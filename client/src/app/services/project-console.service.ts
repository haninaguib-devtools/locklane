import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface ProjectConsoleSession {
  sessionId: string;
  workingDirectory: string;
}

/** One open console from the list endpoint (#177); timestamps are ISO-8601 instants. */
export interface OpenProjectConsole {
  sessionId: string;
  workingDirectory: string;
  createdAt: string;
  lastAttachedAt: string;
}

/**
 * Project-level console sessions (#139/#140): persistent agent sessions with no issue
 * of their own. Since #314 each one runs in its own freshly created git worktree
 * rather than sharing the project's checkout; `workingDirectory` is whatever the
 * engine reports, opaque to callers. Since #177 a project can have several open at
 * once — start mints a fresh id every call, listOpen lists the open ones, and close
 * ends one specific console (never removing its worktree — that cleanup is deferred).
 */
@Injectable({ providedIn: 'root' })
export class ProjectConsoleService {
  private readonly http = inject(HttpClient);

  /** The project's open console sessions the caller may see, oldest first (#177). */
  listOpen(projectId: number): Observable<OpenProjectConsole[]> {
    return this.http.get<OpenProjectConsole[]>(`/api/projects/${projectId}/console/sessions`);
  }

  /** Mints a brand-new console session id and reports its working directory. */
  start(projectId: number): Observable<ProjectConsoleSession> {
    return this.http.post<ProjectConsoleSession>(`/api/projects/${projectId}/console`, {});
  }

  /** Ends one specific console session for good. */
  close(projectId: number, sessionId: string): Observable<void> {
    return this.http.delete<void>(`/api/projects/${projectId}/console/${sessionId}`);
  }
}
