import { Component, EventEmitter, OnDestroy, OnInit, Output, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Subscription, catchError, filter, forkJoin, map, merge, of, switchMap } from 'rxjs';
import { Project, TreeNode } from '../../models/issue.model';
import { AgentStore } from '../../services/agent-store';
import { ConsolesService } from '../../services/consoles.service';
import {
  EventsService,
  ProjectDeletedEvent,
  ProjectStatusEvent,
  isProjectDeletedEvent,
  isProjectStatusEvent,
} from '../../services/events.service';
import { IssuesService } from '../../services/issues.service';
import { ProjectConsoleService } from '../../services/project-console.service';
import { ProjectsService } from '../../services/projects.service';
import { IssueCounts, countIssues } from '../project-summary/project-summary.component';

/** One row of the per-project breakdown; `counts` is null for a project not yet READY. */
export interface ProjectOverviewRow {
  project: Project;
  counts: IssueCounts | null;
}

// The workspace landing page (#197), shown at '/' in place of the old
// redirect-into-the-first-project behavior (#43) for anyone logged in with at
// least one project. It composes its totals client-side from the same
// per-project calls the project summary page (#85) already makes for one
// project -- no new aggregate endpoint.
@Component({
  selector: 'app-overview',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './overview.component.html',
  styleUrl: './overview.component.css',
})
export class OverviewComponent implements OnInit, OnDestroy {
  private readonly projectsService = inject(ProjectsService);
  private readonly issuesService = inject(IssuesService);
  private readonly projectConsoleService = inject(ProjectConsoleService);
  private readonly agentStore = inject(AgentStore);
  private readonly consolesService = inject(ConsolesService);
  private readonly eventsService = inject(EventsService);
  private readonly router = inject(Router);

  // Emitted by the zero-project empty state's CTA (#227) -- opening the add-project
  // popup is AppComponent's job, since it's also the header button's opener.
  @Output() addProject = new EventEmitter<void>();

  rows: ProjectOverviewRow[] = [];
  loading = true;
  error = false;

  // The project row currently minting a shell console (#256) -- guards the
  // button against a double-click opening two sessions, mirroring the
  // sidenav's own one-click "+" guard.
  private startingShellFor: number | null = null;

  // Updates a cloning row off the engine's `projectStatus` / `projectDeleted`
  // broadcasts (#721) instead of re-polling; a reconnect does one full reload, since
  // an event missed while the socket was down is gone for good -- the same pattern
  // the sidenav's own eventsSub uses.
  private readonly eventsSub: Subscription;

  constructor() {
    this.eventsSub = merge(
      this.eventsService.events$.pipe(
        filter(isProjectStatusEvent),
        map((event) => () => this.applyProjectStatusEvent(event)),
      ),
      this.eventsService.events$.pipe(
        filter(isProjectDeletedEvent),
        map((event) => () => this.applyProjectDeletedEvent(event)),
      ),
      this.eventsService.reconnected$.pipe(map(() => () => this.load(true))),
    ).subscribe((run) => run());
  }

  ngOnDestroy(): void {
    this.eventsSub.unsubscribe();
  }

  ngOnInit(): void {
    this.load();
  }

  /** Re-fetches the workspace's project list (#227), e.g. after one is added elsewhere. */
  refresh(): void {
    this.load();
  }

  private load(quiet = false): void {
    if (!quiet) {
      this.loading = true;
    }
    this.error = false;

    this.projectsService
      .list()
      .pipe(
        switchMap((projects) =>
          projects.length === 0
            ? of([] as ProjectOverviewRow[])
            : forkJoin(projects.map((project) => this.loadRow(project))),
        ),
      )
      .subscribe({
        next: (rows) => {
          this.rows = rows;
          this.loading = false;
        },
        error: () => {
          this.error = true;
          this.loading = false;
        },
      });
  }

  /**
   * A clone reached READY or FAILED (#721): update that row's project in place --
   * what replaces the 3s cloning poll that used to notice this instead. READY also
   * fetches the row's issue counts, absent until now; a project not loaded here is
   * ignored.
   */
  private applyProjectStatusEvent(event: ProjectStatusEvent): void {
    const index = this.rows.findIndex((r) => r.project.id === event.projectId);
    if (index === -1) {
      return;
    }
    const project: Project = {
      ...this.rows[index].project,
      status: event.status,
      defaultBranch: event.defaultBranch ?? this.rows[index].project.defaultBranch,
    };
    if (event.status === 'FAILED') {
      this.rows[index] = { project, counts: null };
      return;
    }
    this.loadRow(project).subscribe((row) => {
      this.rows[index] = row;
    });
  }

  /**
   * A project was deleted (#721, absorbed from #720): drop its row. A project not
   * loaded here is a no-op.
   */
  private applyProjectDeletedEvent(event: ProjectDeletedEvent): void {
    this.rows = this.rows.filter((r) => r.project.id !== event.projectId);
  }

  // A project still cloning or failed has no issues to count (mirrors
  // project-summary.component.ts) -- the row renders with counts absent rather
  // than blocking on, or failing, the whole page over one project's tree.
  private loadRow(project: Project) {
    return this.issuesService.tree(project.id).pipe(
      map((tree: TreeNode[]) => ({ project, counts: countIssues(tree) })),
      catchError(() => of({ project, counts: null })),
    );
  }

  get totals(): IssueCounts {
    return aggregateCounts(this.rows.map((r) => r.counts));
  }

  completionPercent(counts: IssueCounts): number {
    return counts.total === 0 ? 0 : (counts.closed / counts.total) * 100;
  }

  isStartingShell(projectId: number): boolean {
    return this.startingShellFor === projectId;
  }

  /**
   * Opens a plain shell console for a project (#256) -- no LLM picker, no
   * default-agent involvement -- the one place that capability lives now that
   * both "+"s always launch the default LLM. Navigates the same way the other
   * entry points (sidenav's "+", project-console's tab strip) do.
   */
  openShell(projectId: number, event: Event): void {
    event.preventDefault();
    event.stopPropagation();
    if (this.startingShellFor !== null) {
      return;
    }
    this.startingShellFor = projectId;
    this.projectConsoleService.start(projectId).subscribe({
      next: (session) => {
        this.startingShellFor = null;
        this.agentStore.set(session.sessionId, 'shell');
        this.consolesService.notifyOpened();
        this.router.navigate(['/projects', projectId, 'console'], {
          queryParams: { session: session.sessionId },
        });
      },
      error: () => {
        this.startingShellFor = null;
      },
    });
  }
}

/** Sums every project's counts into one workspace-wide total. Exported for the spec. */
export function aggregateCounts(counts: (IssueCounts | null)[]): IssueCounts {
  const totals: IssueCounts = { total: 0, open: 0, closed: 0, initiatives: 0, tasks: 0 };
  for (const c of counts) {
    if (!c) {
      continue;
    }
    totals.total += c.total;
    totals.open += c.open;
    totals.closed += c.closed;
    totals.initiatives += c.initiatives;
    totals.tasks += c.tasks;
  }
  return totals;
}
