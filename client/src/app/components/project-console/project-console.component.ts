import { Component, Input, OnChanges, OnDestroy, OnInit, SimpleChanges, inject } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { Agent, AgentStore } from '../../services/agent-store';
import { DefaultAgentStore } from '../../services/default-agent-store';
import { ConsolesService } from '../../services/consoles.service';
import { IssuesService } from '../../services/issues.service';
import { OpenProjectConsole, ProjectConsoleService } from '../../services/project-console.service';
import { LastConsoleStore } from '../../services/last-console-store';
import { cloneStageHint, elapsedSeconds } from '../add-project-popup/clone-progress';
import {
  ConsoleTabsComponent,
  OpenConsoleRequest,
  RenameConsoleRequest,
} from '../console-tabs/console-tabs.component';
import { ConsoleTab, labelProjectConsoles } from '../console-tabs/console-labels';
import { SessionListComponent } from '../session-list/session-list.component';
import { TerminalComponent } from '../terminal/terminal.component';
import { Project, ResumeSession } from '../../models/issue.model';
import { ProjectsService } from '../../services/projects.service';

// How often to re-read the project while it is still CLONING (#537) -- the same
// cadence as the sidenav's own cloning poll, run here too because nothing shares the
// sidenav's list with this page.
const CLONE_POLL_MS = 3000;

/** How often the cloning wait's elapsed counter ticks forward (#717) -- a UI-only
 * redraw, independent of the network poll above. */
const CLONE_TICK_MS = 1000;

// One open console's client-side state. `dir` comes from the engine either way;
// `agent` is only known when this browser launched the session (AgentStore).
// `resume` is the past conversation this console was opened to resume (#372),
// null for an ordinary new one.
interface OpenConsole {
  id: string;
  dir: string;
  agent: Agent | null;
  resume: string | null;
  /** The name the user gave this tab (#393), or null for the auto-generated label. */
  name: string | null;
  /** 'template' for the one seeded console of a templated project (#537), null otherwise. */
  seed: string | null;
}

// The project-level console page (#140, part of #138): lets a user start a
// Claude/Codex/shell conversation -- where the /t-open skill and `gh` are available
// -- before any issue exists, so an agent can open one. Since #314 each console runs
// in its own fresh git worktree rather than sharing one checkout; `dir` below is
// whatever directory the engine reports for that session, opaque to this component.
// Since #177 a project can have several consoles open at once, so this
// page shows the same tab strip an issue's consoles get (#178) -- minus the
// Overview tab and the main/worktree choice, which only make sense for an issue.
// Since #372 it also lists the conversations that ran in this project's consoles
// and can reopen one -- the capability an issue's Overview tab has had since #103
// -- behind a disclosure under the header, since #256 means this page almost always
// opens straight into a live console, with no empty state to put the list in. The
// list is read when that disclosure is first opened rather than on mount, so simply
// landing on a console costs no extra request; the trade-off is that the collapsed
// label cannot carry a count.
// Since #537 the page first looks the project up: while it is still CLONING (the
// add-project popup navigates here the moment a create succeeds) it waits, re-reading
// every few seconds, instead of asking for a console the engine would refuse; once
// READY, a project created from a template whose seeded console has not been launched
// yet gets one opened here, without a click, attached with `seed=template` so the
// engine starts the default agent on its own first prompt -- once per page instance,
// and never again once the engine has recorded the launch.
@Component({
  selector: 'app-project-console',
  standalone: true,
  imports: [ConsoleTabsComponent, SessionListComponent, TerminalComponent],
  templateUrl: './project-console.component.html',
  styleUrl: './project-console.component.css',
})
export class ProjectConsoleComponent implements OnInit, OnChanges, OnDestroy {
  private readonly service = inject(ProjectConsoleService);
  private readonly projectsService = inject(ProjectsService);
  private readonly consolesService = inject(ConsolesService);
  private readonly issuesService = inject(IssuesService);
  private readonly agentStore = inject(AgentStore);
  readonly defaultAgentStore = inject(DefaultAgentStore);
  private readonly lastConsoleStore = inject(LastConsoleStore);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  @Input({ required: true }) projectId!: number;

  loading = true;
  consoles: OpenConsole[] = [];
  tabs: ConsoleTab[] = [];
  selected: string | null = null;
  starting = false;
  startError = false;
  closeError = false;
  renameError = false;
  revealError = false;
  /** Past conversations captured in this project's consoles (#372), newest first. */
  pastSessions: ResumeSession[] = [];
  pastOpen = false;
  pastLoading = false;
  /** Whether the list below is a real answer yet -- false until the first read returns. */
  pastLoaded = false;

