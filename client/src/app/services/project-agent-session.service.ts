import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ResumeSession } from '../models/issue.model';

export interface ProjectAgentSession {
  sessionId: string;
  workingDirectory: string;
}

/** One open agent session from the list endpoint (#177); timestamps are ISO-8601 instants. */
export interface OpenProjectAgentSession {
  sessionId: string;
  workingDirectory: string;
  createdAt: string;
  lastAttachedAt: string;
  /**
   * The name the user gave this agent session's tab (#393); null when they gave it none.
   * Optional so a response from an engine that predates the rename endpoint still
   * types as an open agent session rather than failing to parse.
   */
  displayName?: string | null;
}

/**
 * Project-level agent sessions (#139/#140): persistent agent sessions with no issue
 * of their own. Since #314 each one runs in its own freshly created git worktree
 * rather than sharing the project's checkout; `workingDirectory` is whatever the
 * engine reports, opaque to callers. Since #177 a project can have several open at
 * once — start mints a fresh id every call, listOpen lists the open ones, and close
 * ends one specific agent session (never removing its worktree — that cleanup is deferred).
 * Since #372 it also reads the project's past conversations and reopens one, the same
 * two calls `IssuesService` already makes for an issue's own agent sessions.
 */
@Injectable({ providedIn: 'root' })
export class ProjectAgentSessionService {
  private readonly http = inject(HttpClient);

  /** The project's open agent sessions the caller may see, oldest first (#177). */
  listOpen(projectId: number): Observable<OpenProjectAgentSession[]> {
    // The /console REST path is a compatibility surface kept under ADR-112.
    return this.http.get<OpenProjectAgentSession[]>(`/api/projects/${projectId}/console/sessions`);
  }

  /** Mints a brand-new agent session id and reports its working directory. */
  start(projectId: number): Observable<ProjectAgentSession> {
    // The /console REST path is a compatibility surface kept under ADR-112.
    return this.http.post<ProjectAgentSession>(`/api/projects/${projectId}/console`, {});
  }

  /**
   * Past Claude/Codex/OpenCode conversations captured in this project's agent sessions
   * (#372), newest first — including conversations whose agent session has since closed.
   */
  resumeSessions(projectId: number): Observable<ResumeSession[]> {
    // The /console REST path is a compatibility surface kept under ADR-112.
    return this.http.get<ResumeSession[]>(`/api/projects/${projectId}/console/resume-sessions`);
  }

  /**
   * Mints a brand-new agent session resuming a past conversation (#372), in the
   * working directory of the agent session (`from`) it was captured in — the resume command
   * itself is passed when attaching, like any other new agent session's cmd.
   */
  reopenSession(projectId: number, from: string): Observable<ProjectAgentSession> {
    return this.http.post<ProjectAgentSession>(
      // The /console REST path is a compatibility surface kept under ADR-112.
      `/api/projects/${projectId}/console/resume-sessions/reopen`,
      {},
      { params: { from } },
    );
  }

  /**
   * Names one agent session's tab, or clears the name with an empty string (#393). The name
   * is stored by the engine against the session, so it comes back after a reload and
   * shows the same in any browser.
   */
  rename(projectId: number, sessionId: string, name: string): Observable<void> {
    // The /console REST path is a compatibility surface kept under ADR-112.
    return this.http.put<void>(`/api/projects/${projectId}/console/${sessionId}/name`, { name });
  }

  /** Ends one specific agent session for good. */
  close(projectId: number, sessionId: string): Observable<void> {
    // The /console REST path is a compatibility surface kept under ADR-112.
    return this.http.delete<void>(`/api/projects/${projectId}/console/${sessionId}`);
  }
}
