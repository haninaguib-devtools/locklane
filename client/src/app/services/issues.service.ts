import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { GhIssue, IssueDetail, TreeNode } from '../models/issue.model';

// Nested under a project id since #43 -- issue data itself still comes from one
// shared repo for every project (see #43's task record), but every route requires
// the segment now.
@Injectable({ providedIn: 'root' })
export class IssuesService {
  private readonly http = inject(HttpClient);

  list(projectId: number): Observable<GhIssue[]> {
    return this.http.get<GhIssue[]>(`/api/projects/${projectId}/issues`);
  }

  tree(projectId: number): Observable<TreeNode[]> {
    return this.http.get<TreeNode[]>(`/api/projects/${projectId}/issues/tree`);
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
}
