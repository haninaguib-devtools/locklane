import { HttpErrorResponse } from '@angular/common/http';
import { Component, EventEmitter, HostListener, OnChanges, OnDestroy, OnInit, Output, Input, SimpleChanges, inject } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { CdkDragDrop, DragDropModule, moveItemInArray } from '@angular/cdk/drag-drop';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Subscription, filter, forkJoin, map, merge } from 'rxjs';
import { GithubRefreshStatus, Project, TreeNode, TreeResponse } from '../../models/issue.model';
import { IssuesService } from '../../services/issues.service';
import { ProjectsService } from '../../services/projects.service';
import { PinStore } from '../../services/pin-store';
import { CollapseStore } from '../../services/collapse-store';
import { ProjectSectionStore } from '../../services/project-section-store';
import { AttentionStore } from '../../services/attention-store';
import {
  AgentSessionsService,
  isProjectAgentSessionId,
  issueNumberFromSessionId,
  projectIdFromProjectAgentSessionId,
  projectIssueKeyFromSessionId,
} from '../../services/agent-sessions.service';
import {
  AppEvent,
  EventsService,
  GithubRefreshStatusEvent,
  ProjectCreatedEvent,
  ProjectDeletedEvent,
  ProjectStatusEvent,
  isGithubRefreshStatusEvent,
  isProjectCreatedEvent,
  isProjectDeletedEvent,
  isProjectStatusEvent,
} from '../../services/events.service';
import { ConfirmDialogComponent } from '../confirm-dialog/confirm-dialog.component';
import { UsageWidgetComponent } from '../usage-widget/usage-widget.component';
import { cloneStageHint } from '../clone-progress';
import { filterPinnedTree, filterTree } from './tree-filter';

/** An `issuesChanged` message off the app-wide events channel (#129). */
interface IssuesChangedEvent extends AppEvent {
  type: 'issuesChanged';
  projectId: number;
}

function isIssuesChangedEvent(event: AppEvent): event is IssuesChangedEvent {
  return event.type === 'issuesChanged' && typeof event['projectId'] === 'number';
}

/** "just now", "3 min ago", "2 h ago", "4 d ago" -- coarse on purpose; a failing refresh is re-polled every 30 s. */
export function formatAgo(iso: string, nowMs: number): string {
  const seconds = Math.max(0, Math.round((nowMs - new Date(iso).getTime()) / 1000));
  if (seconds < 60) {
    return 'just now';
  }
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) {
    return `${minutes} min ago`;
  }
  const hours = Math.round(minutes / 60);
  if (hours < 24) {
    return `${hours} h ago`;
  }
  return `${Math.round(hours / 24)} d ago`;
}

/** One issue, resolved to the project id it's selected/pinned/collapsed within (#44). */
export interface ProjectIssue {
  projectId: number;
  issueNumber: number;
}

/**
 * Where one project's own issue-tree request stands (#787): `loading` until its
 * first tree has ever arrived, then `loaded`, or `failed` when the last request
 * for it errored. Each section carries its own, so one slow or failing project's
 * tree never holds back, or hides, the others.
 */
export type TreeState = 'loading' | 'loaded' | 'failed';

export interface Section {
  project: Project;
  tree: TreeNode[];
  // The outcome of the engine's most recent GitHub fetch for this project (#619).
  github: GithubRefreshStatus;
  treeState: TreeState;
}

const GITHUB_UNKNOWN: GithubRefreshStatus = { failing: false, failure: null, lastSuccessAt: null };

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
export class SidenavComponent implements OnInit, OnChanges, OnDestroy {
  private readonly projectsService = inject(ProjectsService);
  private readonly issuesService = inject(IssuesService);
  private readonly pinStore = inject(PinStore);
  private readonly collapseStore = inject(CollapseStore);
  private readonly projectSectionStore = inject(ProjectSectionStore);
  private readonly agentSessionsService = inject(AgentSessionsService);
  private readonly eventsService = inject(EventsService);
  // The one shared "which sessions are waiting" store (#791): the dots below read
  // from it rather than this component keeping its own copy fed from `events$`.
  private readonly attentionStore = inject(AttentionStore);
  private readonly router = inject(Router);

