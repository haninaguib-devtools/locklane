import { HttpErrorResponse } from '@angular/common/http';
import { Component, EventEmitter, Input, OnChanges, Output, SimpleChanges, inject } from '@angular/core';
import { Router } from '@angular/router';
import { Project, TreeNode } from '../../models/issue.model';
import { ConfirmDialogComponent } from '../confirm-dialog/confirm-dialog.component';
import { IssuesService } from '../../services/issues.service';
import { ProjectsService } from '../../services/projects.service';
import { OpenProjectConsole, ProjectConsoleService } from '../../services/project-console.service';
import { ConsolesService } from '../../services/consoles.service';
import { AgentStore } from '../../services/agent-store';
import { DefaultAgentStore } from '../../services/default-agent-store';
import { LastConsoleStore } from '../../services/last-console-store';

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
  imports: [ConfirmDialogComponent],
  templateUrl: './project-summary.component.html',
  styleUrl: './project-summary.component.css',
})
export class ProjectSummaryComponent implements OnChanges {
  private readonly projectsService = inject(ProjectsService);
  private readonly issuesService = inject(IssuesService);
  private readonly projectConsoleService = inject(ProjectConsoleService);
  private readonly consolesService = inject(ConsolesService);
  private readonly agentStore = inject(AgentStore);
  private readonly defaultAgentStore = inject(DefaultAgentStore);
  private readonly lastConsoleStore = inject(LastConsoleStore);
  private readonly router = inject(Router);

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
  // deleting immediately, and surfaces the backend's refusal (open worktree/console)
  // inline rather than navigating away or failing silently.
  showDeleteConfirm = false;
  deleting = false;
  deleteError: string | null = null;

  // The project's open consoles (#221), fetched only once the project is known to
  // be READY -- a cloning or failed project has nowhere to run one. Drives the
  // console button's label and where it navigates.
  openConsoles: OpenProjectConsole[] = [];
  startingConsole = false;
  consoleError = false;

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

  private load(projectId: number): void {
    this.project = null;
    this.counts = null;
    this.loading = true;
    this.error = false;
    this.openConsoles = [];
    this.startingConsole = false;
    this.consoleError = false;

    this.projectsService.list().subscribe({
      next: (projects) => {
        this.project = projects.find((p) => p.id === projectId) ?? null;
        this.loading = false;
        this.error = this.project === null;
        if (this.project?.status === 'READY') {
          this.loadConsoles(projectId);
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

  private loadConsoles(projectId: number): void {
    this.projectConsoleService.listOpen(projectId).subscribe({
      // A failed fetch leaves the button reading "Open console": starting one
      // fresh is still a safe offer even though the existing list is unknown.
      next: (consoles) => (this.openConsoles = consoles),
      error: () => (this.openConsoles = []),
    });
  }

  /** The console button's label (#221): switches once the project has any open console. */
  get consoleButtonLabel(): string {
    if (this.startingConsole) {
      return 'starting…';
    }
    return this.openConsoles.length > 0 ? 'Open consoles' : 'Open console';
  }

  onConsoleButtonClick(): void {
    if (this.startingConsole) {
      return;
    }
    if (this.openConsoles.length === 0) {
      this.startConsole();
    } else {
      this.openMostRecentConsole();
    }
  }

  private startConsole(): void {
    this.startingConsole = true;
    this.consoleError = false;
    this.projectConsoleService.start(this.projectId).subscribe({
      next: (session) => {
        this.startingConsole = false;
        this.agentStore.set(session.sessionId, this.defaultAgentStore.agent());
        this.consolesService.notifyOpened();
        this.navigateToConsole(session.sessionId);
      },
      error: () => {
        this.startingConsole = false;
        this.consoleError = true;
      },
    });
  }

  // "Most recently interacted with" (#221): the console the user last selected on
  // this project's own console page (LastConsoleStore), when it is still one of
  // the open ones -- otherwise the last entry in the open-consoles list, the same
  // fallback a user with no recorded interaction yet would land on.
  private openMostRecentConsole(): void {
    const remembered = this.lastConsoleStore.get(this.projectId);
    const target = this.openConsoles.some((c) => c.sessionId === remembered)
      ? remembered!
      : this.openConsoles[this.openConsoles.length - 1].sessionId;
    this.navigateToConsole(target);
  }

  private navigateToConsole(sessionId: string): void {
    this.router.navigate(['/projects', this.projectId, 'console'], { queryParams: { session: sessionId } });
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
