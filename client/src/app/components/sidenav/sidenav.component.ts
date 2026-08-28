import { Component, EventEmitter, HostListener, OnDestroy, OnInit, Output, Input, inject } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Subscription, filter, forkJoin, map, merge, of, switchMap } from 'rxjs';
import { Project, TreeNode } from '../../models/issue.model';
import { IssuesService } from '../../services/issues.service';
import { ProjectsService } from '../../services/projects.service';
import { PinStore } from '../../services/pin-store';
import { CollapseStore } from '../../services/collapse-store';
import { ProjectSectionStore } from '../../services/project-section-store';
import { ConsolesService, issueNumberFromSessionId, projectIssueKeyFromSessionId } from '../../services/consoles.service';
import { ProjectConsoleService } from '../../services/project-console.service';
import { AgentStore } from '../../services/agent-store';
import { AppEvent, ConsoleAttentionEvent, EventsService, isConsoleAttentionEvent } from '../../services/events.service';
import { AddProjectPopupComponent } from '../add-project-popup/add-project-popup.component';
import { ConfirmDialogComponent } from '../confirm-dialog/confirm-dialog.component';
import { UsageWidgetComponent } from '../usage-widget/usage-widget.component';
import { filterPinnedTree, filterTree } from './tree-filter';

/** An `issuesChanged` message off the app-wide events channel (#129). */
interface IssuesChangedEvent extends AppEvent {
  type: 'issuesChanged';
  projectId: number;
}

function isIssuesChangedEvent(event: AppEvent): event is IssuesChangedEvent {
  return event.type === 'issuesChanged' && typeof event['projectId'] === 'number';
}

/** How often a project still cloning is re-checked, until it settles (#45). */
const CLONE_POLL_MS = 3000;

/**
 * The tag chip selector's fixed option set (#111) — /t-open's classification labels
 * (docs/adapters/TRACKER.md), not every label a loaded issue happens to carry (e.g.
 * "initiative" is a coordination label, not a classification tag).
 */
const CLASSIFICATION_TAGS = ['bug', 'enhancement', 'documentation', 'question'];

/** One issue, resolved to the project id it's selected/pinned/collapsed within (#44). */
export interface ProjectIssue {
  projectId: number;
  issueNumber: number;
}

interface Section {
  project: Project;
  tree: TreeNode[];
}

interface PinnedGroup {
  project: Project;
  nodes: TreeNode[];
}

// One collapsible section per project (#44), replacing the single "CASES" heading
// #3 shipped when the app only ever managed one project. Pinning/collapsing/
// selection all now carry a project id alongside the issue number, since the same
// issue number can appear in more than one project's section at once.
@Component({
  selector: 'app-sidenav',
  standalone: true,
  imports: [
    FormsModule,
    NgTemplateOutlet,
    RouterLink,
    AddProjectPopupComponent,
    ConfirmDialogComponent,
    UsageWidgetComponent,
  ],
  templateUrl: './sidenav.component.html',
  styleUrl: './sidenav.component.css',
})
export class SidenavComponent implements OnInit, OnDestroy {
  private readonly projectsService = inject(ProjectsService);
  private readonly issuesService = inject(IssuesService);
  private readonly pinStore = inject(PinStore);
  private readonly collapseStore = inject(CollapseStore);
  private readonly projectSectionStore = inject(ProjectSectionStore);
  private readonly consolesService = inject(ConsolesService);
  private readonly eventsService = inject(EventsService);
  private readonly projectConsoleService = inject(ProjectConsoleService);
  private readonly agentStore = inject(AgentStore);
  private readonly router = inject(Router);

  // Highlight only -- navigation is each row's own routerLink (#170), so selection
  // flows in from the URL and never back out through an event.
  @Input() selected: ProjectIssue | null = null;

  /** The project whose own summary page is showing, with no issue selected (#85). */
  @Input() selectedProject: number | null = null;
  @Output() projectSelected = new EventEmitter<number>();

  private sections: Section[] = [];
  loading = true;
  refreshing = false;
  error = false;

  showAddProject = false;

  // Neither persists across reloads, matching the old app (#22's Goal).
  filterText = '';
  hideShipped = true;
  // Off by default (#110): most issues have no branch yet, so defaulting to on
  // would hide almost everything.
  activeBranchOnly = false;
  // Empty means no tag filter (#111); non-empty ORs within itself, same as the
  // rest combine ANDed -- see tree-filter.ts.
  selectedTags: string[] = [];
  readonly classificationTags = CLASSIFICATION_TAGS;