  // Highlight only -- navigation is each row's own routerLink (#170), so selection
  // flows in from the URL and never back out through an event. The setter also moves
  // DOM focus onto the newly-selected row (#747), so arrow keys work immediately after
  // a click without a second click to focus it first.
  private _selected: ProjectIssue | null = null;
  @Input()
  set selected(value: ProjectIssue | null) {
    this._selected = value;
    this.focusSelectedRow();
  }
  get selected(): ProjectIssue | null {
    return this._selected;
  }

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
  // A `refresh()` came in while one was already in flight (#738) -- queued rather than
  // dropped, since the in-flight one may have been sent before whatever prompted this
  // one (most commonly a just-created project's `revealProject`), so its response
  // can't possibly reflect it. Run once, right after the in-flight one settles, so a
  // reveal is never left waiting on a load that started too early to ever find its row.
  private refreshQueued = false;
  // Whether any of the queued callers asked for a cache-bypassing reload (#760): one
  // queued run serves them all, so it is fresh if any of them wanted it fresh.
  private refreshQueuedFresh = false;
  error = false;
  // A later refresh()'s own /api/projects request failed after the list had already
  // rendered at least once (#801): the sidenav-wide `error` state above only ever
  // covers the *first* load, so sections stay in the DOM and this notice covers the
  // failure instead -- "what you had, plus a notice", the same shape a failed
  // per-project tree fetch already gets (#787). Cleared on the next successful list
  // load, same as `error`.
  listRefreshFailed = false;
  // Whether the project list has ever loaded successfully (#801): distinguishes a
  // refresh's failed list request (show listRefreshFailed, keep sections) from the
  // very first load's failure (show the sidenav-wide `error` state, nothing to keep).
  private hasLoadedList = false;

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

  // Live clone progress (#717): first-seen timestamps per cloning project drive the
  // elapsed-seconds counters, and the 1s tick only wakes change detection -- the
  // getters read the clock directly. `revealedProjectId` briefly highlights the row
  // a just-finished import asked to reveal.
  private cloneFirstSeen = new Map<number, number>();
  private tickTimer: ReturnType<typeof setInterval> | null = null;
  revealedProjectId: number | null = null;
  private revealTimer: ReturnType<typeof setTimeout> | null = null;
  private pendingRevealId: number | null = null;
  private pendingRevealDone: (() => void) | null = null;
  // "<projectId>:<issueNumber>" for every issue with at least one open agent session
  // (#108), refreshed whenever an agent session opens or closes anywhere in the app.
  private openAgentSessionIssues = new Set<string>();
  // Project ids with an open project-level agent session (#330) -- a session id like
  // "<projectId>-console" or "<projectId>-console-<suffix>" (the persisted id shape, kept under ADR-112) carries no issue number,
  // so it can never land in openAgentSessionIssues; tracked separately and merged into
  // hasOpenAgentSessionForProject below.
  private openAgentSessionProjects = new Set<number>();
  private readonly agentSessionSub: Subscription;
  // "Notify, then fetch" (#129): the event carries no issue data, so a matching
  // project re-fetches its own tree over the existing REST endpoint -- and a project
  // not listed here reloads the whole list instead (#760), as does a `projectCreated`.
  // A reconnect instead does one full reload, since events missed while the socket
  // was down are gone for good.
  private readonly eventsSub: Subscription;
  // Leaving the new project-level agent session (#140) asks the sidenav to bust the
  // GhIssueCache for that one project's re-fetch, rather than waiting on the
  // engine's own 30s poll to notice an issue the agent may have just opened.
  private readonly staleSub: Subscription;
  // Clone-settled events (#721) that arrived for a project not loaded yet (#729):
  // an import reveals its new row via a full reload, and the engine's clone can
  // settle while that reload is still in flight -- the list already answered with
  // CLONING, the row does not exist here yet, so the event would be lost and the
  // row stuck on cloning until the next full reload. Held here until the reload
  // lands, then applied to the row it was meant for. In a window that is *not* the
  // creating one no such reload is in flight, so `applyProjectStatusEvent` starts
  // one (#760) rather than holding the event for a reload that would never come.
  private readonly pendingStatus = new Map<number, ProjectStatusEvent>();
  // Bumped each time a load replaces `sections` (#787). Every tree request a load
  // sends carries the generation it was sent for, so a response that lands after a
  // later load has already rebuilt the list is dropped rather than written by id:
  // that later load has its own request for the same project in flight, and an
  // older response landing afterwards would overwrite the fresher tree.
  private sectionsGeneration = 0;
  // Project ids whose tree request from the current load is still in flight (#787).
  // A clone that settles READY while its own tree request is still out (#729)
  // waits for that request to land before re-fetching, rather than racing it.
  private loadingTrees = new Set<number>();

