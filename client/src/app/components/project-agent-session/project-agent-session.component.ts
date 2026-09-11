import { Component, Input, OnChanges, OnDestroy, OnInit, SimpleChanges, inject } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Subscription, filter, map, merge } from 'rxjs';
import { Agent, AgentStore } from '../../services/agent-store';
import { DefaultAgentStore } from '../../services/default-agent-store';
import { AgentSessionsService } from '../../services/agent-sessions.service';
import { EventsService, ProjectStatusEvent, isProjectStatusEvent } from '../../services/events.service';
import { IssuesService } from '../../services/issues.service';
import { OpenProjectAgentSession, ProjectAgentSessionService } from '../../services/project-agent-session.service';
import { LastAgentSessionStore } from '../../services/last-agent-session-store';
import {
  AgentSessionTabsComponent,
  OpenAgentSessionRequest,
  RenameAgentSessionRequest,
} from '../agent-session-tabs/agent-session-tabs.component';
import { AgentSessionTab, labelProjectAgentSessions, labelShellTabs } from '../agent-session-tabs/agent-session-labels';
import { TerminalComponent } from '../terminal/terminal.component';
import { Project } from '../../models/issue.model';
import { ProjectsService } from '../../services/projects.service';
import { ShellsService } from '../../services/shells.service';
import { cloneStageHint } from '../clone-progress';

// One open shell's client-side state (#876). `dir` is the main checkout the shell
// runs in; `name` is the name the user gave the tab, or null for the auto label.
interface OpenShellTab {
  id: string;
  dir: string;
  name: string | null;
}
// One open agent session's client-side state. `dir` comes from the engine either way;
// `agent` is only known when this browser launched the session (AgentStore).
// `resume` is the past conversation this agent session was opened to resume (#372),
// null for an ordinary new one.
interface OpenAgentSession {
  id: string;
  dir: string;
  agent: Agent | null;
  resume: string | null;
  /** The name the user gave this tab (#393), or null for the auto-generated label. */
  name: string | null;
  /** 'template' for the one seeded agent session of a templated project (#537), null otherwise. */
  seed: string | null;
}

