import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, Subject } from 'rxjs';
import { GhIssue, IssueDetail, TreeNode } from '../models/issue.model';

// Nested under a project id since #43 -- issue data itself still comes from one
// shared repo for every project (see #43's task record), but every route requires
// the segment now.
@Injectable({ providedIn: 'root' })
export class IssuesService {
  private readonly http = inject(HttpClient);
  private readonly stale$ = new Subject<number>();

  list(projectId: number): Observable<GhIssue[]> {
    return this.http.get<GhIssue[]>(`/api/projects/${projectId}/issues`);
  }

  /**
   * `fresh` (#140) bypasses the engine's GhIssueCache for this one call -- for a
   * caller that just left a project-level console session where an agent may have
   * opened an issue via `gh`, and wants it to show up without waiting on the
   * engine's own 30s poll.
   */
  tree(projectId: number, fresh = false): Observable<TreeNode[]> {
    return this.http.get<TreeNode[]>(`/api/projects/${projectId}/issues/tree`, fresh ? { params: { fresh } } : {});
  }

  get(projectId: number, number: number): Observable<GhIssue> {
    return this.http.get<GhIssue>(`/api/projects/${projectId}/issues/${number}`);
  }

  detail(projectId: number, number: number): Observable<IssueDetail> {
    return this.http.get<IssueDetail>(`/api/projects/${projectId}/issues/${number}/detail`);
  }

  worktrees(projectId: number, number: number): Observable<string[]> {
    return this.http.get<string[]>(`/api/projects/${projectId}/issues/${number}/worktrees`);
  }

  startSession(
    projectId: number,
    number: number,
    worktree = true,
  ): Observable<{ worktreeId: string; workingDirectory: string }> {
    return this.http.post<{ worktreeId: string; workingDirectory: string }>(
      `/api/projects/${projectId}/issues/${number}/worktrees`,
      {},
      { params: { worktree } },
    );
  }

  /** Ends a session for good (#75) — kills it server-side, not just this tab's view of it. */
  closeSession(projectId: number, number: number, worktreeId: string): Observable<void> {
    return this.http.delete<void>(`/api/projects/${projectId}/issues/${number}/worktrees/${worktreeId}`);
  }

  /**
   * Fires when a caller wants a loaded view of a project's issue list re-fetched
   * with fresh data (#140) -- the sidenav, which owns the actual issue list,
   * subscribes to bust its own cached view of that project in place.
   */
  readonly onProjectStale = this.stale$.asObservable();

  notifyProjectStale(projectId: number): void {
    this.stale$.next(projectId);
  }
}