  constructor() {
    this.agentSessionSub = merge(this.agentSessionsService.onOpened, this.agentSessionsService.onClosed).subscribe(() =>
      this.refreshAgentSessionIndicators(),
    );
    this.eventsSub = merge(
      this.eventsService.events$.pipe(
        filter(isIssuesChangedEvent),
        map((event) => () => this.applyIssuesChangedEvent(event)),
      ),
      this.eventsService.events$.pipe(
        filter(isGithubRefreshStatusEvent),
        map((event) => () => this.applyGithubStatusEvent(event)),
      ),
      this.eventsService.events$.pipe(
        filter(isProjectStatusEvent),
        map((event) => () => this.applyProjectStatusEvent(event)),
      ),
      this.eventsService.events$.pipe(
        filter(isProjectDeletedEvent),
        map((event) => () => this.applyProjectDeletedEvent(event)),
      ),
      this.eventsService.events$.pipe(
        filter(isProjectCreatedEvent),
        map((event) => () => this.applyProjectCreatedEvent(event)),
      ),
      this.eventsService.reconnected$.pipe(map(() => () => this.load(() => {}))),
    ).subscribe((run) => run());
    this.staleSub = this.issuesService.onProjectStale.subscribe((projectId) =>
      this.refreshProject(projectId, true),
    );
  }

  /** Whether ngOnInit's own first load has run (#803) -- see ngOnChanges. */
  private initialized = false;

  ngOnInit(): void {
    this.initialized = true;
    this.load(() => {});
  }
  // A focus change after the first load (#803) -- the URL gaining or losing `focus=1`
  // while this sidenav is already showing -- re-narrows the list right away, the same
  // reload an events-channel reconnect runs, rather than leaving the old list on
  // screen until the next refresh() happens to rebuild it. The value bound before
  // ngOnInit is read by its own load; `initialized` (not `firstChange`, which only
  // says whether the input system saw an earlier value) is what tells the two apart.
  ngOnChanges(changes: SimpleChanges): void {
    if (changes['focusedProjectId'] !== undefined && this.initialized) {
      this.load(() => {});
    }
  }
  ngOnDestroy(): void {
    this.clearTick();
    this.clearReveal();
    this.agentSessionSub.unsubscribe();
    this.eventsSub.unsubscribe();
    this.staleSub.unsubscribe();
  }

  /**
   * Reloads the project list and every tree. `fresh` (#545) bypasses the engine's
   * GhIssueCache for each tree fetch -- the default, for the refresh button and a
   * just-created project's reveal, so they show what GitHub has right now. An
   * event-driven reload (#760) passes `false`: the event says the *list* changed,
   * not that every project's cached tree is stale, and a cache-bypassing reload in
   * every open window per event would cost one GitHub fetch per project per window.
   */
  refresh(fresh = true): void {
    if (this.refreshing) {
      this.refreshQueued = true;
      this.refreshQueuedFresh = this.refreshQueuedFresh || fresh;
      return;
    }
    this.refreshing = true;
    this.load(() => this.finishRefresh(), fresh);
  }

  /** Runs a queued refresh, if one arrived while this one was in flight (#738). */
  private finishRefresh(): void {
    this.refreshing = false;
    if (this.refreshQueued) {
      const fresh = this.refreshQueuedFresh;
      this.refreshQueued = false;
      this.refreshQueuedFresh = false;
      this.refresh(fresh);
    }
  }

  // The header's one-click "+" (#180): asks the project agent session page for a brand-new
  // agent session (#177) and lands on it with that agent session's tab active. The request rides
  // in the `new` query param rather than this button minting the session itself
  // (#370) — a session the engine has never attached to is absent from the page's
  // open-agent-session list, so the old `?session=<freshId>` handoff was discarded there
  // and some existing agent session was shown instead, stranding the new agent session's
  // worktree on disk. The page mints it, adds its tab, and drops the param again.
  // One click still means no agent picker: the new agent session gets the Settings default
  // agent (#219), which the page applies.
  openNewAgentSession(projectId: number, event: Event): void {
    event.stopPropagation();
    // 'console' is the route path segment -- a compatibility surface kept under ADR-112.
    this.router.navigate(['/projects', projectId, 'console'], { queryParams: { new: 1 } });
  }