// The project-level agent session page (#140, part of #138): lets a user start a
// Claude/Codex/shell conversation -- where the /t-open skill and `gh` are available
// -- before any issue exists, so an agent can open one. Since #314 each agent session runs
// in its own fresh git worktree rather than sharing one checkout; `dir` below is
// whatever directory the engine reports for that session, opaque to this component.
// Since #177 a project can have several agent sessions open at once, so this
// page shows the same tab strip an issue's agent sessions get (#178) -- minus the
// Overview tab and the main/worktree choice, which only make sense for an issue.
// Since #752 the project's own past agent session conversations -- #372's capability, once
// listed here behind a "past sessions" disclosure -- live only on the project page
// (ProjectSummaryComponent), which reaches this page by navigating here with the
// freshly reopened session named in `?session=`, the same handoff `?session=` already
// carries for "Open agent" (#221); `?resume=`/`?tool=` ride alongside it, read once
// below, since the very first WebSocket attach -- which happens here, never on the
// project page -- is what actually launches the tool's own resume command
// (ProjectAgentSessionController#reopenSession). Since #795 `?dir=` rides along too: the
// engine lists a session as open only once something has attached to it, so a
// freshly minted one is never in this page's open-agent-session list, and the page adds
// the tab itself from the handoff -- the way the issue page's own reopen does --
// rather than dropping it (see loadAgentSessions).
// Since #537 the page first looks the project up: while it is still CLONING (the
// add-project popup navigates here the moment a create succeeds) it waits, updating
// off the engine's `projectStatus` broadcast once the clone settles (#721 -- no more
// re-reading every few seconds), instead of asking for an agent session the engine would
// refuse; once READY, a project created from a template whose seeded agent session has not
// been launched yet gets one opened here, without a click, attached with
// `seed=template` so the engine starts the default agent on its own first prompt --
// once per page instance, and never again once the engine has recorded the launch.
@Component({
  selector: 'app-project-agent-session',
  standalone: true,
  imports: [AgentSessionTabsComponent, TerminalComponent],
  templateUrl: './project-agent-session.component.html',
  styleUrl: './project-agent-session.component.css',
})
export class ProjectAgentSessionComponent implements OnInit, OnChanges, OnDestroy {
  private readonly service = inject(ProjectAgentSessionService);
  private readonly projectsService = inject(ProjectsService);
  private readonly agentSessionsService = inject(AgentSessionsService);
  private readonly eventsService = inject(EventsService);
  private readonly issuesService = inject(IssuesService);
  private readonly agentStore = inject(AgentStore);
  readonly defaultAgentStore = inject(DefaultAgentStore);
  private readonly lastAgentSessionStore = inject(LastAgentSessionStore);
  private readonly shellsService = inject(ShellsService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  @Input({ required: true }) projectId!: number;

  loading = true;
  agentSessions: OpenAgentSession[] = [];
  /** This project's open main-checkout shells (#876), alongside the agent sessions above. */
  shells: OpenShellTab[] = [];
  tabs: AgentSessionTab[] = [];
  selected: string | null = null;
  starting = false;
  startError = false;
  closeError = false;
  renameError = false;
  revealError = false;
  /** A shell mint or close failure message (#876), null when quiet. */
  shellError: string | null = null;

  /** The project as last read (#537); null until the first read, or when it is not in the caller's list. */
  project: Project | null = null;
  /** True while the project is still CLONING (#537) -- no agent session is asked for until it is READY. */
  cloning = false;
  /** True when the project's creation FAILED (#537) -- nothing is started; retry lives on the project page. */
  failed = false;
  // Live wait progress (#717): when the CLONING wait started, for the elapsed
  // counter and staged hint. The 1s tick only wakes change detection -- the
  // getters read the clock directly.
  private cloneStartedAt: number | null = null;
  private cloneTickTimer: ReturnType<typeof setInterval> | null = null;
  // The project id whose seeded agent session this page instance has already opened (#537):
  // the guard against opening a second one before the engine's own record lands.
  private seededFor: number | null = null;
  private queryParamsSub: Subscription | null = null;
  // A shell opened or closed anywhere reaches this page as a local notify (this
  // page's own open/close) or as `consolesChanged` over the events channel (#876,
  // folded into onOpened/onClosed the same way the worktree list's shell list
  // follows it) -- either way the shell tabs re-read rather than trusting the
  // local add/remove alone.
  private shellsSub: Subscription | null = null;
  // Updates the CLONING wait off the engine's `projectStatus` broadcast (#721) instead
  // of re-polling; a reconnect does one full reload, since an event missed while the
  // socket was down is gone for good -- the same pattern the sidenav's own eventsSub
  // uses.
  private readonly eventsSub: Subscription;
  // Set when a `?new` request arrives while the open-agent-session list is still in
  // flight (#370): the start has to wait for that list, or the list's response
  // would land on top of the agent session it just added.
  private pendingNewAgentSession = false;

  constructor() {
    this.eventsSub = merge(
      this.eventsService.events$.pipe(
        filter(isProjectStatusEvent),
        map((event) => () => this.applyProjectStatusEvent(event)),
      ),
      this.eventsService.reconnected$.pipe(map(() => () => this.load(this.projectId))),
    ).subscribe((run) => run());
  }

  // The sidenav's "+" (#180) hands off with `?new` rather than minting a session
  // itself (#370): a session the engine has never attached is missing from
  // listOpen, so a `?session=<freshId>` handoff always fell through to some other
  // agent session and left the new one's worktree stranded on disk. Minting lives here
  // instead, where the session goes straight into the tab strip. Reading it off
  // the live query params (not just the snapshot) is what makes a "+" click work
  // while this page is already showing -- the projectId input does not change, so
  // ngOnChanges never fires.
  ngOnInit(): void {
    // #698: the tab strip's "+" reads `defaultAgentStore.agent()` directly, so its
    // fallback to the first installed agent needs this store's fetch already under
    // way here too, the same as #695's fix to project-summary's "Open agent".
    this.defaultAgentStore.refreshInstalled();
    this.queryParamsSub = this.route.queryParamMap.subscribe((params) => {
      if (params.get('new') === null) {
        return;
      }
      // Drop the flag before starting, so a reload -- or the next "+" click --
      // is a fresh request rather than a repeat of this one.
      this.clearNewParam();
      if (this.starting) {
        // The double-click guard, which used to live on the sidenav button: a
        // further "+" while one agent session is being opened is ignored, and a failed
        // start clears `starting` again, re-arming it.
        return;
      }
      // #439: a "+" click for a *different* project changes this navigation's query
      // param and its route projectId together, but the projectId input only catches
      // up once NavigationEnd fires -- later than this subscription, which sees the
      // query param during route activation. Reading the target off the route
      // directly (rather than trusting `this.projectId`, which can still name the
      // previously-viewed project at this point) is what tells the two cases apart;
      // a target this early read can't resolve (no route param configured, as in a
      // component test with no such route) falls back to the loading-only check,
      // same as before this fix.
      const target = this.targetProjectId();
      if ((target !== null && target !== this.projectId) || this.loading) {
        this.pendingNewAgentSession = true;
        return;
      }
      this.startDefault();
    });
    this.shellsSub = merge(this.agentSessionsService.onOpened, this.agentSessionsService.onClosed).subscribe(() =>
      this.reloadShells(),
    );
  }

  // The project id the route names right now (#439), read straight off the root
  // route's snapshot the same way CurrentProjectService derives its own NavigationEnd-
  // gated `projectId` signal -- but synchronously, from wherever the route tree
  // already stands at the moment this is called, since that's what settles first (see
  // ngOnInit above). `null` when the active route carries no such param, e.g. a test
  // that never configured one.
  private targetProjectId(): number | null {
    const raw = this.route.snapshot.firstChild?.paramMap.get('projectId') ?? null;
    const id = raw !== null ? Number(raw) : NaN;
    return Number.isFinite(id) ? id : null;
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['projectId']) {
      this.load(this.projectId);
    }
  }

