import { Component, Input, OnChanges, SimpleChanges, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Project, TreeNode } from '../../models/issue.model';
import { IssuesService } from '../../services/issues.service';
import { ProjectsService } from '../../services/projects.service';

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
  imports: [RouterLink],
  templateUrl: './project-summary.component.html',
  styleUrl: './project-summary.component.css',
})
export class ProjectSummaryComponent implements OnChanges {
  private readonly projectsService = inject(ProjectsService);
  private readonly issuesService = inject(IssuesService);

  @Input({ required: true }) projectId!: number;

  project: Project | null = null;
  counts: IssueCounts | null = null;
  loading = true;
  error = false;

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['projectId']) {
      this.load(this.projectId);
    }
  }

  private load(projectId: number): void {
    this.project = null;
    this.counts = null;
    this.loading = true;
    this.error = false;

    this.projectsService.list().subscribe({
      next: (projects) => {
        this.project = projects.find((p) => p.id === projectId) ?? null;
        this.loading = false;
        this.error = this.project === null;
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
