import { HttpErrorResponse } from '@angular/common/http';
import { Component, EventEmitter, HostListener, OnDestroy, OnInit, Output, Input, inject } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { CdkDragDrop, DragDropModule, moveItemInArray } from '@angular/cdk/drag-drop';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Subscription, filter, forkJoin, map, merge, of, switchMap } from 'rxjs';
import { Project, TreeNode } from '../../models/issue.model';
import { IssuesService } from '../../services/issues.service';
import { ProjectsService } from '../../services/projects.service';
import { PinStore } from '../../services/pin-store';
import { CollapseStore } from '../../services/collapse-store';
import { ProjectSectionStore } from '../../services/project-section-store';
import {
  ConsolesService,
  isProjectConsoleSessionId,
  issueNumberFromSessionId,
  projectIdFromProjectConsoleSessionId,
  projectIssueKeyFromSessionId,
} from '../../services/consoles.service';
import { AppEvent, ConsoleAttentionEvent, EventsService, isConsoleAttentionEvent } from '../../services/events.service';
import { RunningVersionService } from '../../services/running-version.service';
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

/** One issue, resolved to the project id it's selected/pinned/collapsed within (#44). */
export interface ProjectIssue {
  projectId: number;
  issueNumber: number;
}

export interface Section {
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
  imports: [FormsModule, NgTemplateOutlet, RouterLink, DragDropModule, ConfirmDialogComponent, UsageWidgetComponent],
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
  private readonly router = inject(Router);

  // The engine's own version off the events-channel greeting (#467), for the footer
  // line pinned under the usage widget. Null until the first connect delivers it.
  readonly runningVersion = inject(RunningVersionService).version;

  // Highlight only -- navigation is each row's own routerLink (#170), so selection
  // flows in from the URL and never back out through an event.
  @Input() selected: ProjectIssue | null = null;

  /** The project whose own summary page is showing, with no issue selected (#85). */
  @Input() selectedProject: number | null = null;
  @Output() projectSelected = new EventEmitter<number>();

  // Set by AppComponent from the `focus=1` query param (#286): restricts this sidenav
  // to the one focused project -- it neither fetches nor renders any other project's
  // section -- rather than the workspace-wide view every other window shows.
  @Input() focusedProjectId: number | null = null;

  private sections: Section[] = [];
  loading = true;
  refreshing = false;
  error = false;

  // Neither persists across reloads, matching the old app (#22's Goal).
  filterText = '';
  hideShipped = true;

  // The failed project awaiting delete confirmation in the app-styled dialog (#231),
  // replacing the synchronous native `confirm()` this used to block on.
  pendingDeleteProjectId: number | null = null;

  // A failed delete's inline error (#250), mirroring ProjectSummaryComponent's own
  // delete-error handling. Tracked by project id rather than a bare string since more
  // than one FAILED project can be listed at once, each with its own delete action.
  deleteErrorProjectId: number | null = null;
  deleteError: string | null = null;

  private openMenuFor: string | null = null;
  private pollTimer: ReturnType<typeof setTimeout> | null = null;

  // "<projectId>:<issueNumber>" for every issue with at least one open console
  // (#108), refreshed whenever a console opens or closes anywhere in the app.
  private openConsoleIssues = new Set<string>();
  // Project ids with an open project-level console (#330) -- a session id like
  // "<projectId>-console" or "<projectId>-console-<suffix>" carries no issue number,
  // so it can never land in openConsoleIssues; tracked separately and merged into
  // hasOpenConsoleForProject below.
  private openConsoleProjects = new Set<number>();
  // "<projectId>:<issueNumber>" for every issue with a console currently waiting for
  // attention (#130) -- a bell, or output gone quiet with no input since. Kept as its
  // own set (rather than folded into openConsoleIssues) since a dot can need to pulse
  // independent of whether the console list has otherwise changed.
  private waitingIssues = new Set<string>();
  // Raw session ids of project-level consoles currently waiting for attention (#450).
  // Keyed off the session id itself, like the header's console-indicator, rather than
  // the project id: with two project consoles open, one going active must not clear a
  // flag another still-waiting console set.
  private waitingProjectConsoleSessions = new Set<string>();
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

  // The header's one-click "+" (#180): asks the project console page for a brand-new
  // console (#177) and lands on it with that console's tab active. The request rides
  // in the `new` query param rather than this button minting the session itself
  // (#370) — a session the engine has never attached to is absent from the page's
  // open-console list, so the old `?session=<freshId>` handoff was discarded there
  // and some existing console was shown instead, stranding the new console's
  // worktree on disk. The page mints it, adds its tab, and drops the param again.
  // One click still means no agent picker: the new console gets the Settings default
  // agent (#219), which the page applies.
  openNewConsole(projectId: number, event: Event): void {
    event.stopPropagation();
    this.router.navigate(['/projects', projectId, 'console'], { queryParams: { new: 1 } });
  }

  // Opens this project alone in a new browser window (#286): the focused state rides
  // in the URL's `focus` query param, not a shared service, so the popped-out window
  // re-derives everything from its own route the same way this one does. When this
  // project is the one currently open, the new window keeps whatever issue/console
  // route is showing here; otherwise there is no "current" route to carry, so it
  // falls back to the project's own base route.
  popOutProject(projectId: number, event: Event): void {
    event.stopPropagation();
    const tree = this.isActiveProject(projectId)
      ? this.router.parseUrl(this.router.url)
      : this.router.createUrlTree(['/projects', projectId, 'issues']);
    tree.queryParams = { ...tree.queryParams, focus: '1' };
    window.open(this.router.serializeUrl(tree), '_blank');
  }

  private isActiveProject(projectId: number): boolean {
    return this.selectedProject === projectId || this.selected?.projectId === projectId;
  }