  /** Rewrites the URL without `new`, keeping every other param (`focus`, `session`). */
  private clearNewParam(): void {
    this.dropQueryParams('new');
  }

  /** Rewrites the URL without the named params, keeping every other one. */
  private dropQueryParams(...names: string[]): void {
    const queryParams = { ...this.route.snapshot.queryParams };
    for (const name of names) {
      delete queryParams[name];
    }
    // #439: build the URL from the route's own (possibly just-updated) project id, not
    // `this.projectId` -- during the race this method exists to help resolve, the
    // input still names the previously-viewed project, and navigating there would
    // snap the URL back to it out from under the navigation already landing on the
    // newly-clicked one. Falls back to the input when the route carries no such param
    // (e.g. a component test with none configured), same as before this fix.
    const projectId = this.targetProjectId() ?? this.projectId;
    // 'console' is the route path segment -- a compatibility surface kept under ADR-112.
    this.router.navigate(['/projects', projectId, 'console'], { queryParams, replaceUrl: true });
  }

  // Leaving this page -- however the navigation happened -- never closes the
  // sessions (they keep running server-side for the next reattach, same as an
  // issue's own agent sessions); what it does need is telling the sidenav its cached
  // view of this project's issue list may be stale, since the agent may have just
  // opened one via `gh` before the engine's own 30s poll would notice (#140).
  ngOnDestroy(): void {
    this.queryParamsSub?.unsubscribe();
    this.shellsSub?.unsubscribe();
    this.eventsSub.unsubscribe();
    this.stopCloneTick();
    this.issuesService.notifyProjectStale(this.projectId);
  }

  // The project first (#537): its status decides whether an agent session may be asked for
  // at all, and whether the one being opened is the template's seeded agent session. A
  // project the list does not carry (or a failed read) falls through to the
  // pre-#537 behaviour, so nothing this page did before depends on the lookup.
  private load(projectId: number): void {
    this.loading = true;
    this.cloning = false;
    this.failed = false;
    this.project = null;
    this.stopCloneTick();
    this.projectsService.list().subscribe({
      next: (projects) => {
        const project = projects.find((p) => p.id === projectId) ?? null;
        this.project = project;
        if (project?.status === 'CLONING') {
          this.loading = false;
          this.cloning = true;
          if (this.cloneStartedAt === null) {
            this.cloneStartedAt = Date.now();
          }
          this.startCloneTick();
          return;
        }
        this.cloneStartedAt = null;
        if (project?.status === 'FAILED') {
          this.loading = false;
          this.failed = true;
          return;
        }
        this.loadAgentSessions(projectId);
      },
      error: () => {
        this.cloneStartedAt = null;
        this.loadAgentSessions(projectId);
      },
    });
  }