  // Opens this project alone in a new browser window (#286): the focused state rides
  // in the URL's `focus` query param, not a shared service, so the popped-out window
  // re-derives everything from its own route the same way this one does. When this
  // project is the one currently open, the new window keeps whatever issue/agent session
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

  /**
   * Reloads the project list, then every project's tree. The sections are built --
   * and rendered -- the moment the list arrives, in list order (#787); each tree
   * request then fills in its own section as it lands, so one slow or failing
   * project never holds the others back or hides them. A section that had already
   * loaded keeps showing its current tree until the new one replaces it, the same
   * in-place update a reload always gave; one that never had a tree (or whose last
   * request failed) shows its own loading state until its request lands.
   *
   * `onDone` fires once every tree request has settled, success or failure -- or
   * right away when the list itself fails -- so `refreshing`, and the queued
   * refresh behind it (#738), still cover the whole reload.
   *
   * `fresh` (#545) bypasses the engine's `GhIssueCache` for every project's tree
   * fetch, the same way `refreshProject` already does for one project alone — for
   * the refresh button, so it shows the current issue list rather than whatever the
   * cache already held from the last fetch.
   */
  private load(onDone: () => void, fresh = false): void {
    this.projectsService.list().subscribe({
      next: (projects) => {
        // Focus mode (#286): narrow to the one focused project before fetching any
        // tree, so no other project's (expensive) tree is ever requested or shown.
        const relevant =
          this.focusedProjectId === null ? projects : projects.filter((p) => p.id === this.focusedProjectId);
        const previous = new Map(this.sections.map((s) => [s.project.id, s]));
        this.sections = relevant.map((project): Section => {
          const carried = previous.get(project.id);
          return carried !== undefined && carried.treeState === 'loaded'
            ? { ...carried, project }
            : { project, tree: [], github: GITHUB_UNKNOWN, treeState: 'loading' };
        });
        const generation = ++this.sectionsGeneration;
        this.loadingTrees = new Set(relevant.map((p) => p.id));
        this.loading = false;
        this.error = false;
        this.listRefreshFailed = false;
        this.hasLoadedList = true;
        this.trackCloneProgress();

        let outstanding = relevant.length;
        const settle = () => {
          if (--outstanding === 0) {
            onDone();
          }
        };
        for (const project of relevant) {
          // Whether the engine already reported this project READY when its tree
          // was requested: a clone that settles while the request is out (#729)
          // may have been answered with the empty pre-clone tree, so the response
          // handler re-fetches once it knows the status changed underneath it.
          const readyAtRequest = project.status === 'READY';
          this.issuesService.treeWithStatus(project.id, fresh).subscribe({
            next: (response) => {
              this.applyLoadedTree(generation, project.id, response, readyAtRequest);
              settle();
            },
            error: () => {
              this.applyFailedTree(generation, project.id);
              settle();
            },
          });
        }
        // After the requests are out, so a held READY event (#729) sees this
        // project's tree still loading and defers its re-fetch (see
        // `applyProjectStatusEvent`) rather than sending a duplicate now.
        this.applyPendingStatusEvents();
        this.maybeReveal();
        this.refreshAgentSessionIndicators();
        if (relevant.length === 0) {
          onDone();
        }
      },
      error: () => {
        this.loading = false;
        // The sidenav-wide `error` state only ever covers the first load, with
        // nothing yet rendered to keep; once the list has loaded once, a later
        // failure leaves the existing sections alone and surfaces a notice instead
        // (#801).
        if (this.hasLoadedList) {
          this.listRefreshFailed = true;
        } else {
          this.error = true;
        }
        onDone();
        // `onDone` (`finishRefresh`, for a refresh-triggered load) may have just
        // started a fresh attempt for a queued refresh (#738) -- `refreshing` is
        // true again in that case, and the pending reveal stays for it rather than
        // being dropped here.
        if (!this.refreshing) {
          this.dropPendingReveal();
        }
      },
    });
  }

