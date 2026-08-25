import { Component, Input, OnChanges, SimpleChanges, inject } from '@angular/core';
import { GhIssue, IssueDetail } from '../../models/issue.model';
import { IssuesService } from '../../services/issues.service';
import { AgentStore } from '../../services/agent-store';
import { ActiveConsoleStore } from '../../services/active-console-store';
import { IssueHeaderComponent } from '../issue-header/issue-header.component';
import { FlowStripComponent } from '../flow-strip/flow-strip.component';
import { ConsoleTabsComponent, OpenConsoleRequest } from '../console-tabs/console-tabs.component';
import { ConsoleTab, labelConsoles } from '../console-tabs/console-labels';
import { TerminalComponent } from '../terminal/terminal.component';

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
  imports: [IssueHeaderComponent, FlowStripComponent, ConsoleTabsComponent, TerminalComponent],
  templateUrl: './main-content.component.html',
  styleUrl: './main-content.component.css',
})
export class MainContentComponent implements OnChanges {
  private readonly issuesService = inject(IssuesService);
  private readonly agentStore = inject(AgentStore);
  private readonly activeConsoleStore = inject(ActiveConsoleStore);

  @Input({ required: true }) issueNumber!: number;

  issue: GhIssue | null = null;
  detail: IssueDetail | null = null;
  consoles: OpenConsole[] = [];
  tabs: ConsoleTab[] = [];
  selectedConsole: string | null = null;

  starting = false;
  startError = false;

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['issueNumber']) {
      this.load(this.issueNumber);
    }
  }

  private load(number: number): void {
    this.issue = null;
    this.detail = null;
    this.consoles = [];
    this.tabs = [];
    this.selectedConsole = null;
    this.startError = false;

    this.issuesService.get(number).subscribe((issue) => {
      this.issue = issue;
    });
    this.issuesService.detail(number).subscribe((detail) => {
      this.detail = detail;
    });
    this.issuesService.worktrees(number).subscribe((ids) => {
      this.consoles = ids.map((id) => ({ id, dir: null, agent: this.agentStore.get(id) }));
      const remembered = this.activeConsoleStore.get(number);
      this.selectedConsole = remembered && ids.includes(remembered) ? remembered : (ids[0] ?? null);
      this.relabel();
    });
  }

  selectConsole(id: string): void {
    this.selectedConsole = id;
    this.activeConsoleStore.set(this.issueNumber, id);
  }

  openConsole(request: OpenConsoleRequest): void {
    this.starting = true;
    this.startError = false;
    this.issuesService.startSession(this.issueNumber, request.worktree).subscribe({
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
        }
        this.selectedConsole = worktreeId;
        this.activeConsoleStore.set(this.issueNumber, worktreeId);
        this.starting = false;
      },
      error: () => {
        this.starting = false;
        this.startError = true;
      },
    });
  }

  private relabel(): void {
    this.tabs = labelConsoles(
      this.consoles.map((c) => ({ id: c.id, agent: (c.agent as ConsoleTab['agent']) ?? null })),
    );
  }
}