  /**
   * The project's clone reached READY or FAILED (#721): update the waiting state in
   * place off the engine's broadcast -- what replaces the 3s re-read that used to poll
   * for this. Ignored for any project other than the one this page is currently
   * showing (a stale event for a project this page navigated away from, or one it
   * never loaded).
   */
  private applyProjectStatusEvent(event: ProjectStatusEvent): void {
    if (this.project === null || this.project.id !== event.projectId) {
      return;
    }
    this.stopCloneTick();
    this.cloneStartedAt = null;
    this.project = {
      ...this.project,
      status: event.status,
      defaultBranch: event.defaultBranch ?? this.project.defaultBranch,
    };
    if (event.status === 'FAILED') {
      this.cloning = false;
      this.failed = true;
      return;
    }
    this.cloning = false;
    this.failed = false;
    this.loadAgentSessions(event.projectId);
  }

  /** Seconds since the CLONING wait started (#717); 0 when not waiting. */
  get cloneElapsedSec(): number {
    if (this.cloneStartedAt === null) {
      return 0;
    }
    return Math.max(0, Math.floor((Date.now() - this.cloneStartedAt) / 1000));
  }

  /** Staged line for the CLONING wait (#717) -- same mapping as the dialog and sidenav row. */
  get cloneStage(): string {
    return cloneStageHint(this.cloneElapsedSec);
  }

  private startCloneTick(): void {
    if (this.cloneTickTimer === null) {
      this.cloneTickTimer = setInterval(() => {}, 1000);
    }
  }

  private stopCloneTick(): void {
    if (this.cloneTickTimer !== null) {
      clearInterval(this.cloneTickTimer);
      this.cloneTickTimer = null;
    }
  }

  /** Whether this render owes the project its one seeded agent session (#537). */
  private owesSeededAgentSession(projectId: number): boolean {
    const project = this.project;
    return (
      project !== null &&
      project.id === projectId &&
      project.status === 'READY' &&
      project.template !== null &&
      (project.templateSeededAt ?? null) === null &&
      this.seededFor !== projectId
    );
  }

