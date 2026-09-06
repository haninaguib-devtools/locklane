import { HttpErrorResponse } from '@angular/common/http';
import { Component, Input, OnChanges, OnDestroy, OnInit, SimpleChanges, inject } from '@angular/core';
import { Subscription, merge } from 'rxjs';
import { ProjectWorktree, WorktreesService } from '../../services/worktrees.service';
import { OpenShell, ShellsService } from '../../services/shells.service';
import { ConsolesService } from '../../services/consoles.service';

/**
 * The project page's worktree list (#320): every worktree tied to the project's
 * issues, with a manual "remove worktree" per row and a page-level "run cleanup now"
 * button — so a human can directly verify the console button (#318) and the periodic
 * cleanup sweep (#319) are behaving as expected, and clear out a stray worktree
 * without waiting on the schedule. Both actions go through the engine's
 * {@code ProjectWorktreesController}, which applies the exact same safety guard as
 * the periodic sweep rather than a separate, potentially-drifting copy of it.
 *
 * Also lists the project's open shells (#733) — the standalone terminals opened from a
 * console tab's hover-revealed terminal icon, which otherwise have no home on the
 * project page even though the delete-project guard blocks on them same as a
 * worktree/console. `ShellsService.list()` has no per-project endpoint, so it is
 * filtered client-side, same as `ShellsSidenavComponent`'s own per-project grouping.
 */
@Component({
  selector: 'app-worktree-list',
  standalone: true,
  templateUrl: './worktree-list.component.html',
  styleUrl: './worktree-list.component.css',
})
export class WorktreeListComponent implements OnChanges, OnInit, OnDestroy {
  private readonly worktreesService = inject(WorktreesService);
  private readonly shellsService = inject(ShellsService);
  private readonly consolesService = inject(ConsolesService);

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

  shells: OpenShell[] = [];
  shellsLoading = true;
  shellsLoadError = false;
  closingShellId: string | null = null;
  closeShellErrors = new Map<string, string>();

  // A shell (or console) opened or closed anywhere reaches this page as `consolesChanged`
  // (#195; the shell endpoints broadcast it too, #445/#460) -- ConsolesService already
  // folds that, plus a reconnect, into `onOpened`/`onClosed` (the same signal
  // ConsoleIndicatorComponent reacts to), so this page's shell list stays live without
  // talking to EventsService directly.
  private readonly subscriptions = new Subscription();

  ngOnInit(): void {
    this.subscriptions.add(
      merge(this.consolesService.onOpened, this.consolesService.onClosed).subscribe(() => this.loadShells()),
    );
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['projectId']) {
      this.load();
      this.loadShells();
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

  private loadShells(): void {
    this.shellsLoading = true;
    this.shellsLoadError = false;
    this.shellsService.list().subscribe({
      next: (shells) => {
        this.shells = shells.filter((shell) => shell.projectId === this.projectId);
        this.shellsLoading = false;
      },
      error: () => {
        this.shellsLoading = false;
        this.shellsLoadError = true;
      },
    });
  }

  /** A shell's own name (#393) when it has one, otherwise its location. */
  shellLabel(shell: OpenShell): string {
    const name = shell.displayName?.trim();
    if (name) {
      return name;
    }
    return shell.mainCheckout ? 'main checkout' : `#${shell.issueNumber}`;
  }

  /** The singleton-window convention (#444): repeat calls with the same window name focus it. */
  openShell(shell: OpenShell): void {
    window.open(`/shells/${shell.sessionId}`, 'locklane-shells');
  }

  closeShell(shell: OpenShell): void {
    if (this.closingShellId) {
      return;
    }
    this.closingShellId = shell.sessionId;
    this.closeShellErrors.delete(shell.sessionId);
    this.shellsService.close(this.projectId, shell.sessionId).subscribe({
      next: () => {
        this.closingShellId = null;
        this.shells = this.shells.filter((s) => s.sessionId !== shell.sessionId);
      },
      error: (err: HttpErrorResponse) => {
        this.closingShellId = null;
        this.closeShellErrors.set(shell.sessionId, err.error?.error ?? 'could not close this shell');
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
