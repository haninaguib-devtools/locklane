import { HttpErrorResponse } from '@angular/common/http';
import { Component, EventEmitter, Injector, Input, OnChanges, OnInit, Output, SimpleChanges, inject } from '@angular/core';
import { Router } from '@angular/router';
import { switchMap } from 'rxjs';
import { Project, ResumeSession, TreeNode } from '../../models/issue.model';
import { ConfirmDialogComponent } from '../confirm-dialog/confirm-dialog.component';
import { WorktreeListComponent } from '../worktree-list/worktree-list.component';
import { SessionListComponent } from '../session-list/session-list.component';
import { IssuesService } from '../../services/issues.service';
import { ProjectsService } from '../../services/projects.service';
import { OpenProjectAgentSession, ProjectAgentSessionService } from '../../services/project-agent-session.service';
import { AgentSessionsService } from '../../services/agent-sessions.service';
import { OpenShell, ShellsService } from '../../services/shells.service';
import { AgentStore } from '../../services/agent-store';
import { DefaultAgentStore } from '../../services/default-agent-store';
import { DefaultIdeStore, InstalledIde } from '../../services/default-ide-store';
import { LastAgentSessionStore } from '../../services/last-agent-session-store';
import { CurrentProjectService } from '../../services/current-project.service';

/** The issue counts shown on a project's summary, all derived from its tree (#85). */
export interface IssueCounts {
  total: number;
  open: number;
  closed: number;
  initiatives: number;
  tasks: number;
}

// The project's own page (#85), shown wherever an issue is not selected -- the slot
// that used to read "select an issue to begin". It re-fetches rather than reading the
// sidenav's sections: the sidenav owns those privately, and MainContentComponent sets
// the precedent that the main pane loads what it displays. No `GET /api/projects/{id}`
// exists (adding one was a non-goal of #85), so the project is picked out of the list.
@Component({
  selector: 'app-project-summary',
  standalone: true,
  imports: [ConfirmDialogComponent, WorktreeListComponent, SessionListComponent],
  templateUrl: './project-summary.component.html',
  styleUrl: './project-summary.component.css',
})
export class ProjectSummaryComponent implements OnChanges, OnInit {
  private readonly projectsService = inject(ProjectsService);
  private readonly issuesService = inject(IssuesService);
  private readonly projectAgentSessionService = inject(ProjectAgentSessionService);
  private readonly agentSessionsService = inject(AgentSessionsService);
  private readonly shellsService = inject(ShellsService);
  private readonly agentStore = inject(AgentStore);
  private readonly defaultAgentStore = inject(DefaultAgentStore);
  private readonly defaultIdeStore = inject(DefaultIdeStore);
  private readonly lastAgentSessionStore = inject(LastAgentSessionStore);
  private readonly router = inject(Router);
  private readonly injector = inject(Injector);

  // Lazy, like AppComponent's own `currentProject` getter: an eager field would
  // construct CurrentProjectService on every load of this page, not only the
  // rare one where the accent-color picker below actually needs it. In the
  // running app it is always already constructed by the time this component
  // exists (AppComponent's topbar reads its own `currentProject` unconditionally),
  // so `.refresh()` is always called explicitly here -- never skipped on the
  // assumption that obtaining the reference just now means it was *just*
  // constructed and so already fetched fresh on its own.
  private refreshCurrentProject(): void {
    this.injector.get(CurrentProjectService).refresh();
  }

  // The project's own accent-color picker (#428, a free picker plus Reset since #843)
  // submits the chosen hex straight to the backend (#427); unlike the global setting,
  // this is per-project state with no client-only representation -- Reset persists
  // NULL rather than falling back to a client-side default.
  savingAccentColor = false;
  accentColorError: string | null = null;

  @Input({ required: true }) projectId!: number;

  // Tells the app shell to drop this project from the sidenav (#249): the sidenav
  // owns its own project list privately (#44) and has no other way to learn that a
  // delete initiated from this page succeeded.
  @Output() projectDeleted = new EventEmitter<void>();

  project: Project | null = null;
  counts: IssueCounts | null = null;
  loading = true;
  error = false;

  // The delete-project button (#231): opens the app-styled confirm dialog rather than
  // deleting immediately, and surfaces the backend's refusal (open worktree/agent session)
  // inline rather than navigating away or failing silently.
  showDeleteConfirm = false;
  deleting = false;
  deleteError: string | null = null;

  // The project's open agent sessions (#221), fetched only once the project is known to
  // be READY -- a cloning or failed project has nowhere to run one. Drives the
  // agent session button's label and where it navigates.
  openAgentSessions: OpenProjectAgentSession[] = [];
  startingAgentSession = false;
  agentSessionError = false;