  private loadAgentSessions(projectId: number): void {
    this.loading = true;
    this.agentSessions = [];
    this.shells = [];
    this.tabs = [];
    this.selected = null;
    this.starting = false;
    this.startError = false;
    this.closeError = false;
    this.renameError = false;
    this.revealError = false;
    this.shellError = null;
    // `pendingNewAgentSession` deliberately survives this reset: a "+" click for
    // another project changes the projectId input and the query params in the
    // same navigation, in no guaranteed order (#370).
    this.service.listOpen(projectId).subscribe({
      next: (sessions) => {
        this.loading = false;
        // The project page's reopen (#752) hands off `?session=<id>` alongside
        // `?resume=<id>&tool=<tool>` for that one session -- read once here, since
        // this mapping feeds the very first `<app-terminal>` this session mounts,
        // whose first WebSocket attach is what actually launches the tool's resume
        // command (ProjectAgentSessionController#reopenSession). An ordinary `?session=`
        // handoff (e.g. "Open agent") carries no `?resume=`, so every other
        // session keeps mapping exactly as before.
        const requestedSession = this.route.snapshot.queryParamMap.get('session');
        const requestedResume = this.route.snapshot.queryParamMap.get('resume');
        const requestedTool = this.route.snapshot.queryParamMap.get('tool');
        const requestedDir = this.route.snapshot.queryParamMap.get('dir');
        this.agentSessions = sessions.map((s) => {
          const isRequested = s.sessionId === requestedSession;
          return {
            id: s.sessionId,
            dir: s.workingDirectory,
            agent: (isRequested ? requestedTool : null) ?? this.agentStore.get(s.sessionId),
            resume: isRequested ? requestedResume : null,
            name: s.displayName ?? null,
            seed: null,
          };
        });
        // #795: a session the project page has just minted -- a reopened past
        // conversation, or "Open agent" with none open -- has never been attached,
        // so the engine does not list it yet (the same gap #370 closed for the
        // sidenav "+"). `?dir=` is what tells such a handoff from a stale `?session=`
        // (a closed tab, an old bookmark), which keeps falling through to the
        // most-recent fallback below: the directory has to travel anyway, since the
        // first attach resolves a never-attached session's working directory from the
        // client and has nothing recorded to fall back to. The tab is added here the
        // way the issue page's own reopen adds one to its list, with `resume`/`tool`
        // applied so its first attach resumes the conversation.
        const handedOff =
          requestedSession !== null &&
          requestedDir !== null &&
          !sessions.some((s) => s.sessionId === requestedSession);
        if (handedOff) {
          this.agentSessions = [
            ...this.agentSessions,
            {
              id: requestedSession,
              dir: requestedDir,
              agent: requestedTool ?? this.agentStore.get(requestedSession),
              resume: requestedResume,
              name: null,
              seed: null,
            },
          ];
        }
        if (requestedDir !== null || requestedResume !== null || requestedTool !== null) {
          // Consumed: a reload must not add the tab again (and re-run the resume
          // against an id the engine may since have ended), the same reason `?new`
          // is dropped once acted on (#370). `session` stays -- it is the ordinary
          // tab-activation handoff, and by then the engine lists the session itself.
          this.dropQueryParams('dir', 'resume', 'tool');
        }
        this.relabel();
        this.loadShells(projectId, () => this.decideInitialTab(projectId, sessions));
      },
      error: () => {
        this.loading = false;
        this.loadShells(projectId, () => {
          if (this.takePendingNewAgentSession()) {
            // The list failed, but the "+" click still asked for an agent session: mint it
            // anyway rather than dropping the click (#370). A failed start shows the
            // page's own start error, as it does anywhere else here.
            this.startDefault();
          }
        });
      },
    });
  }

  /**
   * This project's open main-checkout shells (#876): the only shells this page
   * owns -- issue-worktree shells live on their issue's page. Only main-checkout
   * shells are kept; anything else the listing carries belongs elsewhere. Runs
   * `done` once the tabs are rebuilt, so the initial load can decide on the full
   * strip and live updates can just re-render.
   */
  private loadShells(projectId: number, done: () => void): void {
    this.shellsService.list().subscribe({
      next: (shells) => {
        this.shells = shells
          .filter((shell) => shell.projectId === projectId && shell.mainCheckout)
          .map((shell) => ({ id: shell.sessionId, dir: shell.workingDirectory, name: shell.displayName ?? null }));
        this.relabel();
        done();
      },
      error: () => {
        this.shells = [];
        this.relabel();
        done();
      },
    });
  }

  /** A shell opened or closed elsewhere (#876): re-read this page's shells and repair the selection. */
  private reloadShells(): void {
    if (this.loading || this.cloning || this.failed || this.project === null) {
      return;
    }
    const projectId = this.projectId;
    this.loadShells(projectId, () => {
      if (this.selected !== null && !this.tabs.some((tab) => tab.id === this.selected)) {
        this.selectAgentSession(this.tabs[0]?.id ?? null);
      }
    });
  }

