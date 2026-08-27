import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface ProjectConsoleSession {
  sessionId: string;
  workingDirectory: string;
}

/** One row of the open-consoles list — mirrors the engine's OpenConsoleView (#177). */
export interface OpenProjectConsole {
  sessionId: string;
  workingDirectory: string;
  createdAt: string;
  lastAttachedAt: string;
}

/**
 * Project-level console sessions (#139/#140): persistent agent sessions running in the
 * project's own checkout rather than an issue worktree. Since #177 a project can have
 * several open at once — start mints a fresh id every call, sessions lists the open
 * ones, and close ends one specific console.
 */
@Injectable({ providedIn: 'root' })
export class ProjectConsoleService {
  private readonly http = inject(HttpClient);

  /** The project's open console sessions, oldest first. */
  sessions(projectId: number): Observable<OpenProjectConsole[]> {
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
