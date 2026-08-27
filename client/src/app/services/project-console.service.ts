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
 * The project-level console session (#139/#140): one persistent agent session per
 * project, running in the project's own checkout rather than an issue worktree.
 */
@Injectable({ providedIn: 'root' })
export class ProjectConsoleService {
  private readonly http = inject(HttpClient);

  /** The project's console session, if one has actually been attached to before. 404 otherwise. */
  find(projectId: number): Observable<ProjectConsoleSession> {
    return this.http.get<ProjectConsoleSession>(`/api/projects/${projectId}/console`);
  }

  /** Mints (or reports) the project's console session id and working directory. */
  start(projectId: number): Observable<ProjectConsoleSession> {
    return this.http.post<ProjectConsoleSession>(`/api/projects/${projectId}/console`, {});
  }

  /** The project's open console sessions the caller may see, oldest first (#177). */
  listOpen(projectId: number): Observable<OpenProjectConsole[]> {
    return this.http.get<OpenProjectConsole[]>(`/api/projects/${projectId}/console/sessions`);
  }
}
