import { HttpErrorResponse } from '@angular/common/http';
import { Component, Input, OnChanges, SimpleChanges, inject } from '@angular/core';
import { ProjectWorktree, WorktreesService } from '../../services/worktrees.service';

/**
 * The project page's worktree list (#320): every worktree tied to the project's
 * issues, with a manual "remove worktree" per row and a page-level "run cleanup now"
 * button — so a human can directly verify the console button (#318) and the periodic
 * cleanup sweep (#319) are behaving as expected, and clear out a stray worktree
 * without waiting on the schedule. Both actions go through the engine's
 * {@code ProjectWorktreesController}, which applies the exact same safety guard as
 * the periodic sweep rather than a separate, potentially-drifting copy of it.
 */
@Component({
  selector: 'app-worktree-list',
  standalone: true,
  templateUrl: './worktree-list.component.html',
  styleUrl: './worktree-list.component.css',
})
export class WorktreeListComponent implements OnChanges {
  private readonly worktreesService = inject(WorktreesService);

  @Input({ required: true }) projectId!: number;

  rows: ProjectWorktree[] = [];
  loading = true;
  loadError = false;

  // Which row's remove is in flight, and the refusal message (if any) for the row it
  // last failed on -- keyed by worktreeId so one row's refusal never bleeds into
  // another's.
  removingId: string | null = null;
  removeErrors = new Map<string, string>();

  cleaningUp = false;
  cleanupMessage: string | null = null;
  cleanupError = false;

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['projectId']) {
      this.load();
    }
  }

  private load(): void {
    this.loading = true;
    this.loadError = false;
    this.worktreesService.list(this.projectId).subscribe({
      next: (rows) => {
        this.rows = rows;
        this.loading = false;
      },
      error: () => {
        this.loading = false;
        this.loadError = true;
      },
    });
  }

  remove(row: ProjectWorktree): void {
    if (this.removingId) {
      return;
    }
    this.removingId = row.worktreeId;
    this.removeErrors.delete(row.worktreeId);
    this.worktreesService.remove(this.projectId, row.worktreeId).subscribe({
      next: () => {
        this.removingId = null;
        this.rows = this.rows.filter((r) => r.worktreeId !== row.worktreeId);
      },
      error: (err: HttpErrorResponse) => {
        this.removingId = null;
        this.removeErrors.set(row.worktreeId, err.error?.error ?? 'could not remove this worktree');
      },
    });
  }

  runCleanupNow(): void {
    if (this.cleaningUp) {
      return;
    }
    this.cleaningUp = true;
    this.cleanupError = false;
    this.cleanupMessage = null;
    this.worktreesService.runCleanupNow(this.projectId).subscribe({
      next: (result) => {
        this.cleaningUp = false;
        this.cleanupMessage =
          result.removed.length === 0
            ? 'nothing to clean up'
            : `removed ${result.removed.length} worktree${result.removed.length === 1 ? '' : 's'}`;
        this.load();
      },
      error: () => {
        this.cleaningUp = false;
        this.cleanupError = true;
      },
    });
  }
}
