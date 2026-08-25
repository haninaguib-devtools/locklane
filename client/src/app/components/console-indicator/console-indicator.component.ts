import { Component, OnInit, inject } from '@angular/core';
import { Router } from '@angular/router';
import { forkJoin } from 'rxjs';
import { ConsolesService } from '../../services/consoles.service';
import { IssuesService } from '../../services/issues.service';
import { AgentStore } from '../../services/agent-store';
import { ActiveConsoleStore } from '../../services/active-console-store';
import { labelConsoles } from '../console-tabs/console-labels';

export interface ConsoleEntry {
  sessionId: string;
  issueNumber: number;
  issueTitle: string;
  label: string;
}

// The "Open Shells"-style header badge (#32): shows how many consoles are
// open across every issue, and a picker that jumps straight to one.
@Component({
  selector: 'app-console-indicator',
  standalone: true,
  templateUrl: './console-indicator.component.html',
  styleUrl: './console-indicator.component.css',
})
export class ConsoleIndicatorComponent implements OnInit {
  private readonly consolesService = inject(ConsolesService);
  private readonly issuesService = inject(IssuesService);
  private readonly agentStore = inject(AgentStore);
  private readonly activeConsoleStore = inject(ActiveConsoleStore);
  private readonly router = inject(Router);

  entries: ConsoleEntry[] = [];
  open = false;

  ngOnInit(): void {
    this.refresh();
  }

  toggle(): void {
    this.open = !this.open;
    if (this.open) {
      this.refresh();
    }
  }

  jumpTo(entry: ConsoleEntry): void {
    this.activeConsoleStore.set(entry.issueNumber, entry.sessionId);
    this.open = false;
    this.router.navigate(['/issues', entry.issueNumber]);
  }

  private refresh(): void {
    forkJoin([this.consolesService.list(), this.issuesService.list()]).subscribe(([ids, issues]) => {
      const titles = new Map(issues.map((issue) => [issue.number, issue.title]));
      this.entries = ids
        .map((id) => this.toEntry(id, titles))
        .filter((entry): entry is ConsoleEntry => entry !== null);
    });
  }

  private toEntry(sessionId: string, titles: Map<number, string>): ConsoleEntry | null {
    const match = /^(\d+)-/.exec(sessionId);
    if (!match) {
      return null;
    }
    const issueNumber = Number(match[1]);
    const [{ label }] = labelConsoles([{ id: sessionId, agent: this.agentStore.get(sessionId) }]);
    return { sessionId, issueNumber, issueTitle: titles.get(issueNumber) ?? `#${issueNumber}`, label };
  }
}