  // The failed project awaiting delete confirmation in the app-styled dialog (#231),
  // replacing the synchronous native `confirm()` this used to block on.
  pendingDeleteProjectId: number | null = null;

  private openMenuFor: string | null = null;
  private pollTimer: ReturnType<typeof setTimeout> | null = null;
  // The project whose "+" is currently minting a console (#180) — guards the
  // one-click entry against a double-click minting two sessions.
  private startingConsoleFor: number | null = null;

  // "<projectId>:<issueNumber>" for every issue with at least one open console
  // (#108), refreshed whenever a console opens or closes anywhere in the app.
  private openConsoleIssues = new Set<string>();
  // "<projectId>:<issueNumber>" for every issue with a console currently waiting for
  // attention (#130) -- a bell, or output gone quiet with no input since. Kept as its
  // own set (rather than folded into openConsoleIssues) since a dot can need to pulse
  // independent of whether the console list has otherwise changed.
  private waitingIssues = new Set<string>();
  private readonly consoleSub: Subscription;
  // "Notify, then fetch" (#129): the event carries no issue data, so a matching
  // project re-fetches its own tree over the existing REST endpoint. A reconnect
  // instead does one full reload, since events missed while the socket was down
  // are gone for good.
  private readonly eventsSub: Subscription;
  // Leaving the new project-level console (#140) asks the sidenav to bust the
  // GhIssueCache for that one project's re-fetch, rather than waiting on the
  // engine's own 30s poll to notice an issue the agent may have just opened.
  private readonly staleSub: Subscription;

  constructor() {
    this.consoleSub = merge(this.consolesService.onOpened, this.consolesService.onClosed).subscribe(() =>
      this.refreshConsoleIndicators(),
    );
    this.eventsSub = merge(
      this.eventsService.events$.pipe(
        filter(isIssuesChangedEvent),
        map((event) => () => this.refreshProject(event.projectId)),
      ),
      this.eventsService.events$.pipe(
        filter(isConsoleAttentionEvent),
        map((event) => () => this.applyAttentionEvent(event)),
      ),
      this.eventsService.reconnected$.pipe(map(() => () => this.load(() => {}))),
    ).subscribe((run) => run());
    this.staleSub = this.issuesService.onProjectStale.subscribe((projectId) =>
      this.refreshProject(projectId, true),
    );
  }

  ngOnInit(): void {
    this.load(() => (this.loading = false));
  }

  ngOnDestroy(): void {
    this.clearPoll();
    this.consoleSub.unsubscribe();
    this.eventsSub.unsubscribe();
    this.staleSub.unsubscribe();
  }

  refresh(): void {
    if (this.refreshing) {
      return;
    }
    this.refreshing = true;
    this.load(() => (this.refreshing = false));
  }

  openAddProject(): void {
    this.showAddProject = true;
  }

  onProjectCreated(): void {
    this.showAddProject = false;
    this.refresh();
  }

  onAddProjectClosed(): void {
    this.showAddProject = false;
  }

  // The header's one-click "+" (#180): mints a brand-new console session (#177) and
  // lands on the project-console page with that console's tab active — the tab strip
  // (#178) reads the `session` query param. One click means no agent picker; the new
  // console gets the pickers' own default agent.
  openNewConsole(projectId: number, event: Event): void {
    event.stopPropagation();
    if (this.startingConsoleFor !== null) {
      return;
    }
    this.startingConsoleFor = projectId;
    this.projectConsoleService.start(projectId).subscribe({
      next: (session) => {
        this.startingConsoleFor = null;
        this.agentStore.set(session.sessionId, 'claude');
        this.consolesService.notifyOpened();
        this.router.navigate(['/projects', projectId, 'console'], {
          queryParams: { session: session.sessionId },
        });
      },
      error: () => {
        this.startingConsoleFor = null;
      },
    });
  }

  isStartingConsole(projectId: number): boolean {
    return this.startingConsoleFor === projectId;
  }

  retryProject(projectId: number, event: Event): void {
    event.stopPropagation();
    this.projectsService.retry(projectId).subscribe(() => this.refresh());
  }

  deleteProject(projectId: number, event: Event): void {
    event.stopPropagation();
    this.pendingDeleteProjectId = projectId;
  }

