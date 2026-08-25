import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { GhIssue, IssueDetail, TreeNode } from '../models/issue.model';

@Injectable({ providedIn: 'root' })
export class IssuesService {
  private readonly http = inject(HttpClient);

  list(): Observable<GhIssue[]> {
    return this.http.get<GhIssue[]>('/api/issues');
  }

  tree(): Observable<TreeNode[]> {
    return this.http.get<TreeNode[]>('/api/issues/tree');
  }

  get(number: number): Observable<GhIssue> {
    return this.http.get<GhIssue>(`/api/issues/${number}`);
  }

  detail(number: number): Observable<IssueDetail> {
    return this.http.get<IssueDetail>(`/api/issues/${number}/detail`);
  }

  worktrees(number: number): Observable<string[]> {
    return this.http.get<string[]>(`/api/issues/${number}/worktrees`);
  }

  startSession(
    number: number,
    worktree = true,
  ): Observable<{ worktreeId: string; workingDirectory: string }> {
    return this.http.post<{ worktreeId: string; workingDirectory: string }>(
      `/api/issues/${number}/worktrees`,
      {},
      { params: { worktree } },
    );
  }
}
