import { Component, EventEmitter, OnInit, Output, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { catchError, forkJoin, map, of, switchMap } from 'rxjs';
import { Project, TreeNode } from '../../models/issue.model';
import { IssuesService } from '../../services/issues.service';
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
export class OverviewComponent implements OnInit {
  private readonly projectsService = inject(ProjectsService);
  private readonly issuesService = inject(IssuesService);

  // Emitted by the zero-project empty state's CTA (#227) -- opening the add-project
  // popup is AppComponent's job, since it's also the header button's opener.
  @Output() addProject = new EventEmitter<void>();

  rows: ProjectOverviewRow[] = [];
  loading = true;
  error = false;

  ngOnInit(): void {
    this.load();
  }

  /** Re-fetches the workspace's project list (#227), e.g. after one is added elsewhere. */
  refresh(): void {
    this.load();
  }

  private load(): void {
    this.loading = true;
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