  /**
   * Writes one tree response from a load into its own section (#787), looked up by
   * project id now rather than by the index it had when requested (#760). A
   * response from a load whose sections a later load has since replaced is dropped:
   * that later load has its own request for this project in flight.
   */
  private applyLoadedTree(generation: number, projectId: number, response: TreeResponse, readyAtRequest: boolean): void {
    if (generation !== this.sectionsGeneration) {
      return;
    }
    this.loadingTrees.delete(projectId);
    const index = this.sections.findIndex((s) => s.project.id === projectId);
    if (index === -1) {
      return;
    }
    const section = this.sections[index];
    this.sections[index] = { ...section, tree: response.nodes, github: response.github, treeState: 'loaded' };
    // The selected input can arrive before the tree that carries its row does
    // (e.g. loading a URL straight onto an issue) -- try again now that it has.
    if (this._selected?.projectId === projectId) {
      this.focusSelectedRow();
    }
    if (!readyAtRequest && section.project.status === 'READY') {
      // The clone settled while this request was out (#729): the tree it returned
      // may be the empty one from before the clone finished, so fetch the real one.
      this.refreshProject(projectId);
    }
  }

  /** One project's tree request from a load failed (#787): that section alone shows it. */
  private applyFailedTree(generation: number, projectId: number): void {
    if (generation !== this.sectionsGeneration) {
      return;
    }
    this.loadingTrees.delete(projectId);
    const index = this.sections.findIndex((s) => s.project.id === projectId);
    if (index === -1) {
      return;
    }
    this.sections[index] = { ...this.sections[index], treeState: 'failed' };
  }

  /**
   * An `issuesChanged` (#129) for a project listed here re-fetches that one tree in
   * place. For a project this sidenav does not list (#760) it reloads the whole list
   * instead: the project was created in another window (or its `projectCreated`
   * never arrived), and dropping the event would leave it -- and every issue in it
   * from then on -- invisible here until someone reloads the page.
   */
  private applyIssuesChangedEvent(event: IssuesChangedEvent): void {
    if (this.hasSection(event.projectId)) {
      this.refreshProject(event.projectId);
    } else if (this.couldList(event.projectId)) {
      this.refresh(false);
    }
  }

  /**
   * A project was created somewhere (#760) -- in this window, where `revealProject`
   * is already reloading (so this one queues behind it, #738), or in another, where
   * nothing else was ever going to fetch the new row. Reload so it exists here too.
   */
  private applyProjectCreatedEvent(event: ProjectCreatedEvent): void {
    if (this.couldList(event.projectId)) {
      this.refresh(false);
    }
  }

  /**
   * Whether a project could ever appear in this sidenav: a focused window (#286)
   * lists exactly one project, so an event about any other is never a reason to
   * reload -- the reload could not carry it.
   */
  private couldList(projectId: number): boolean {
    return this.focusedProjectId === null || this.focusedProjectId === projectId;
  }

  private hasSection(projectId: number): boolean {
    return this.sections.some((s) => s.project.id === projectId);
  }

  /**
   * Re-fetches one project's issue tree in place (#129) — a no-op if that project
   * isn't loaded (yet). `fresh` (#140) bypasses the engine's GhIssueCache for this
   * one fetch.
   */
  private refreshProject(projectId: number, fresh = false): void {
    if (!this.hasSection(projectId)) {
      return;
    }
    this.issuesService.treeWithStatus(projectId, fresh).subscribe({
      next: (response) => {
        // Looked up again now, not at request time (#760): a reload that replaced
        // `sections` while this fetch was in flight may have moved this project to
        // another index -- or dropped it -- and writing to the old index would hand
        // this tree to whatever project sits there now.
        const index = this.sections.findIndex((s) => s.project.id === projectId);
        if (index === -1) {
          return;
        }
        this.sections[index] = { ...this.sections[index], tree: response.nodes, github: response.github, treeState: 'loaded' };
        this.refreshAgentSessionIndicators();
      },
      error: () => {
        // The same per-project failure state a load's own request gets (#787),
        // keeping whatever tree the section already showed.
        const index = this.sections.findIndex((s) => s.project.id === projectId);
        if (index !== -1) {
          this.sections[index] = { ...this.sections[index], treeState: 'failed' };
        }
      },
    });
  }

  /**
   * The engine's GitHub fetch for a project started or stopped failing (#619): update
   * that project's status in place so the error appears (or clears) without a
   * re-fetch. A project not loaded here is ignored, the same as `issuesChanged`.
   */
  private applyGithubStatusEvent(event: GithubRefreshStatusEvent): void {
    const index = this.sections.findIndex((s) => s.project.id === event.projectId);
    if (index === -1) {
      return;
    }
    const current = this.sections[index].github;
    this.sections[index] = {
      ...this.sections[index],
      github: {
        failing: event.failing,
        failure: event.failure ?? null,
        lastSuccessAt: event.lastSuccessAt ?? current.lastSuccessAt,
      },
    };
  }