  /**
   * Which tab a fresh load lands on: the seeded agent session, a queued `?new`
   * start, the empty-state auto-start, or the remembered/most-recent agent
   * session -- decided only once the shells are in, so a `?session=` handoff
   * naming a shell (#876) resolves instead of falling through to an agent.
   */
  private decideInitialTab(projectId: number, sessions: OpenProjectAgentSession[]): void {
    if (this.owesSeededAgentSession(projectId)) {
      // #537: the template's one seeded agent session, alongside whatever is already
      // open. Marked before the request so a slow answer cannot open two.
      this.seededFor = projectId;
      this.start(this.defaultAgentStore.agent(), 'template');
      return;
    }
    if (this.takePendingNewAgentSession()) {
      // The sidenav's "+" (#370): the project's existing agent sessions stay in the
      // strip, with the brand-new one added alongside them and selected.
      this.startDefault();
      return;
    }
    if (this.agentSessions.length === 0 && this.shells.length === 0) {
      // #256: landing here with nothing open starts one immediately, using
      // the same default-agent source the sidenav "+" uses -- no picker, no
      // separate start button. (A handed-off session counts as open here --
      // #795 -- or "Open agent" on a project with none would start a second
      // one and strand the first's worktree.) Open shells count as open too
      // (#876): landing on shells alone shows them rather than minting an
      // agent nobody asked for.
      this.startDefault();
      return;
    }
    // The agent sessions page (#179) hands off with ?session=<id> naming the tab
    // to activate -- an agent or, since #876, one of this page's shells (e.g. the
    // project page's "Open shells" button); otherwise reattach where the user left
    // off. That is the tab this browser last selected on this project
    // (LastAgentSessionStore, #221) when it is still open -- not the engine's most
    // recently attached agent session: unselected tabs stay attached, merely hidden,
    // so a tab switch never moves lastAttachedAt, and on re-entry every agent
    // session reattaches at once, making the engine's winner whichever socket
    // connected last (#810). The most recently attached agent session -- what this
    // page showed before it had tabs -- is the fallback when nothing usable is
    // remembered. (Routing is component-less, so the query param is read off the
    // root route.)
    const requestedSession = this.route.snapshot.queryParamMap.get('session');
    const isOpen = (id: string | null): id is string =>
      id !== null && (this.agentSessions.some((c) => c.id === id) || this.shells.some((s) => s.id === id));
    const remembered = this.lastAgentSessionStore.get(projectId);
    this.selectAgentSession(
      isOpen(requestedSession)
        ? requestedSession
        : isOpen(remembered)
          ? remembered
          : sessions.reduce(
              (latest: OpenProjectAgentSession | null, s) =>
                !latest || Date.parse(s.lastAttachedAt) > Date.parse(latest.lastAttachedAt) ? s : latest,
              null,
            )?.sessionId ?? this.shells[0]?.id ?? null,
    );
  }

  /** Consumes a queued `?new` request, if one is waiting on the open-agent-session list. */
  private takePendingNewAgentSession(): boolean {
    const pending = this.pendingNewAgentSession;
    this.pendingNewAgentSession = false;
    return pending;
  }

  /** Retries the empty-state auto-start after a failure -- the only "start" affordance left. */
  retryStart(): void {
    this.startDefault();
  }

  private startDefault(): void {
    this.start(this.defaultAgentStore.agent());
  }

  /** The tab strip's "+" agent entries (the location choice is hidden -- only the agent matters). */
  openFromTabs(request: OpenAgentSessionRequest): void {
    this.start(request.agent);
  }

  /** The tab strip's "+" Shell entry (#876): mints a shell at the main checkout. */
  openShellFromTabs(): void {
    if (!this.project || this.starting) {
      return;
    }
    this.starting = true;
    this.shellError = null;
    this.shellsService.open(this.projectId, null, this.project.workareaPath).subscribe({
      next: (created) => {
        this.shells = [...this.shells, { id: created.sessionId, dir: created.workingDirectory, name: null }];
        this.relabel();
        this.selectAgentSession(created.sessionId);
        this.starting = false;
        this.agentSessionsService.notifyOpened();
      },
      error: () => {
        this.starting = false;
        this.shellError = 'could not open a shell — try again';
      },
    });
  }

  /** The tab strip's own selection change -- also the entry points' recency signal (#221). */
  selectTab(id: string): void {
    this.selectAgentSession(id);
  }

  // Recorded in {@link LastAgentSessionStore} so the sidenav "+" and the project
  // summary's agent session button can jump back into the agent session the user was last
  // looking at, rather than always landing on the server's most-recently-attached
  // one (#221). Agent sessions only (#876): a shell tab is selected plainly, never
  // remembered as the agent to jump back into.
  private selectAgentSession(id: string | null): void {
    this.selected = id;
    if (id && this.agentSessions.some((c) => c.id === id)) {
      this.lastAgentSessionStore.set(this.projectId, id);
    }
  }