  // The project's open shells (#745, reusing #733's ShellsService), fetched
  // alongside the agent sessions above -- drives the "Open shells" button's choice
  // between focusing an existing shell and minting one at the main worktree first.
  openShells: OpenShell[] = [];
  startingShell = false;
  shellError = false;

  // The "Open IDE" button (#831): mints/reuses the project's main-checkout IDE
  // session, then opens it exactly like the per-tab "Open IDE" action does.
  startingIde = false;
  ideOpenFailed = false;

  // This project's past agent session conversations (#752), shown in an always-visible
  // column the same way an issue's Overview tab shows its own (overview-tab's
  // sessions-rail) -- unlike that tab, and unlike the project agent session page's old
  // disclosure, there is no live terminal here competing for the initial request,
  // so the list loads with the rest of the page rather than behind a toggle.
  pastSessions: ResumeSession[] = [];
  pastSessionsLoading = true;
  reopeningSession = false;
  reopenSessionError = false;

  // #695: "Open agent" launches with `defaultAgentStore.agent()` directly, so its
  // fallback to the first installed agent needs this store's fetch already under way
  // -- not only triggered from the settings dialog.
  ngOnInit(): void {
    this.defaultAgentStore.refreshInstalled();
    // #782's own rule: a browser that never chose an IDE in Settings acts on
    // code-server whatever is installed, so there is nothing to look up yet -- only a
    // browser with a stored choice needs the engine's installed set to know whether it
    // still holds (DefaultIdeStore.effective).
    if (this.defaultIdeStore.ide() !== '') {
      this.defaultIdeStore.refreshInstalled();
    }
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['projectId']) {
      this.load(this.projectId);
    }
  }

  openDeleteConfirm(): void {
    this.deleteError = null;
    this.showDeleteConfirm = true;
  }

  cancelDelete(): void {
    this.showDeleteConfirm = false;
  }

  confirmDelete(): void {
    this.showDeleteConfirm = false;
    this.deleting = true;
    this.deleteError = null;
    this.projectsService.delete(this.projectId).subscribe({
      next: () => {
        this.deleting = false;
        this.projectDeleted.emit();
        // The project this page was showing no longer exists -- back to the
        // workspace Overview (#197), the same place no project selected lands.
        this.router.navigate(['/']);
      },
      error: (err: HttpErrorResponse) => {
        this.deleting = false;
        this.deleteError = err.error?.error ?? 'could not delete this project';
      },
    });
  }

  /** Sets this project's accent color (#428) to the picked hex value. */
  chooseAccentColor(accentColor: string): void {
    this.setAccentColor(accentColor);
  }

  /** Clears this project's accent color (#843) back to no tint. */
  resetAccentColor(): void {
    this.setAccentColor(null);
  }

  private setAccentColor(accentColor: string | null): void {
    if (!this.project || this.savingAccentColor) {
      return;
    }
    this.savingAccentColor = true;
    this.accentColorError = null;
    this.projectsService.setAccentColor(this.project.id, accentColor).subscribe({
      next: () => {
        this.savingAccentColor = false;
        this.project = { ...this.project!, accentColor };
        // The tint AppComponent shows behind every page of this project (#428)
        // reads from CurrentProjectService's own cached list, which this page
        // never otherwise refreshes -- without this, the new color wouldn't
        // show until some unrelated navigation happened to re-fetch it.
        this.refreshCurrentProject();
      },
      error: () => {
        this.savingAccentColor = false;
        this.accentColorError = 'could not set the accent color';
      },
    });
  }

  private load(projectId: number): void {
    this.project = null;
    this.counts = null;
    this.loading = true;
    this.error = false;
    this.openAgentSessions = [];
    this.startingAgentSession = false;
    this.agentSessionError = false;
    this.openShells = [];
    this.startingShell = false;
    this.shellError = false;
    this.startingIde = false;
    this.ideOpenFailed = false;
    this.pastSessions = [];
    this.pastSessionsLoading = true;
    this.reopeningSession = false;
    this.reopenSessionError = false;
    this.savingAccentColor = false;
    this.accentColorError = null;

    this.projectsService.list().subscribe({
      next: (projects) => {
        this.project = projects.find((p) => p.id === projectId) ?? null;
        this.loading = false;
        this.error = this.project === null;
        if (this.project?.status === 'READY') {
          this.loadAgentSessions(projectId);
          this.loadShells(projectId);
          this.loadPastSessions(projectId);
        }
      },
      error: () => {
        this.loading = false;
        this.error = true;
      },
    });

    // A project still cloning has no issues to count yet, and a failed one never
    // will -- the tree call is made anyway and simply comes back empty, so the
    // counts block renders zeros rather than disappearing.
    this.issuesService.tree(projectId).subscribe({
      next: (tree) => {
        this.counts = countIssues(tree);
      },
      // Counts are secondary to the project's identity: a tree that fails to load
      // leaves them absent rather than blanking the whole page.
      error: () => {
        this.counts = null;
      },
    });
  }

  private loadAgentSessions(projectId: number): void {
    this.projectAgentSessionService.listOpen(projectId).subscribe({
      // A failed fetch leaves the button reading "Open agent": starting one
      // fresh is still a safe offer even though the existing list is unknown.
      next: (agentSessions) => (this.openAgentSessions = agentSessions),
      error: () => (this.openAgentSessions = []),
    });
  }

  private loadShells(projectId: number): void {
    // ShellsService.list() has no per-project endpoint (#733's own worktree list
    // filters the same way), so every open shell is fetched and narrowed here.
    this.shellsService.list().subscribe({
      next: (shells) => (this.openShells = shells.filter((s) => s.projectId === projectId)),
      error: () => (this.openShells = []),
    });
  }

  // A conversation outlives the agent session it ran in (#101), so this list is read
  // independently of the open-agent-session list; a failure leaves it simply empty
  // rather than blocking the rest of the page.
  private loadPastSessions(projectId: number): void {
    this.projectAgentSessionService.resumeSessions(projectId).subscribe({
      next: (sessions) => {
        this.pastSessions = sessions;
        this.pastSessionsLoading = false;
      },
      error: () => {
        this.pastSessions = [];
        this.pastSessionsLoading = false;
      },
    });
  }

  /**
   * Reopens a past conversation (#752): the engine mints a brand-new session in the
   * original agent session's working directory, then this navigates to the project's
   * agent session page with that session selected -- the same handoff `onAgentSessionButtonClick`
   * uses for "Open agent" -- so the resume itself happens there, where the terminal
   * lives. The engine only lists a session as open once something has attached to it,
   * so the freshly minted id is never in that page's open-agent-session list (#795): its
   * working directory rides along as `?dir=` so the page can add the tab itself, the
   * way the issue page's own reopen does, instead of falling through to some other
   * agent session.
   */
  reopenPastSession(session: ResumeSession): void {
    if (this.reopeningSession) {
      return;
    }
    this.reopeningSession = true;
    this.reopenSessionError = false;
    this.projectAgentSessionService.reopenSession(this.projectId, session.worktreeId).subscribe({
      next: (started) => {
        this.reopeningSession = false;
        this.agentStore.set(started.sessionId, session.tool);
        this.agentSessionsService.notifyOpened();
        // `resume`/`tool` ride along in the URL (read once by ProjectAgentSessionComponent)
        // because the session's first-ever WebSocket attach is what actually launches
        // `<tool> --resume <id>` (WorktreeController#reopenSession) -- this page never
        // mounts a terminal itself, so that attach only happens after this navigation.
        this.navigateToAgentSession(started.sessionId, {
          dir: started.workingDirectory,
          resume: session.resumeId,
          tool: session.tool,
        });
      },
      error: () => {
        this.reopeningSession = false;
        this.reopenSessionError = true;
      },
    });
  }

  /** The agent session button's label (#221): switches once the project has any open agent session. */
  get agentSessionButtonLabel(): string {
    if (this.startingAgentSession) {
      return 'starting…';
    }
    return this.openAgentSessions.length > 0 ? 'Open agents' : 'Open agent';
  }

  onAgentSessionButtonClick(): void {
    if (this.startingAgentSession) {
      return;
    }
    if (this.openAgentSessions.length === 0) {
      this.startAgentSession();
    } else {
      this.openMostRecentAgentSession();
    }
  }

  private startAgentSession(): void {
    this.startingAgentSession = true;
    this.agentSessionError = false;
    this.projectAgentSessionService.start(this.projectId).subscribe({
      next: (session) => {
        this.startingAgentSession = false;
        this.agentStore.set(session.sessionId, this.defaultAgentStore.agent());
        this.agentSessionsService.notifyOpened();
        // Same `?dir=` handoff as reopenPastSession (#795): without it the agent session
        // page, not finding the never-attached id in its open list, auto-started a
        // second agent session and left this one's worktree stranded.
        this.navigateToAgentSession(session.sessionId, { dir: session.workingDirectory });
      },
      error: () => {
        this.startingAgentSession = false;
        this.agentSessionError = true;
      },
    });
  }

  // "Most recently interacted with" (#221): the agent session the user last selected on
  // this project's own agent session page (LastAgentSessionStore), when it is still one of
  // the open ones -- otherwise the last entry in the open-agent-sessions list, the same
  // fallback a user with no recorded interaction yet would land on.
  private openMostRecentAgentSession(): void {
    const remembered = this.lastAgentSessionStore.get(this.projectId);
    const target = this.openAgentSessions.some((c) => c.sessionId === remembered)
      ? remembered!
      : this.openAgentSessions[this.openAgentSessions.length - 1].sessionId;
    this.navigateToAgentSession(target);
  }

  private navigateToAgentSession(sessionId: string, queryParams: Record<string, string> = {}): void {
    // 'console' is the route path segment -- a compatibility surface kept under ADR-112.
    this.router.navigate(['/projects', this.projectId, 'console'], {
      queryParams: { session: sessionId, ...queryParams },
    });
  }

  /** The shells button's label (#745): switches while a first mint is in flight. */
  get shellsButtonLabel(): string {
    return this.startingShell ? 'opening…' : 'Open shells';
  }

  onShellsButtonClick(): void {
    if (this.startingShell) {
      return;
    }
    if (this.openShells.length === 0) {
      this.startShell();
    } else {
      this.openMostRecentShell();
    }
  }

  // No open shell yet (#745): mint one at the project's own main worktree -- never
  // an issue's, since this button carries no issue context -- then focus the
  // singleton Shells window on it, the same convention `openShellAt`/`openMainShell`
  // already follow. Re-fetches afterwards so a second click reuses it instead of
  // minting again.
  private startShell(): void {
    if (!this.project) {
      return;
    }
    this.startingShell = true;
    this.shellError = false;
    this.shellsService.open(this.projectId, null, this.project.workareaPath).subscribe({
      next: (created) => {
        this.startingShell = false;
        this.loadShells(this.projectId);
        window.open(`/shells/${created.sessionId}`, 'locklane-shells');
      },
      error: () => {
        this.startingShell = false;
        this.shellError = true;
      },
    });
  }

  // "Most recently used" (#745): the open shell with the latest `lastAttachedAt`,
  // the same signal the engine updates on every reattach -- there is no
  // LastAgentSessionStore equivalent recording an explicit user pick for shells.
  private openMostRecentShell(): void {
    const target = this.openShells.reduce((latest, shell) =>
      new Date(shell.lastAttachedAt).getTime() > new Date(latest.lastAttachedAt).getTime() ? shell : latest,
    );
    window.open(`/shells/${target.sessionId}`, 'locklane-shells');
  }

  // The IDE "Open IDE" acts on (#831, mirroring #782): the Settings choice when this
  // browser may use it, else code-server.
  get effectiveIde(): InstalledIde {
    return this.defaultIdeStore.effective();
  }

  /** The "Open IDE" button's label (#831): names a desktop choice, same as the per-tab menu item (#782). */
  get ideButtonLabel(): string {
    if (this.startingIde) {
      return 'opening…';
    }
    return this.effectiveIde.desktop ? `Open in ${this.effectiveIde.label}` : 'Open IDE';
  }

  /**
   * Opens the project's main checkout in the effective IDE (#831): ensures the
   * project's main-checkout IDE session exists, then opens it exactly the way the
   * per-tab "Open IDE" action does -- mint/reuse server-side, then `window.open` the
   * result, never a path sent from here. For code-server that is a singleton browser
   * tab; a desktop IDE launches on the engine's own host and returns no URL.
   */
  onOpenIdeButtonClick(): void {
    if (this.startingIde) {
      return;
    }
    this.startingIde = true;
    this.ideOpenFailed = false;
    this.agentSessionsService
      .openMainCheckoutIdeSession(this.projectId)
      .pipe(switchMap((session) => this.agentSessionsService.openIde(this.projectId, session.sessionId, this.effectiveIde.id)))
      .subscribe({
        next: (opened) => {
          this.startingIde = false;
          if (opened.url !== null) {
            window.open(opened.url, 'locklane-ide');
          }
        },
        error: () => {
          this.startingIde = false;
          this.ideOpenFailed = true;
        },
      });
  }
}

/** Flattens the nested tree and tallies it. Exported for the spec and for reuse. */
export function countIssues(tree: TreeNode[]): IssueCounts {
  const counts: IssueCounts = { total: 0, open: 0, closed: 0, initiatives: 0, tasks: 0 };
  const walk = (nodes: TreeNode[]): void => {
    for (const node of nodes) {
      counts.total += 1;
      if (node.state.toUpperCase() === 'CLOSED') {
        counts.closed += 1;
      } else {
        counts.open += 1;
      }
      if (node.kind === 'INITIATIVE') {
        counts.initiatives += 1;
      } else {
        counts.tasks += 1;
      }
      walk(node.children);
    }
  };
  walk(tree);
  return counts;
}