  /** The project as last read (#537); null until the first read, or when it is not in the caller's list. */
  project: Project | null = null;
  /** True while the project is still CLONING (#537) -- no console is asked for until it is READY. */
  cloning = false;
  /** True when the project's creation FAILED (#537) -- nothing is started; retry lives on the project page. */
  failed = false;
  private clonePollTimer: ReturnType<typeof setTimeout> | null = null;
  // The cloning wait's elapsed counter (#717): ticked forward once a second while
  // `cloning` is true, independent of the network poll above.
  private now = Date.now();
  private cloneTickTimer: ReturnType<typeof setInterval> | null = null;
  // The project id whose seeded console this page instance has already opened (#537):
  // the guard against opening a second one before the engine's own record lands.
  private seededFor: number | null = null;

  private queryParamsSub: Subscription | null = null;
  // Set when a `?new` request arrives while the open-console list is still in
  // flight (#370): the start has to wait for that list, or the list's response
  // would land on top of the console it just added.
  private pendingNewConsole = false;

  // The sidenav's "+" (#180) hands off with `?new` rather than minting a session
  // itself (#370): a session the engine has never attached is missing from
  // listOpen, so a `?session=<freshId>` handoff always fell through to some other
  // console and left the new one's worktree stranded on disk. Minting lives here
  // instead, where the session goes straight into the tab strip. Reading it off
  // the live query params (not just the snapshot) is what makes a "+" click work
  // while this page is already showing -- the projectId input does not change, so
  // ngOnChanges never fires.
  ngOnInit(): void {
    // #698: the tab strip's "+" reads `defaultAgentStore.agent()` directly, so its
    // fallback to the first installed agent needs this store's fetch already under
    // way here too, the same as #695's fix to project-summary's "Open console".
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
        // further "+" while one console is being opened is ignored, and a failed
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
        this.pendingNewConsole = true;
        return;
      }
      this.startDefault();
    });
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
    const queryParams = { ...this.route.snapshot.queryParams };
    delete queryParams['new'];
    // #439: build the URL from the route's own (possibly just-updated) project id, not
    // `this.projectId` -- during the race this method exists to help resolve, the
    // input still names the previously-viewed project, and navigating there would
    // snap the URL back to it out from under the navigation already landing on the
    // newly-clicked one. Falls back to the input when the route carries no such param
    // (e.g. a component test with none configured), same as before this fix.
    const projectId = this.targetProjectId() ?? this.projectId;
    this.router.navigate(['/projects', projectId, 'console'], { queryParams, replaceUrl: true });
  }

  // Leaving this page -- however the navigation happened -- never closes the
  // sessions (they keep running server-side for the next reattach, same as an
  // issue's own consoles); what it does need is telling the sidenav its cached
  // view of this project's issue list may be stale, since the agent may have just
  // opened one via `gh` before the engine's own 30s poll would notice (#140).
  ngOnDestroy(): void {
    this.queryParamsSub?.unsubscribe();
    this.stopClonePoll();
    this.stopCloneTick();
    this.issuesService.notifyProjectStale(this.projectId);
  }

  /** The cloning wait's staged text and elapsed seconds (#717), derived from the
   * project's own `createdAt` -- estimated client text, not a real engine signal. */
  get cloningElapsedSeconds(): number {
    return this.project ? elapsedSeconds(Date.parse(this.project.createdAt), this.now) : 0;
  }

  get cloningStageHint(): string {
    return cloneStageHint(this.cloningElapsedSeconds);
  }

  // The project first (#537): its status decides whether a console may be asked for
  // at all, and whether the one being opened is the template's seeded console. A
  // project the list does not carry (or a failed read) falls through to the
  // pre-#537 behaviour, so nothing this page did before depends on the lookup.
  private load(projectId: number): void {
    this.loading = true;
    this.cloning = false;
    this.failed = false;
    this.project = null;
    this.stopClonePoll();
    this.stopCloneTick();
    this.projectsService.list().subscribe({
      next: (projects) => {
        const project = projects.find((p) => p.id === projectId) ?? null;
        this.project = project;
        if (project?.status === 'CLONING') {
          this.loading = false;
          this.cloning = true;
          this.now = Date.now();
          this.cloneTickTimer = setInterval(() => (this.now = Date.now()), CLONE_TICK_MS);
          this.clonePollTimer = setTimeout(() => {
            this.clonePollTimer = null;
            this.load(projectId);
          }, CLONE_POLL_MS);
          return;
        }
        if (project?.status === 'FAILED') {
          this.loading = false;
          this.failed = true;
          return;
        }
        this.loadConsoles(projectId);
      },
      error: () => this.loadConsoles(projectId),
    });
  }

  private stopClonePoll(): void {
    if (this.clonePollTimer !== null) {
      clearTimeout(this.clonePollTimer);
      this.clonePollTimer = null;
    }
  }

  private stopCloneTick(): void {
    if (this.cloneTickTimer !== null) {
      clearInterval(this.cloneTickTimer);
      this.cloneTickTimer = null;
    }
  }

  /** Whether this render owes the project its one seeded console (#537). */
  private owesSeededConsole(projectId: number): boolean {
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

  private loadConsoles(projectId: number): void {
    this.loading = true;
    this.consoles = [];
    this.tabs = [];
    this.selected = null;
    this.starting = false;
    this.startError = false;
    this.closeError = false;
    this.renameError = false;
    this.revealError = false;
    this.pastSessions = [];
    this.pastOpen = false;
    this.pastLoading = false;
    this.pastLoaded = false;
    // `pendingNewConsole` deliberately survives this reset: a "+" click for
    // another project changes the projectId input and the query params in the
    // same navigation, in no guaranteed order (#370).
    this.service.listOpen(projectId).subscribe({
      next: (sessions) => {
        this.loading = false;
        this.consoles = sessions.map((s) => ({
          id: s.sessionId,
          dir: s.workingDirectory,
          agent: this.agentStore.get(s.sessionId),
          resume: null,
          name: s.displayName ?? null,
          seed: null,
        }));
        this.relabel();
        if (this.owesSeededConsole(projectId)) {
          // #537: the template's one seeded console, alongside whatever is already
          // open. Marked before the request so a slow answer cannot open two.
          this.seededFor = projectId;
          this.start(this.defaultAgentStore.agent(), 'template');
          return;
        }
        if (this.takePendingNewConsole()) {
          // The sidenav's "+" (#370): the project's existing consoles stay in the
          // strip, with the brand-new one added alongside them and selected.
          this.startDefault();
          return;
        }
        if (sessions.length === 0) {
          // #256: landing here with nothing open starts one immediately, using
          // the same default-agent source the sidenav "+" uses -- no picker, no
          // separate start button.
          this.startDefault();
          return;
        }
        // The consoles page (#179) hands off with ?session=<id> naming the tab
        // to activate; otherwise reattach where the user left off -- the most
        // recently attached console, which is what this page showed before it
        // had tabs. (Routing is component-less, so the query param is read off
        // the root route.)
        const requested = this.route.snapshot.queryParamMap.get('session');
        this.selectConsole(
          requested && sessions.some((s) => s.sessionId === requested)
            ? requested
            : sessions.reduce(
                (latest: OpenProjectConsole | null, s) =>
                  !latest || Date.parse(s.lastAttachedAt) > Date.parse(latest.lastAttachedAt) ? s : latest,
                null,
              )?.sessionId ?? null,
        );
      },
      error: () => {
        this.loading = false;
        if (this.takePendingNewConsole()) {
          // The list failed, but the "+" click still asked for a console: mint it
          // anyway rather than dropping the click (#370). A failed start shows the
          // page's own start error, as it does anywhere else here.
          this.startDefault();
        }
      },
    });
  }

  /** Consumes a queued `?new` request, if one is waiting on the open-console list. */
  private takePendingNewConsole(): boolean {
    const pending = this.pendingNewConsole;
    this.pendingNewConsole = false;
    return pending;
  }

  // A conversation outlives the console it ran in (#101), so this list is read
  // independently of the open-console list; a failure leaves it simply empty rather
  // than blocking the page.
  private loadPastSessions(projectId: number): void {
    this.pastLoading = true;
    this.service.resumeSessions(projectId).subscribe({
      next: (sessions) => {
        this.pastSessions = sessions;
        this.pastLoading = false;
        this.pastLoaded = true;
      },
      error: () => {
        this.pastSessions = [];
        this.pastLoading = false;
        this.pastLoaded = true;
      },
    });
  }

  // Opening the disclosure is what asks for the list, and asks again every time:
  // the set grows whenever a console is closed, so a cached answer would go stale
  // exactly when the user is most likely to want it.
  togglePast(): void {
    this.pastOpen = !this.pastOpen;
    if (this.pastOpen) {
      this.loadPastSessions(this.projectId);
    }
  }

  /**
   * Reopens a past conversation (#372): the engine mints a brand-new session in the
   * original console's working directory, and the first attach launches the tool's
   * own resume command -- the same handoff `main-content.component.ts` makes for an
   * issue's conversations.
   */
  reopenSession(session: ResumeSession): void {
    this.starting = true;
    this.startError = false;
    this.service.reopenSession(this.projectId, session.worktreeId).subscribe({
      next: (started) => {
        this.agentStore.set(started.sessionId, session.tool);
        this.consoles = [
          ...this.consoles,
          {
            id: started.sessionId,
            dir: started.workingDirectory,
            agent: session.tool,
            resume: session.resumeId,
            name: null,
            seed: null,
          },
        ];
        this.relabel();
        this.selectConsole(started.sessionId);
        this.starting = false;
        this.pastOpen = false;
        this.consolesService.notifyOpened();
      },
      error: () => {
        this.starting = false;
        this.startError = true;
      },
    });
  }

  /** Retries the empty-state auto-start after a failure -- the only "start" affordance left. */
  retryStart(): void {
    this.startDefault();
  }

  private startDefault(): void {
    this.start(this.defaultAgentStore.agent());
  }

  /** The tab strip's "+" (the location choice is hidden -- only the agent matters). */
  openFromTabs(request: OpenConsoleRequest): void {
    this.start(request.agent);
  }

  /** The tab strip's own selection change -- also the entry points' recency signal (#221). */
  selectTab(id: string): void {
    this.selectConsole(id);
  }

  // Recorded in {@link LastConsoleStore} so the sidenav "+" and the project
  // summary's console button can jump back into the console the user was last
  // looking at, rather than always landing on the server's most-recently-attached
  // one (#221).
  private selectConsole(id: string | null): void {
    this.selected = id;
    if (id) {
      this.lastConsoleStore.set(this.projectId, id);
    }
  }

  private start(agent: Agent, seed: string | null = null): void {
    this.starting = true;
    this.startError = false;
    this.service.start(this.projectId).subscribe({
      next: (session) => {
        this.agentStore.set(session.sessionId, agent);
        this.consoles = [
          ...this.consoles,
          { id: session.sessionId, dir: session.workingDirectory, agent, resume: null, name: null, seed },
        ];
        this.relabel();
        this.selectConsole(session.sessionId);
        this.starting = false;
        this.consolesService.notifyOpened();
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
   * notifyRenamed() so the header consoles widget re-reads its rows (#456).
   */
  renameConsole(request: RenameConsoleRequest): void {
    const target = this.consoles.find((c) => c.id === request.id);
    if (!target) {
      return;
    }
    const name = request.name.trim();
    const previous = target.name;
    if ((previous ?? '') === name) {
      return;
    }
    this.renameError = false;
    this.consoles = this.consoles.map((c) => (c.id === request.id ? { ...c, name: name || null } : c));
    this.relabel();
    this.consolesService.notifyRenamed();
    this.service.rename(this.projectId, request.id, name).subscribe({
      error: () => {
        this.consoles = this.consoles.map((c) => (c.id === request.id ? { ...c, name: previous } : c));
        this.relabel();
        this.renameError = true;
        this.consolesService.notifyRenamed();
      },
    });
  }

  closeConsole(id: string): void {
    this.closeError = false;
    this.service.close(this.projectId, id).subscribe({
      next: () => {
        this.consoles = this.consoles.filter((c) => c.id !== id);
        this.relabel();
        if (this.selected === id) {
          this.selectConsole(this.consoles[0]?.id ?? null);
        }
        this.consolesService.notifyClosed();
        if (this.consoles.length === 0) {
          // #265: closing the last console leaves the console view rather than
          // auto-starting a new one -- back to the project page, where the "+"
          // affordance lives if they want another later. Landing here directly
          // with zero sessions is unaffected (#256's load()-time auto-start).
          this.back();
        }
      },
      error: () => {
        this.closeError = true;
      },
    });
  }

  revealConsole(id: string): void {
    this.revealError = false;
    this.consolesService.reveal(this.projectId, id).subscribe({
      error: () => {
        this.revealError = true;
      },
    });
  }

  back(): void {
    this.router.navigate(['/projects', this.projectId, 'issues']);
  }

  // Every console here runs in the project's own checkout, so the issue pages'
  // main/wtree labelling (console-labels.ts) carries no information -- tabs are
  // just "console", "console 2", ..., plus the agent when known, via the shared
  // labelProjectConsoles() (#449) the header consoles widget also calls.
  private relabel(): void {
    this.tabs = labelProjectConsoles(this.consoles);
  }
}