  /**
   * A clone reached READY or FAILED (#721): update that project's status (and, for
   * READY, its default branch) in place -- no re-fetch needed, and this is what
   * replaces the 3s cloning poll that used to notice this instead. A project not
   * loaded here is held until a reload carries it (#729) -- and that reload is
   * started here when none is in flight (#760). `trackCloneProgress` re-runs so a
   * settled project's elapsed-seconds tracking (#717) drops along with it.
   */
  private applyProjectStatusEvent(event: ProjectStatusEvent): void {
    const index = this.sections.findIndex((s) => s.project.id === event.projectId);
    if (index === -1) {
      if (!this.couldList(event.projectId)) {
        return;
      }
      // Not loaded yet. Keep the event so the reload that carries this project can
      // apply it (#729); a project that never shows up again is dropped by its own
      // projectDeleted event. In the creating window that reload is already in
      // flight (`revealProject`); in every other window nothing was going to fetch
      // the row this event is about, so start one (#760). A reload already running
      // is left alone: the held event rides on it, and re-fetching everything
      // behind it would be the re-polling #729 removed.
      this.pendingStatus.set(event.projectId, event);
      if (!this.refreshing) {
        this.refresh(false);
      }
      return;
    }
    this.pendingStatus.delete(event.projectId);
    const previous = this.sections[index].project.status;
    this.sections[index] = {
      ...this.sections[index],
      project: {
        ...this.sections[index].project,
        status: event.status,
        defaultBranch: event.defaultBranch ?? this.sections[index].project.defaultBranch,
      },
    };
    this.trackCloneProgress();
    if (event.status === 'READY' && previous !== 'READY' && !this.loadingTrees.has(event.projectId)) {
      // The tree fetched while the project was still cloning is empty; fetch the
      // real one now so a newly READY row does not sit empty (#729). When this
      // project's own tree request from a load is still out (#787), that request's
      // handler does the re-fetch once it lands instead, so the two never race.
      this.refreshProject(event.projectId);
    }
  }

  /**
   * Applies every clone-settled event held while its project was not loaded (#729),
   * once a reload has landed. Events for projects the reload still does not carry
   * stay held for the next one; a later projectDeleted event drops them for good.
   */
  private applyPendingStatusEvents(): void {
    for (const event of Array.from(this.pendingStatus.values())) {
      if (this.sections.some((s) => s.project.id === event.projectId)) {
        this.applyProjectStatusEvent(event);
      }
    }
  }

  /**
   * A project was deleted (#721, absorbed from #720): drop its section so an
   * out-of-band deletion (another tab, the API, a cascade-deleted account) clears the
   * row without waiting on some other reload to notice it is gone. A project not
   * loaded here is a no-op.
   */
  private applyProjectDeletedEvent(event: ProjectDeletedEvent): void {
    this.pendingStatus.delete(event.projectId);
    this.sections = this.sections.filter((s) => s.project.id !== event.projectId);
    this.trackCloneProgress();
  }

  /**
   * The sidenav's own wording for a project whose GitHub fetch is failing (#619):
   * the failure text, plus how long ago the last successful refresh was so the
   * reader knows how old the tree they are looking at is. Null when not failing.
   */
  githubErrorFor(section: Section): string | null {
    if (!section.github.failing) {
      return null;
    }
    const failure = section.github.failure ?? 'GitHub is unavailable';
    const since =
      section.github.lastSuccessAt === null
        ? 'never refreshed successfully'
        : `last refreshed ${formatAgo(section.github.lastSuccessAt, Date.now())}`;
    return `${failure} — ${since}`;
  }

  /** Recomputes which issues have an open agent session (#108), across every loaded project. */
  private refreshAgentSessionIndicators(): void {
    if (this.sections.length === 0) {
      this.openAgentSessionIssues = new Set();
      this.openAgentSessionProjects = new Set();
      return;
    }
    forkJoin(
      this.sections.map((section) =>
        this.agentSessionsService.list(section.project.id).pipe(map((ids) => ({ projectId: section.project.id, ids }))),
      ),
    ).subscribe((results) => {
      const issues = new Set<string>();
      const projects = new Set<number>();
      for (const { projectId, ids } of results) {
        for (const id of ids) {
          const issueNumber = issueNumberFromSessionId(id);
          if (issueNumber !== null) {
            issues.add(`${projectId}:${issueNumber}`);
          } else if (isProjectAgentSessionId(id)) {
            projects.add(projectId);
          }
        }
      }
      this.openAgentSessionIssues = issues;
      this.openAgentSessionProjects = projects;
    });
  }