  confirmDeleteProject(): void {
    const projectId = this.pendingDeleteProjectId;
    this.pendingDeleteProjectId = null;
    if (projectId !== null) {
      this.projectsService.delete(projectId).subscribe(() => this.refresh());
    }
  }

  cancelDeleteProject(): void {
    this.pendingDeleteProjectId = null;
  }

  private load(onDone: () => void): void {
    this.projectsService
      .list()
      .pipe(
        switchMap((projects) =>
          projects.length === 0
            ? of([] as Section[])
            : forkJoin(
                projects.map((project) =>
                  this.issuesService.tree(project.id).pipe(map((tree): Section => ({ project, tree }))),
                ),
              ),
        ),
      )
      .subscribe({
        next: (sections) => {
          this.sections = sections;
          this.error = false;
          onDone();
          this.schedulePollIfNeeded();
          this.refreshConsoleIndicators();
        },
        error: () => {
          this.error = true;
          onDone();
        },
      });
  }

  /**
   * Re-fetches one project's issue tree in place (#129) — a no-op if that project
   * isn't loaded (yet). `fresh` (#140) bypasses the engine's GhIssueCache for this
   * one fetch.
   */
  private refreshProject(projectId: number, fresh = false): void {
    const index = this.sections.findIndex((s) => s.project.id === projectId);
    if (index === -1) {
      return;
    }
    this.issuesService.tree(projectId, fresh).subscribe((tree) => {
      this.sections[index] = { ...this.sections[index], tree };
      this.refreshConsoleIndicators();
    });
  }

  /** Recomputes which issues have an open console (#108), across every loaded project. */
  private refreshConsoleIndicators(): void {
    if (this.sections.length === 0) {
      this.openConsoleIssues = new Set();
      return;
    }
    forkJoin(
      this.sections.map((section) =>
        this.consolesService.list(section.project.id).pipe(map((ids) => ({ projectId: section.project.id, ids }))),
      ),
    ).subscribe((results) => {
      const issues = new Set<string>();
      for (const { projectId, ids } of results) {
        for (const id of ids) {
          const issueNumber = issueNumberFromSessionId(id);
          if (issueNumber !== null) {
            issues.add(`${projectId}:${issueNumber}`);
          }
        }
      }
      this.openConsoleIssues = issues;
    });
  }

  hasOpenConsole(projectId: number, issueNumber: number): boolean {
    return this.openConsoleIssues.has(`${projectId}:${issueNumber}`);
  }

  /** Applies one `consoleAttention` event (#130) onto whichever issue its session belongs to. */
  private applyAttentionEvent(event: ConsoleAttentionEvent): void {
    const key = projectIssueKeyFromSessionId(event.sessionId);
    if (key === null) {
      return;
    }
    if (event.state === 'waiting') {
      this.waitingIssues.add(key);
    } else {
      this.waitingIssues.delete(key);
    }
  }

  hasAttentionWaiting(projectId: number, issueNumber: number): boolean {
    return this.waitingIssues.has(`${projectId}:${issueNumber}`);
  }

  /** Re-checks project status while any project is still cloning (#45), until it settles. */
  private schedulePollIfNeeded(): void {
    this.clearPoll();
    const stillCloning = this.sections.some((s) => s.project.status === 'CLONING');
    if (stillCloning) {
      this.pollTimer = setTimeout(() => this.load(() => {}), CLONE_POLL_MS);
    }
  }

  private clearPoll(): void {
    if (this.pollTimer !== null) {
      clearTimeout(this.pollTimer);
      this.pollTimer = null;
    }
  }

  get projectSections(): Section[] {
    return this.sections;
  }

  get pinnedGroups(): PinnedGroup[] {
    const groups: PinnedGroup[] = [];
    for (const section of this.sections) {
      const pinnedForProject = this.pinStore.list().filter((p) => p.projectId === section.project.id);
      if (pinnedForProject.length === 0) {
        continue;
      }
      const pinnedNumbers = new Set(pinnedForProject.map((p) => p.issueNumber));
      const byNumber = new Map(this.flatten(section.tree).map((n) => [n.number, n]));
      const ordered = pinnedForProject
        .map((p) => byNumber.get(p.issueNumber))
        .filter((n): n is TreeNode => !!n)
        // A child that is *also* individually pinned gets its own top-level pinned
        // entry instead of being duplicated inside its pinned parent.
        .map((n) =>
          n.children.length > 0
            ? { ...n, children: n.children.filter((c) => !pinnedNumbers.has(c.number)) }
            : n,
        );
      // hideShipped never removes a pin, only the text filter can -- see tree-filter.ts.
      const nodes = filterPinnedTree(
        ordered,
        this.filterText,
        this.hideShipped,
        this.activeBranchOnly,
        this.selectedTags,
      );
      if (nodes.length > 0) {
        groups.push({ project: section.project, nodes });
      }
    }
    return groups;
  }

