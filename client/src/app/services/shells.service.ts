import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/**
 * One open shell session (#445): a plain shell — not an agent CLI — at an issue's
 * worktree (`issueNumber` set, `mainCheckout` false) or at the project's own main
 * checkout (`issueNumber` null, `mainCheckout` true). `displayName` is the name the
 * user gave it (#393), null for the auto-generated label.
 */
export interface OpenShell {
  sessionId: string;
  projectId: number;
  issueNumber: number | null;
  mainCheckout: boolean;
  workingDirectory: string;
  createdAt: string;
  lastAttachedAt: string;
  displayName: string | null;
}

/**
 * Shell-kind console sessions over REST (#445/#460) — the cross-project listing the
 * Shells window's sidenav renders, and the per-session close. Attaching to one is
 * not here: the terminal component speaks the same WebSocket pipeline every console
 * uses, with `cmd=shell`.
 */
@Injectable({ providedIn: 'root' })
export class ShellsService {
  private readonly http = inject(HttpClient);

  /** Every open shell the caller may see, across all projects, oldest first. */
  list(): Observable<OpenShell[]> {
    return this.http.get<OpenShell[]>('/api/shells');
  }

  /** Ends one shell for good (#460) — kills the process and forgets the session. */
  close(projectId: number, sessionId: string): Observable<void> {
    return this.http.delete<void>(`/api/projects/${projectId}/shells/${sessionId}`);
  }
}