  private start(agent: Agent, seed: string | null = null): void {
    this.starting = true;
    this.startError = false;
    this.service.start(this.projectId).subscribe({
      next: (session) => {
        this.agentStore.set(session.sessionId, agent);
        this.agentSessions = [
          ...this.agentSessions,
          { id: session.sessionId, dir: session.workingDirectory, agent, resume: null, name: null, seed },
        ];
        this.relabel();
        this.selectAgentSession(session.sessionId);
        this.starting = false;
        this.agentSessionsService.notifyOpened();
      },
      error: () => {
        this.starting = false;
        this.startError = true;
      },
    });
  }

  /**
   * Renames a tab (#393). The new name is shown immediately and then written to the
   * engine; a failed write puts the previous name back, so the strip never keeps
   * showing a name the server does not have. Each change of what the tab shows --
   * the optimistic update and the error-path revert alike -- is announced via
   * notifyRenamed() so the header agent sessions widget re-reads its rows (#456).
   */
  renameAgentSession(request: RenameAgentSessionRequest): void {
    const target = this.agentSessions.find((c) => c.id === request.id);
    if (!target) {
      return;
    }
    const name = request.name.trim();
    const previous = target.name;
    if ((previous ?? '') === name) {
      return;
    }
    this.renameError = false;
    this.agentSessions = this.agentSessions.map((c) => (c.id === request.id ? { ...c, name: name || null } : c));
    this.relabel();
    this.agentSessionsService.notifyRenamed();
    this.service.rename(this.projectId, request.id, name).subscribe({
      error: () => {
        this.agentSessions = this.agentSessions.map((c) => (c.id === request.id ? { ...c, name: previous } : c));
        this.relabel();
        this.renameError = true;
        this.agentSessionsService.notifyRenamed();
      },
    });
  }

  /** The tab strip's close, for either kind of tab (#876). */
  closeTab(id: string): void {
    if (this.shells.some((s) => s.id === id)) {
      this.closeShell(id);
    } else {
      this.closeAgentSession(id);
    }
  }

  closeAgentSession(id: string): void {
    this.closeError = false;
    this.service.close(this.projectId, id).subscribe({
      next: () => {
        this.agentSessions = this.agentSessions.filter((c) => c.id !== id);
        this.relabel();
        if (this.selected === id) {
          this.selectAgentSession(this.tabs[0]?.id ?? null);
        }
        this.agentSessionsService.notifyClosed();
        if (this.tabs.length === 0) {
          // #265: closing the last tab leaves the agent session view rather than
          // auto-starting a new one -- back to the project page, where the "+"
          // affordance lives if they want another later. Landing here directly
          // with zero sessions is unaffected (#256's load()-time auto-start).
          // Since #876 open shells count too: shells alone keep the page alive.
          this.back();
        }
      },
      error: () => {
        this.closeError = true;
      },
    });
  }

  /** Ends one shell tab for good (#876): kills the process, drops the tab, stays put while tabs remain. */
  closeShell(id: string): void {
    this.shellError = null;
    this.shellsService.close(this.projectId, id).subscribe({
      next: () => {
        this.shells = this.shells.filter((s) => s.id !== id);
        this.relabel();
        if (this.selected === id) {
          this.selectAgentSession(this.tabs[0]?.id ?? null);
        }
        this.agentSessionsService.notifyClosed();
        if (this.tabs.length === 0) {
          this.back();
        }
      },
      error: () => {
        this.shellError = 'could not close that shell — try again';
      },
    });
  }

  revealAgentSession(id: string): void {
    this.revealError = false;
    this.agentSessionsService.reveal(this.projectId, id).subscribe({
      error: () => {
        this.revealError = true;
      },
    });
  }

  back(): void {
    this.router.navigate(['/projects', this.projectId, 'issues']);
  }

  // Every agent session here runs in the project's own checkout, so the issue pages'
  // main/wtree labelling (agent-session-labels.ts) carries no information -- tabs are
  // just "agent", "agent 2", ..., plus the agent when known, via the shared
  // labelProjectAgentSessions() (#449) the header agent sessions widget also calls.
  // Shell tabs follow with their own numbering -- "shell", "shell 2", ... -- via
  // labelShellTabs(), agents first so the agent numbering never shifts as shells
  // come and go (#876).
  private relabel(): void {
    this.tabs = [...labelProjectAgentSessions(this.agentSessions), ...labelShellTabs(this.shells)];
  }
}