  hasOpenAgentSession(projectId: number, issueNumber: number): boolean {
    return this.openAgentSessionIssues.has(`${projectId}:${issueNumber}`);
  }

  // Backs the section header's per-project agent sessions button (#312). Tracks
  // project-level agent sessions exclusively (#330) -- it does not aggregate
  // issue-attached agent sessions under the project; each issue row's own dot already
  // covers those. A project-level session id ("<projectId>-console[-suffix]", the persisted shape kept under ADR-112) carries
  // no issue number, so it's tracked separately in openAgentSessionProjects rather than
  // openAgentSessionIssues.
  hasOpenAgentSessionForProject(projectId: number): boolean {
    return this.openAgentSessionProjects.has(projectId);
  }

  // Like hasOpenAgentSessionForProject above, tracks project-level agent sessions
  // exclusively (#450) -- an issue-attached agent session's wait shows on that issue row's
  // own dot, never here. Reads the shared store (#791) by session id, so with two
  // project agent sessions open, one going active does not clear a flag another
  // still-waiting agent session set; `projectIdFromProjectAgentSessionId` is null for an
  // issue-attached session id, which keeps those off the project row.
  hasAttentionWaitingForProject(projectId: number): boolean {
    for (const sessionId of this.attentionStore.waiting()) {
      if (projectIdFromProjectAgentSessionId(sessionId) === projectId) {
        return true;
      }
    }
    return false;
  }

  // Jumps straight to this project's agent session page (#312) -- the button that
  // triggers this only ever renders once hasOpenAgentSessionForProject is true.
  openProjectAgentSessions(projectId: number, event: Event): void {
    event.stopPropagation();
    // 'console' is the route path segment -- a compatibility surface kept under ADR-112.
    this.router.navigate(['/projects', projectId, 'console']);
  }

  /**
   * Whether any session attached to this issue is waiting for attention (#130): the
   * shared store (#791) holds session ids, and a session's "<projectId>:<issueNumber>"
   * key is parsed straight out of its id, the same placement the sidenav used when it
   * applied the events itself. A project-level agent session's id carries no issue number,
   * so it never matches here -- it shows on the project row (#450) instead.
   */
  hasAttentionWaiting(projectId: number, issueNumber: number): boolean {
    const key = `${projectId}:${issueNumber}`;
    for (const sessionId of this.attentionStore.waiting()) {
      if (projectIssueKeyFromSessionId(sessionId) === key) {
        return true;
      }
    }
    return false;
  }

  /**
   * Flashes a just-created project's row into view (#717): expands its section,
   * reloads so the row exists, then scrolls it into view and highlights it
   * briefly. Called by the host on every created project -- import and create
   * responses alike are CLONING, and a momentary highlight on a READY row is
   * harmless. `done`, when given, fires once the row is revealed -- or when the
   * reload failed, so a caller holding UI open on it (the import dialog) can
   * never be trapped by a reveal that will not complete.
   */
  revealProject(projectId: number, done?: () => void): void {
    if (this.projectSectionStore.isCollapsed(projectId)) {
      this.projectSectionStore.toggle(projectId);
    }
    this.pendingRevealId = projectId;
    this.pendingRevealDone = done ?? null;
    this.refresh();
    if (this.sections.some((s) => s.project.id === projectId)) {
      this.maybeReveal();
    }
  }

  /** Seconds since this project was first seen CLONING (#717); 0 when not cloning. */
  cloneElapsedSecFor(projectId: number): number {
    const since = this.cloneFirstSeen.get(projectId);
    if (since === undefined) {
      return 0;
    }
    return Math.max(0, Math.floor((Date.now() - since) / 1000));
  }

  /** Staged line for a cloning row (#717) -- same mapping as the dialog and agent session wait. */
  cloneStageHintFor(projectId: number): string {
    return cloneStageHint(this.cloneElapsedSecFor(projectId));
  }

