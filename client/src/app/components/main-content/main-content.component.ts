import { Component, Input, OnChanges, OnInit, SimpleChanges, inject } from '@angular/core';
import { GhIssue, IssueDetail, ResumeSession } from '../../models/issue.model';
import { IssuesService } from '../../services/issues.service';
import { ProjectsService } from '../../services/projects.service';
import { AgentStore } from '../../services/agent-store';
import { DefaultAgentStore } from '../../services/default-agent-store';
import { ActiveAgentSessionStore } from '../../services/active-agent-session-store';
import { ActiveTabStore } from '../../services/active-tab-store';
import { AgentSessionsService } from '../../services/agent-sessions.service';
import { IssueHeaderComponent } from '../issue-header/issue-header.component';
import { FlowStripComponent } from '../flow-strip/flow-strip.component';
import { OverviewTabComponent } from '../overview-tab/overview-tab.component';
import { AgentSessionTabsComponent, OpenAgentSessionRequest } from '../agent-session-tabs/agent-session-tabs.component';
import { AgentSessionTab, OVERVIEW_TAB_ID, labelAgentSessions } from '../agent-session-tabs/agent-session-labels';
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
export class MainContentComponent implements OnChanges, OnInit {
  private readonly issuesService = inject(IssuesService);
  private readonly projectsService = inject(ProjectsService);
  private readonly agentSessionsService = inject(AgentSessionsService);
  private readonly agentStore = inject(AgentStore);
  readonly defaultAgentStore = inject(DefaultAgentStore);
  private readonly activeAgentSessionStore = inject(ActiveAgentSessionStore);
  private readonly activeTabStore = inject(ActiveTabStore);

  @Input({ required: true }) projectId!: number;
  @Input({ required: true }) issueNumber!: number;

  // Exposed for the template: which tab in the merged strip (#96) is showing
  // right now, either the Overview sentinel or an open agent session's id.
  readonly overviewId = OVERVIEW_TAB_ID;

  issue: GhIssue | null = null;
  detail: IssueDetail | null = null;
  resumeSessions: ResumeSession[] = [];
  repoWebUrl: string | null = null;
  activeTab: string = OVERVIEW_TAB_ID;
  agentSessions: OpenAgentSession[] = [];
  tabs: AgentSessionTab[] = [];
  selectedAgentSession: string | null = null;

  starting = false;
  startError = false;
  closeError = false;
  revealError = false;

  // #698: the agent-session-tabs "+" button reads `defaultAgentStore.agent()` directly, so
  // its fallback to the first installed agent needs this store's fetch already under
  // way on this page too, the same as #695's fix to project-summary's "Open agent".
  ngOnInit(): void {
    this.defaultAgentStore.refreshInstalled();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['issueNumber'] || changes['projectId']) {
      this.load(this.projectId, this.issueNumber);
    }
  }

  onTabSelected(id: string): void {
    if (id === OVERVIEW_TAB_ID) {
      this.selectOverview();
    } else {
      this.selectAgentSession(id);
    }
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
    this.tabs = [];
    this.selectedAgentSession = null;
    this.startError = false;
    this.closeError = false;
    this.revealError = false;

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
    });
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

  closeAgentSession(id: string): void {
    this.closeError = false;
    this.issuesService.closeSession(this.projectId, this.issueNumber, id).subscribe({
      next: () => {
        this.agentSessions = this.agentSessions.filter((c) => c.id !== id);
        this.relabel();
        if (this.selectedAgentSession === id) {
          const next = this.agentSessions[0]?.id ?? null;
          this.selectedAgentSession = next;
          if (next) {
            this.activeAgentSessionStore.set(this.issueNumber, next);
          }
        }
        if (this.activeTab === id) {
          this.setActiveTab(this.selectedAgentSession ?? OVERVIEW_TAB_ID);
        }
        this.agentSessionsService.notifyClosed();
      },
      error: () => {
        this.closeError = true;
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
    this.tabs = labelAgentSessions(
      this.agentSessions.map((c) => ({ id: c.id, agent: (c.agent as AgentSessionTab['agent']) ?? null })),
    );
  }
}