  retryProject(projectId: number, event: Event): void {
    event.stopPropagation();
    this.projectsService.retry(projectId).subscribe(() => this.refresh());
  }

  deleteProject(projectId: number, event: Event): void {
    event.stopPropagation();
    this.deleteErrorProjectId = null;
    this.deleteError = null;
    this.pendingDeleteProjectId = projectId;
  }

  confirmDeleteProject(): void {
    const projectId = this.pendingDeleteProjectId;
    this.pendingDeleteProjectId = null;
    if (projectId === null) {
      return;
    }
    this.projectsService.delete(projectId).subscribe({
      next: () => this.refresh(),
      error: (err: HttpErrorResponse) => {
        this.deleteErrorProjectId = projectId;
        this.deleteError = err.error?.error ?? 'could not delete this project';
      },
    });
  }

  cancelDeleteProject(): void {
    this.pendingDeleteProjectId = null;
  }

  deleteErrorFor(projectId: number): string | null {
    return this.deleteErrorProjectId === projectId ? this.deleteError : null;
  }

  private load(onDone: () => void): void {
    this.projectsService
      .list()
      .pipe(
        switchMap((projects) => {
          // Focus mode (#286): narrow to the one focused project before fetching any
          // tree, so no other project's (expensive) tree is ever requested or shown.
          const relevant =
            this.focusedProjectId === null
              ? projects
              : projects.filter((p) => p.id === this.focusedProjectId);
          return relevant.length === 0
            ? of([] as Section[])
            : forkJoin(
                relevant.map((project) =>
                  this.issuesService.tree(project.id).pipe(map((tree): Section => ({ project, tree }))),
                ),
              );
        }),
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
      this.openConsoleProjects = new Set();
      return;
    }
    forkJoin(
      this.sections.map((section) =>
        this.consolesService.list(section.project.id).pipe(map((ids) => ({ projectId: section.project.id, ids }))),
      ),
    ).subscribe((results) => {
      const issues = new Set<string>();
      const projects = new Set<number>();
      for (const { projectId, ids } of results) {
        for (const id of ids) {
          const issueNumber = issueNumberFromSessionId(id);
          if (issueNumber !== null) {
            issues.add(`${projectId}:${issueNumber}`);
          } else if (isProjectConsoleSessionId(id)) {
            projects.add(projectId);
          }
        }
      }
      this.openConsoleIssues = issues;
      this.openConsoleProjects = projects;
    });
  }

  hasOpenConsole(projectId: number, issueNumber: number): boolean {
    return this.openConsoleIssues.has(`${projectId}:${issueNumber}`);
  }

  // Backs the section header's per-project consoles button (#312). Tracks
  // project-level console sessions exclusively (#330) -- it does not aggregate
  // issue-attached consoles under the project; each issue row's own dot already
  // covers those. A project-level session id ("<projectId>-console[-suffix]") carries
  // no issue number, so it's tracked separately in openConsoleProjects rather than
  // openConsoleIssues.
  hasOpenConsoleForProject(projectId: number): boolean {
    return this.openConsoleProjects.has(projectId);
  }

  // Like hasOpenConsoleForProject above, tracks project-level console sessions
  // exclusively (#450) -- an issue-attached console's wait shows on that issue row's
  // own dot, never here.
  hasAttentionWaitingForProject(projectId: number): boolean {
    for (const sessionId of this.waitingProjectConsoleSessions) {
      if (projectIdFromProjectConsoleSessionId(sessionId) === projectId) {
        return true;
      }
    }
    return false;
  }

  // Jumps straight to this project's console page (#312) -- the button that
  // triggers this only ever renders once hasOpenConsoleForProject is true.
  openProjectConsoles(projectId: number, event: Event): void {
    event.stopPropagation();
    this.router.navigate(['/projects', projectId, 'console']);
  }

  /**
   * Applies one `consoleAttention` event (#130) onto whichever issue its session
   * belongs to -- or, for a project-level console's session id, which carries no
   * issue number, onto its project's own row (#450).
   */
  private applyAttentionEvent(event: ConsoleAttentionEvent): void {
    const key = projectIssueKeyFromSessionId(event.sessionId);
    if (key !== null) {
      if (event.state === 'waiting') {
        this.waitingIssues.add(key);
      } else {
        this.waitingIssues.delete(key);
      }
      return;
    }
    if (projectIdFromProjectConsoleSessionId(event.sessionId) === null) {
      return;
    }
    if (event.state === 'waiting') {
      this.waitingProjectConsoleSessions.add(event.sessionId);
    } else {
      this.waitingProjectConsoleSessions.delete(event.sessionId);
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

  /**
   * Reorders the project sections in place (#541) so the drop lands immediately,
   * without waiting on the persist request or a reload; a failed persist re-loads to
   * fall back to whatever order the server actually kept.
   */
  onProjectSectionDrop(event: CdkDragDrop<Section[]>): void {
    if (event.previousIndex === event.currentIndex) {
      return;
    }
    moveItemInArray(this.sections, event.previousIndex, event.currentIndex);
    const orderedIds = this.sections.map((s) => s.project.id);
    this.projectsService.setOrder(orderedIds).subscribe({
      error: () => this.load(() => {}),
    });
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
      const nodes = filterPinnedTree(ordered, this.filterText, this.hideShipped, [], (n) =>
        this.hasOpenConsole(section.project.id, n.number),
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
    return filterTree(topLevel, this.filterText, this.hideShipped, [], (n) =>
      this.hasOpenConsole(section.project.id, n.number),
    );
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
    return this.filterText.trim().length > 0;
  }

  private flatten(nodes: TreeNode[]): TreeNode[] {
    return nodes.flatMap((n) => [n, ...n.children]);
  }
}
