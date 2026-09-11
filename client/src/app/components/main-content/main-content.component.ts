import { Component, Input, OnChanges, OnDestroy, OnInit, SimpleChanges, inject } from '@angular/core';
import { Observable, Subscription, catchError, forkJoin, map, merge, of, switchMap } from 'rxjs';
import { GhIssue, IssueDetail, ResumeSession } from '../../models/issue.model';
import { IssuesService } from '../../services/issues.service';
import { ProjectsService } from '../../services/projects.service';
import { AgentStore } from '../../services/agent-store';
import { DefaultAgentStore } from '../../services/default-agent-store';
import { ActiveAgentSessionStore } from '../../services/active-agent-session-store';
import { ActiveTabStore } from '../../services/active-tab-store';
import { AgentSessionsService } from '../../services/agent-sessions.service';
import { ShellsService } from '../../services/shells.service';
import { WorktreesService } from '../../services/worktrees.service';
import { IssueHeaderComponent } from '../issue-header/issue-header.component';
import { FlowStripComponent } from '../flow-strip/flow-strip.component';
import { OverviewTabComponent } from '../overview-tab/overview-tab.component';
import { AgentSessionTabsComponent, OpenAgentSessionRequest } from '../agent-session-tabs/agent-session-tabs.component';
import { AgentSessionTab, OVERVIEW_TAB_ID, labelAgentSessions, labelShellTabs } from '../agent-session-tabs/agent-session-labels';
import { TerminalComponent } from '../terminal/terminal.component';
import { repoWebUrl } from './repo-web-url';

// One agent session tab's client-side state. `dir` is only known for a session this
// page just started — reconnects leave it null and the engine resolves the
// working directory from its own records. `resume` is set only on an agent session
// this page just reopened from a past conversation (#103): it makes the first
// attach launch the tool's resume command instead of a blank session, and is
// irrelevant after that (a reattach reaches the process already running).
interface OpenAgentSession {
  id: string;
  dir: string | null;
  agent: string | null;
  resume: string | null;
}

// One shell tab's client-side state (#876): a plain shell at this issue's
// worktree directory, alongside the issue's agent session tabs.
interface OpenShell {
  id: string;
  dir: string;
  name: string | null;
}

@Component({
  selector: 'app-main-content',
  standalone: true,
  imports: [
    IssueHeaderComponent,
    FlowStripComponent,
    OverviewTabComponent,
    AgentSessionTabsComponent,
    TerminalComponent,
  ],
  templateUrl: './main-content.component.html',
  styleUrl: './main-content.component.css',
})
export class MainContentComponent implements OnChanges, OnInit, OnDestroy {
  private readonly issuesService = inject(IssuesService);
  private readonly projectsService = inject(ProjectsService);
  private readonly agentSessionsService = inject(AgentSessionsService);
  private readonly shellsService = inject(ShellsService);
  private readonly worktreesService = inject(WorktreesService);
  private readonly agentStore = inject(AgentStore);
  readonly defaultAgentStore = inject(DefaultAgentStore);
  private readonly activeAgentSessionStore = inject(ActiveAgentSessionStore);
  private readonly activeTabStore = inject(ActiveTabStore);

  @Input({ required: true }) projectId!: number;
  @Input({ required: true }) issueNumber!: number;

  // Exposed for the template: which tab in the merged strip (#96) is showing
  // right now, either the Overview sentinel or an open agent session's or shell's id.
  readonly overviewId = OVERVIEW_TAB_ID;

  issue: GhIssue | null = null;
  detail: IssueDetail | null = null;
  resumeSessions: ResumeSession[] = [];
  repoWebUrl: string | null = null;
  activeTab: string = OVERVIEW_TAB_ID;
  agentSessions: OpenAgentSession[] = [];
  shells: OpenShell[] = [];
  tabs: AgentSessionTab[] = [];
  selectedAgentSession: string | null = null;

  starting = false;
  startError = false;
  closeError = false;
  revealError = false;
  /** A shell mint or fan-out close failure message (#876), null when quiet. */
  shellError: string | null = null;

  private shellsSub: Subscription | null = null;

  // #698: the agent-session-tabs "+" button reads `defaultAgentStore.agent()` directly, so
  // its fallback to the first installed agent needs this store's fetch already under
  // way on this page too, the same as #695's fix to project-summary's "Open agent".
  ngOnInit(): void {
    this.defaultAgentStore.refreshInstalled();
    // A shell opened or closed elsewhere reaches this page over the events
    // channel (#876, folded into onOpened/onClosed) -- re-read this issue's
    // shells rather than trusting the local add/remove alone.
    this.shellsSub = merge(this.agentSessionsService.onOpened, this.agentSessionsService.onClosed).subscribe(() =>
      this.reloadShells(),
    );
  }