  /**
   * Records first-seen CLONING timestamps, drops settled projects, and runs a 1s
   * tick while anything is still cloning so the elapsed counters move (#717).
   * The tick only wakes change detection -- the getters read the clock directly.
   */
  private trackCloneProgress(): void {
    const now = Date.now();
    for (const section of this.sections) {
      if (section.project.status === 'CLONING' && !this.cloneFirstSeen.has(section.project.id)) {
        this.cloneFirstSeen.set(section.project.id, now);
      }
    }
    for (const id of [...this.cloneFirstSeen.keys()]) {
      if (!this.sections.some((s) => s.project.id === id && s.project.status === 'CLONING')) {
        this.cloneFirstSeen.delete(id);
      }
    }
    if (this.cloneFirstSeen.size > 0 && this.tickTimer === null) {
      this.tickTimer = setInterval(() => {}, 1000);
    } else if (this.cloneFirstSeen.size === 0) {
      this.clearTick();
    }
  }

  private clearTick(): void {
    if (this.tickTimer !== null) {
      clearInterval(this.tickTimer);
      this.tickTimer = null;
    }
  }
  /** Completes a pending reveal once the reloaded list carries the project (#717). */
  private maybeReveal(): void {
    const id = this.pendingRevealId;
    if (id === null) {
      return;
    }
    if (!this.sections.some((s) => s.project.id === id)) {
      return;
    }
    this.pendingRevealId = null;
    this.revealedProjectId = id;
    setTimeout(() => {
      document.querySelector(`[data-project-id="${id}"]`)?.scrollIntoView({ block: 'nearest' });
    }, 0);
    this.clearReveal();
    this.revealTimer = setTimeout(() => {
      if (this.revealedProjectId === id) {
        this.revealedProjectId = null;
      }
    }, 3000);
    this.takeRevealDone()?.();
  }

  /**
   * Releases a pending reveal without revealing -- the reload failed, so the row
   * may never arrive; the waiter still gets its `done` rather than hanging (#717).
   */
  private dropPendingReveal(): void {
    if (this.pendingRevealId !== null) {
      this.pendingRevealId = null;
      this.takeRevealDone()?.();
    }
  }

  private takeRevealDone(): (() => void) | null {
    const done = this.pendingRevealDone;
    this.pendingRevealDone = null;
    return done;
  }

  private clearReveal(): void {
    if (this.revealTimer !== null) {
      clearTimeout(this.revealTimer);
      this.revealTimer = null;
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
        this.hasOpenAgentSession(section.project.id, n.number),
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
      this.hasOpenAgentSession(section.project.id, n.number),
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

  /**
   * Moves DOM focus onto the currently-selected row, once it exists (#747). Deferred a
   * tick -- like `maybeReveal`'s scroll -- since a binding that just arrived hasn't
   * necessarily been rendered into the DOM yet by the time this runs.
   */
  private focusSelectedRow(): void {
    const target = this._selected;
    if (target === null) {
      return;
    }
    setTimeout(() => {
      if (
        this._selected === null ||
        this._selected.projectId !== target.projectId ||
        this._selected.issueNumber !== target.issueNumber
      ) {
        return; // selection moved on before this ran
      }
      this.rowElement(target.projectId, target.issueNumber)?.focus();
    });
  }

  private rowElement(projectId: number, issueNumber: number): HTMLElement | null {
    return document.querySelector<HTMLElement>(
      `a.row[data-project-id="${projectId}"][data-issue-number="${issueNumber}"]`,
    );
  }

  /**
   * Arrow-key navigation between sidenav rows (#747): moves focus to the next/previous
   * row in DOM order -- which already reflects render order (expand/collapse, the text
   * filter, the pinned section, project-section boundaries included), since a row not
   * currently visible is simply not in the DOM -- then clicks it to drive the same
   * navigation its routerLink would. Bound per-row, so this never fires from anywhere
   * else in the app (the filter `<input>` included).
   */
  onRowKeydown(event: KeyboardEvent): void {
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') {
      return;
    }
    event.preventDefault();
    const direction = event.key === 'ArrowDown' ? 1 : -1;
    const rows = Array.from(document.querySelectorAll<HTMLElement>('a.row'));
    const index = rows.indexOf(event.currentTarget as HTMLElement);
    const next = rows[index + direction];
    if (next === undefined) {
      return;
    }
    next.focus();
    next.click();
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
