import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** One row of the project page's worktree list (#320) — mirrors the engine's `WorktreeRow`. */
export interface ProjectWorktree {
  worktreeId: string;
  issueNumber: number;
  workingDirectory: string;
  clean: boolean;
  sessionAttached: boolean;
}

/** The engine's refusal body for a remove request the safety guard rejected (409). */
export interface WorktreeRemovalError {
  error: string;
}

/** What the on-demand cleanup trigger actually removed. */
export interface CleanupResult {
  removed: string[];
}

/**
 * The project page's worktree list, manual remove action, and on-demand cleanup
 * trigger (#320) — talks to {@code ProjectWorktreesController}, which applies the same
 * safety guard as the periodic sweep (#319) rather than a separate one.
 */
@Injectable({ providedIn: 'root' })
export class WorktreesService {
  private readonly http = inject(HttpClient);

  /** Every worktree tied to this project's issues. */
  list(projectId: number): Observable<ProjectWorktree[]> {
    return this.http.get<ProjectWorktree[]>(`/api/projects/${projectId}/worktrees`);
  }

  /**
   * Removes one worktree. The guard refuses (409, body `{error}`) for an open issue, a
   * dirty worktree, or one with a live session attached — the caller reads that body
   * off the thrown `HttpErrorResponse` and shows it verbatim.
   */
  remove(projectId: number, worktreeId: string): Observable<void> {
    return this.http.delete<void>(`/api/projects/${projectId}/worktrees/${worktreeId}`);
  }

  /** Runs the same sweep the schedule runs, right now. */
  runCleanupNow(projectId: number): Observable<CleanupResult> {
    return this.http.post<CleanupResult>(`/api/projects/${projectId}/worktrees/cleanup`, {});
  }
}