  ngOnDestroy(): void {
    this.shellsSub?.unsubscribe();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['issueNumber'] || changes['projectId']) {
      this.load(this.projectId, this.issueNumber);
    }
  }

  onTabSelected(id: string): void {
    if (id === OVERVIEW_TAB_ID) {
      this.selectOverview();
    } else if (this.shells.some((s) => s.id === id)) {
      this.selectShell(id);
    } else {
      this.selectAgentSession(id);
    }
  }

  /** Selecting a shell tab shows it; unlike an agent it feeds no recency store. */
  selectShell(id: string): void {
    this.setActiveTab(id);
  }

  selectOverview(): void {
    this.setActiveTab(OVERVIEW_TAB_ID);
  }

  private setActiveTab(id: string): void {
    this.activeTab = id;
    this.activeTabStore.set(this.issueNumber, id);
  }

  private load(projectId: number, number: number): void {
    this.issue = null;
    this.detail = null;
    this.resumeSessions = [];
    this.repoWebUrl = null;
    this.activeTab = OVERVIEW_TAB_ID;
    this.agentSessions = [];
    this.shells = [];
    this.tabs = [];
    this.selectedAgentSession = null;
    this.startError = false;
    this.closeError = false;
    this.revealError = false;
    this.shellError = null;

    this.issuesService.get(projectId, number).subscribe((issue) => {
      this.issue = issue;
    });
    this.issuesService.detail(projectId, number).subscribe((detail) => {
      this.detail = detail;
    });
    this.issuesService.resumeSessions(projectId, number).subscribe((sessions) => {
      this.resumeSessions = sessions;
    });
    this.projectsService.list().subscribe((projects) => {
      const project = projects.find((p) => p.id === projectId);
      this.repoWebUrl = project ? repoWebUrl(project.gitUrl) : null;
    });
    this.issuesService.worktrees(projectId, number).subscribe((ids) => {
      this.agentSessions = ids.map((id) => ({ id, dir: null, agent: this.agentStore.get(id), resume: null }));
      const remembered = this.activeAgentSessionStore.get(number);
      this.selectedAgentSession = remembered && ids.includes(remembered) ? remembered : (ids[0] ?? null);
      this.relabel();

      const rememberedTab = this.activeTabStore.get(number);
      this.activeTab =
        rememberedTab && (rememberedTab === OVERVIEW_TAB_ID || ids.includes(rememberedTab))
          ? rememberedTab
          : OVERVIEW_TAB_ID;
      // The remembered tab may be a shell, which the agent list above never
      // carries: the shell list arriving below adopts it when it is open (#876).
      this.loadShells();
    });
  }

  /**
   * This issue's open shells (#876): every shell the listing ties to this
   * project and issue number. After rebuilding, a remembered shell tab the
   * agent list above could not validate is adopted when it is open.
   */
  private loadShells(): void {
    this.shellsService.list().subscribe({
      next: (shells) => {
        this.shells = shells
          .filter((shell) => shell.projectId === this.projectId && shell.issueNumber === this.issueNumber)
          .map((shell) => ({ id: shell.sessionId, dir: shell.workingDirectory, name: shell.displayName ?? null }));
        this.relabel();
        const rememberedTab = this.activeTabStore.get(this.issueNumber);
        if (
          this.activeTab === OVERVIEW_TAB_ID &&
          rememberedTab !== null &&
          rememberedTab !== OVERVIEW_TAB_ID &&
          this.shells.some((s) => s.id === rememberedTab)
        ) {
          this.setActiveTab(rememberedTab);
        } else if (!this.isTabOpen(this.activeTab)) {
          this.setActiveTab(this.selectedAgentSession ?? OVERVIEW_TAB_ID);
        }
      },
      error: () => {
        // Shells are secondary to the agent session: a failed fetch leaves them
        // absent rather than blanking the page.
      },
    });
  }

  /** A shell opened or closed elsewhere (#876): re-read this issue's shells. */
  private reloadShells(): void {
    this.loadShells();
  }

  private isTabOpen(id: string): boolean {
    return (
      id === OVERVIEW_TAB_ID ||
      this.agentSessions.some((c) => c.id === id) ||
      this.shells.some((s) => s.id === id)
    );
  }

  selectAgentSession(id: string): void {
    this.selectedAgentSession = id;
    this.activeAgentSessionStore.set(this.issueNumber, id);
    this.setActiveTab(id);
  }

  openAgentSession(request: OpenAgentSessionRequest): void {
    this.starting = true;
    this.startError = false;
    this.issuesService.startSession(this.projectId, this.issueNumber).subscribe({
      next: ({ worktreeId, workingDirectory }) => {
        // Reuses the issue's existing worktree session when one exists (#29) —
        // then there is no new tab to add, just select it.
        if (!this.agentSessions.some((c) => c.id === worktreeId)) {
          this.agentStore.set(worktreeId, request.agent);
          this.agentSessions = [
            ...this.agentSessions,
            { id: worktreeId, dir: workingDirectory, agent: request.agent, resume: null },
          ];
          this.relabel();
          this.agentSessionsService.notifyOpened();
        }
        this.selectAgentSession(worktreeId);
        this.starting = false;
      },
      error: () => {
        this.starting = false;
        this.startError = true;
      },
    });
  }

  /**
   * The tab strip's "+" Shell entry (#876): mints a brand-new shell at this
   * issue's worktree directory -- never a reuse, every click another shell --
   * and selects it. The directory is the live agent session's when this page
   * just started one, else the project worktree list's row for this issue.
   */
  openShell(): void {
    if (this.starting) {
      return;
    }
    this.starting = true;
    this.shellError = null;
    this.resolveWorktreeDir()
      .pipe(switchMap((dir) => this.shellsService.open(this.projectId, this.issueNumber, dir)))
      .subscribe({
        next: (created) => {
          this.shells = [
            ...this.shells,
            { id: created.sessionId, dir: created.workingDirectory, name: null },
          ];
          this.relabel();
          this.setActiveTab(created.sessionId);
          this.starting = false;
          this.agentSessionsService.notifyOpened();
        },
        error: () => {
          this.starting = false;
          this.shellError = 'could not open a shell — try again';
        },
      });
  }

  private resolveWorktreeDir(): Observable<string> {
    const live = this.agentSessions.find((c) => c.dir !== null);
    if (live?.dir) {
      return of(live.dir);
    }
    return this.worktreesService.list(this.projectId).pipe(
      map((rows) => {
        const row = rows.find((r) => r.issueNumber === this.issueNumber);
        if (!row) {
          throw new Error(`no worktree directory known for issue #${this.issueNumber}`);
        }
        return row.workingDirectory;
      }),
    );
  }
  /**
   * Reopens a past conversation (#103): the engine mints a brand-new session in
   * the original agent session's working directory, and the first attach launches the
   * tool's resume command (`claude --resume <id>` / `codex resume <id>`).
   */
  reopenSession(session: ResumeSession): void {
    this.starting = true;
    this.startError = false;
    this.issuesService.reopenSession(this.projectId, this.issueNumber, session.worktreeId).subscribe({
      next: ({ worktreeId, workingDirectory }) => {
        this.agentStore.set(worktreeId, session.tool);
        this.agentSessions = [
          ...this.agentSessions,
          { id: worktreeId, dir: workingDirectory, agent: session.tool, resume: session.resumeId },
        ];
        this.relabel();
        this.agentSessionsService.notifyOpened();
        this.selectAgentSession(worktreeId);
        this.starting = false;
      },
      error: () => {
        this.starting = false;
        this.startError = true;
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
    this.issuesService.closeSession(this.projectId, this.issueNumber, id).subscribe({
      next: () => {
        this.agentSessions = this.agentSessions.filter((c) => c.id !== id);
        // #876: this issue's shells die with the worktree -- a shell owns no
        // worktree of its own, so the engine leaves their rows behind and the
        // client ends each one rather than stranding it on a removed directory.
        const doomed = this.shells;
        this.shells = [];
        this.relabel();
        if (this.selectedAgentSession === id) {
          const next = this.agentSessions[0]?.id ?? null;
          this.selectedAgentSession = next;
          if (next) {
            this.activeAgentSessionStore.set(this.issueNumber, next);
          }
        }
        if (!this.isTabOpen(this.activeTab)) {
          this.setActiveTab(this.selectedAgentSession ?? OVERVIEW_TAB_ID);
        }
        this.agentSessionsService.notifyClosed();
        if (doomed.length === 0) {
          return;
        }
        forkJoin(
          doomed.map((shell) =>
            this.shellsService.close(this.projectId, shell.id).pipe(
              map(() => true),
              catchError(() => of(false)),
            ),
          ),
        ).subscribe((results) => {
          if (results.some((ok) => !ok)) {
            this.shellError = 'could not close a shell — try again';
          }
          this.agentSessionsService.notifyClosed();
        });
      },
      error: () => {
        this.closeError = true;
      },
    });
  }

  /** Ends one shell tab for good (#876): kills the process and drops the tab. */
  closeShell(id: string): void {
    this.closeError = false;
    this.shellsService.close(this.projectId, id).subscribe({
      next: () => {
        this.shells = this.shells.filter((s) => s.id !== id);
        this.relabel();
        if (!this.isTabOpen(this.activeTab)) {
          this.setActiveTab(this.selectedAgentSession ?? OVERVIEW_TAB_ID);
        }
        this.agentSessionsService.notifyClosed();
      },
      error: () => {
        this.shellError = 'could not close a shell — try again';
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

  private relabel(): void {
    this.tabs = [
      ...labelAgentSessions(
        this.agentSessions.map((c) => ({ id: c.id, agent: (c.agent as AgentSessionTab['agent']) ?? null })),
      ),
      ...labelShellTabs(this.shells),
    ];
  }
}