  mainNodesFor(section: Section): TreeNode[] {
    const pinnedNumbers = new Set(
      this.pinStore
        .list()
        .filter((p) => p.projectId === section.project.id)
        .map((p) => p.issueNumber),
    );
    const topLevel = section.tree
      .filter((n) => !pinnedNumbers.has(n.number))
      // A pinned child moves to the Pinned section entirely -- it disappears from
      // its (unpinned) parent's nested children here too, not just avoiding
      // duplication within the Pinned section itself.
      .map((n) =>
        n.children.length > 0
          ? { ...n, children: n.children.filter((c) => !pinnedNumbers.has(c.number)) }
          : n,
      );
    return filterTree(topLevel, this.filterText, this.hideShipped, this.activeBranchOnly, this.selectedTags);
  }

  isTagSelected(tag: string): boolean {
    return this.selectedTags.includes(tag);
  }

  toggleTag(tag: string): void {
    this.selectedTags = this.isTagSelected(tag)
      ? this.selectedTags.filter((t) => t !== tag)
      : [...this.selectedTags, tag];
  }

  // Counted off the raw tree, before the text filter and hideShipped run (#186):
  // the header answers "how much open work is here", not "how many rows are showing".
  openIssueCount(section: Section): number {
    return this.flatten(section.tree).filter((n) => n.state === 'OPEN').length;
  }

  isProjectCollapsed(projectId: number): boolean {
    return this.projectSectionStore.isCollapsed(projectId);
  }

  isProjectSelected(projectId: number): boolean {
    return this.selectedProject === projectId;
  }

  // The header row selects the project (#85); folding moved onto the twisty, which
  // is what already means "fold" on an initiative row. One row cannot do both.
  selectProject(projectId: number): void {
    this.projectSelected.emit(projectId);
  }

  toggleProjectCollapse(projectId: number, event: Event): void {
    event.stopPropagation();
    this.projectSectionStore.toggle(projectId);
  }

  isCollapsed(projectId: number, node: TreeNode): boolean {
    // A fold never hides a filter match: an active filter always shows everything
    // it matched, regardless of stored fold state.
    return this.hasActiveFilter() ? false : this.collapseStore.isCollapsed(projectId, node.number);
  }

  // The row controls live inside the row's anchor (#170): stopPropagation keeps
  // their clicks out of routerLink's handler, and preventDefault stops the browser
  // from following the row's href itself.
  toggleCollapse(projectId: number, node: TreeNode, event: Event): void {
    event.stopPropagation();
    event.preventDefault();
    this.collapseStore.toggle(projectId, node.number);
  }

  isPinned(projectId: number, issueNumber: number): boolean {
    return this.pinStore.isPinned(projectId, issueNumber);
  }

  togglePin(projectId: number, issueNumber: number, event: Event): void {
    event.stopPropagation();
    event.preventDefault();
    this.pinStore.toggle(projectId, issueNumber);
    this.openMenuFor = null;
  }

  isMenuOpen(projectId: number, issueNumber: number): boolean {
    return this.openMenuFor === this.menuKey(projectId, issueNumber);
  }

  toggleMenu(projectId: number, issueNumber: number, event: Event): void {
    event.stopPropagation();
    event.preventDefault();
    const key = this.menuKey(projectId, issueNumber);
    this.openMenuFor = this.openMenuFor === key ? null : key;
  }

  @HostListener('document:click')
  closeMenu(): void {
    this.openMenuFor = null;
  }

  isSelected(projectId: number, issueNumber: number): boolean {
    return this.selected !== null && this.selected.projectId === projectId && this.selected.issueNumber === issueNumber;
  }

  private menuKey(projectId: number, issueNumber: number): string {
    return `${projectId}-${issueNumber}`;
  }

  private hasActiveFilter(): boolean {
    return this.filterText.trim().length > 0 || this.activeBranchOnly || this.selectedTags.length > 0;
  }

  private flatten(nodes: TreeNode[]): TreeNode[] {
    return nodes.flatMap((n) => [n, ...n.children]);
  }
}
