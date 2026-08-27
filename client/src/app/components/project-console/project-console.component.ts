import { Component, Input, OnChanges, OnDestroy, SimpleChanges, inject } from '@angular/core';
import { Router } from '@angular/router';
import { Agent } from '../../services/agent-store';
import { AgentPickerComponent } from '../agent-picker/agent-picker.component';
import { IssuesService } from '../../services/issues.service';
import { ProjectConsoleService } from '../../services/project-console.service';
import { TerminalComponent } from '../terminal/terminal.component';

// The project-level console page (#140, part of #138): lets a user start a
// Claude/Codex/shell conversation in the project's own checkout -- where the
// /t-open skill and `gh` are available -- before any issue exists, so an agent can
// open one. Reuses the same TerminalComponent and agent picker an issue's own
// consoles use; unlike those there is only ever one session here (deterministic
// "<projectId>-console" id, minted server-side), so this page has no tab strip.
@Component({
  selector: 'app-project-console',
  standalone: true,
  imports: [AgentPickerComponent, TerminalComponent],
  templateUrl: './project-console.component.html',
  styleUrl: './project-console.component.css',
})
export class ProjectConsoleComponent implements OnChanges, OnDestroy {
  private readonly service = inject(ProjectConsoleService);
  private readonly issuesService = inject(IssuesService);
  private readonly router = inject(Router);

  @Input({ required: true }) projectId!: number;

  loading = true;
  sessionId: string | null = null;
  dir: string | null = null;
  agent: Agent = 'claude';
  starting = false;
  startError = false;

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['projectId']) {
      this.load(this.projectId);
    }
  }

  // Leaving this page -- however the navigation happened -- never closes the
  // session (it keeps running server-side for the next reattach, same as an
  // issue's own consoles); what it does need is telling the sidenav its cached
  // view of this project's issue list may be stale, since the agent may have just
  // opened one via `gh` before the engine's own 30s poll would notice (#140).
  ngOnDestroy(): void {
    this.issuesService.notifyProjectStale(this.projectId);
  }

  private load(projectId: number): void {
    this.loading = true;
    this.sessionId = null;
    this.dir = null;
    this.starting = false;
    this.startError = false;
    this.service.find(projectId).subscribe({
      next: (session) => {
        this.sessionId = session.sessionId;
        this.dir = session.workingDirectory;
        this.loading = false;
      },
      // No session has ever been attached to for this project (404) -- show the
      // agent picker instead so the user can start one.
      error: () => {
        this.loading = false;
      },
    });
  }

  open(): void {
    this.starting = true;
    this.startError = false;
    this.service.start(this.projectId).subscribe({
      next: (session) => {
        this.sessionId = session.sessionId;
        this.dir = session.workingDirectory;
        this.starting = false;
      },
      error: () => {
        this.starting = false;
        this.startError = true;
      },
    });
  }

  back(): void {
    this.router.navigate(['/projects', this.projectId, 'issues']);
  }
}
