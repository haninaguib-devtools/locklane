import { Component, Input, OnChanges, SimpleChanges, inject } from '@angular/core';
import { GhIssue, IssueDetail } from '../../models/issue.model';
import { IssuesService } from '../../services/issues.service';
import { ProjectsService } from '../../services/projects.service';
import { AgentStore } from '../../services/agent-store';
import { ActiveConsoleStore } from '../../services/active-console-store';
import { ConsolesService } from '../../services/consoles.service';
import { IssueHeaderComponent } from '../issue-header/issue-header.component';
import { FlowStripComponent } from '../flow-strip/flow-strip.component';
import { OverviewTabComponent } from '../overview-tab/overview-tab.component';
import { ConsoleTabsComponent, OpenConsoleRequest } from '../console-tabs/console-tabs.component';
import { ConsoleTab, OVERVIEW_TAB_ID, labelConsoles } from '../console-tabs/console-labels';
import { TerminalComponent } from '../terminal/terminal.component';
import { repoWebUrl } from './repo-web-url';

// One console tab's client-side state. `dir` is only known for a session this
// page just started — reconnects leave it null and the engine resolves the
// working directory from its own records.
interface OpenConsole {
  id: string;
  dir: string | null;
  agent: string | null;
}

@Component({
  selector: 'app-main-content',
  standalone: true,
  imports: [
    IssueHeaderComponent,
    FlowStripComponent,
    OverviewTabComponent,
    ConsoleTabsComponent,
    TerminalComponent,
  ],
  templateUrl: './main-content.component.html',
  styleUrl: './main-content.component.css',
})
export class MainContentComponent implements OnChanges {
  private readonly issuesService = inject(IssuesService);
  private readonly projectsService = inject(ProjectsService);
  private readonly consolesService = inject(ConsolesService);
  private readonly agentStore = inject(AgentStore);
  private readonly activeConsoleStore = inject(ActiveConsoleStore);

  @Input({ required: true }) projectId!: number;
  @Input({ required: true }) issueNumber!: number;

  // Exposed for the template: which tab in the merged strip (#96) is showing
  // right now, either the Overview sentinel or an open console's id.
  readonly overviewId = OVERVIEW_TAB_ID;

  issue: GhIssue | null = null;
  detail: IssueDetail | null = null;
  repoWebUrl: string | null = null;
  activeTab: string = OVERVIEW_TAB_ID;
  consoles: OpenConsole[] = [];
  tabs: ConsoleTab[] = [];
  selectedConsole: string | null = null;

  starting = false;
  startError = false;
  closeError = false;

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['issueNumber'] || changes['projectId']) {
      this.load(this.projectId, this.issueNumber);
    }
  }

  onTabSelected(id: string): void {
    if (id === OVERVIEW_TAB_ID) {
      this.selectOverview();
    } else {
      this.selectConsole(id);
    }
  }

  selectOverview(): void {
    this.activeTab = OVERVIEW_TAB_ID;
  }

  private load(projectId: number, number: number): void {
    this.issue = null;
    this.detail = null;
    this.repoWebUrl = null;
    this.activeTab = OVERVIEW_TAB_ID;
    this.consoles = [];
    this.tabs = [];
    this.selectedConsole = null;
    this.startError = false;
    this.closeError = false;

    this.issuesService.get(projectId, number).subscribe((issue) => {
      this.issue = issue;
    });
    this.issuesService.detail(projectId, number).subscribe((detail) => {
      this.detail = detail;
    });
    this.projectsService.list().subscribe((projects) => {
      const project = projects.find((p) => p.id === projectId);
      this.repoWebUrl = project ? repoWebUrl(project.gitUrl) : null;
    });
    this.issuesService.worktrees(projectId, number).subscribe((ids) => {
      this.consoles = ids.map((id) => ({ id, dir: null, agent: this.agentStore.get(id) }));
      const remembered = this.activeConsoleStore.get(number);
      this.selectedConsole = remembered && ids.includes(remembered) ? remembered : (ids[0] ?? null);
      this.relabel();
    });
  }

  selectConsole(id: string): void {
    this.selectedConsole = id;
    this.activeTab = id;
    this.activeConsoleStore.set(this.issueNumber, id);
  }

  openConsole(request: OpenConsoleRequest): void {
    this.starting = true;
    this.startError = false;
    this.issuesService.startSession(this.projectId, this.issueNumber, request.worktree).subscribe({
      next: ({ worktreeId, workingDirectory }) => {
        // A worktree request reuses the issue's existing worktree session when
        // one exists (#29) — then there is no new tab to add, just select it.
        if (!this.consoles.some((c) => c.id === worktreeId)) {
          this.agentStore.set(worktreeId, request.agent);
          this.consoles = [
            ...this.consoles,
            { id: worktreeId, dir: workingDirectory, agent: request.agent },
          ];
          this.relabel();
          this.consolesService.notifyOpened();
        }
        this.selectConsole(worktreeId);
        this.starting = false;
      },
      error: () => {
        this.starting = false;
        this.startError = true;
      },
    });
  }

  closeConsole(id: string): void {
    this.closeError = false;
    this.issuesService.closeSession(this.projectId, this.issueNumber, id).subscribe({
      next: () => {
        this.consoles = this.consoles.filter((c) => c.id !== id);
        this.relabel();
        if (this.selectedConsole === id) {
          const next = this.consoles[0]?.id ?? null;
          this.selectedConsole = next;
          if (next) {
            this.activeConsoleStore.set(this.issueNumber, next);
          }
        }
        if (this.activeTab === id) {
          this.activeTab = this.selectedConsole ?? OVERVIEW_TAB_ID;
        }
        this.consolesService.notifyClosed();
      },
      error: () => {
        this.closeError = true;
      },
    });
  }

  private relabel(): void {
    this.tabs = labelConsoles(
      this.consoles.map((c) => ({ id: c.id, agent: (c.agent as ConsoleTab['agent']) ?? null })),
    );
  }
}
