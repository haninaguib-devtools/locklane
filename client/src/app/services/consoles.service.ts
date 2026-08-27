import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, Subject } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class ConsolesService {
  private readonly http = inject(HttpClient);
  private readonly closed$ = new Subject<void>();
  private readonly opened$ = new Subject<void>();

  /**
   * Every open console session id the caller may see, across all of one project's
   * issues (#32) — nested under a project id since #43.
   */
  list(projectId: number): Observable<string[]> {
    return this.http.get<string[]>(`/api/projects/${projectId}/consoles`);
  }

  /**
   * Fires whenever a console session is closed for good somewhere in the app (#75),
   * so the header indicator can refresh its count without an unrelated reload.
   */
  readonly onClosed = this.closed$.asObservable();

  notifyClosed(): void {
    this.closed$.next();
  }

  /**
   * Fires whenever a new console session is opened somewhere in the app (#108), so
   * other views (the sidebar's open-console dot) can refresh without polling.
   */
  readonly onOpened = this.opened$.asObservable();

  notifyOpened(): void {
    this.opened$.next();
  }
}

/**
 * Session ids are shaped "<projectId>-<issueNumber>-<slug>" (#43) -- the second
 * numeric segment is the issue number.
 */
export function issueNumberFromSessionId(sessionId: string): number | null {
  const match = /^\d+-(\d+)-/.exec(sessionId);
  return match ? Number(match[1]) : null;
}

/**
 * True for a project-level console's session id (#139/#177): the legacy
 * "<projectId>-console" shape or "<projectId>-console-<suffix>" -- mirrors the
 * engine's `ProjectConsoleService.CONSOLE_SESSION_ID`. These never match
 * `issueNumberFromSessionId`'s pattern, since their second segment is the
 * literal "console", never a number.
 */
export function isProjectConsoleSessionId(sessionId: string): boolean {
  return /^\d+-console(-.+)?$/.test(sessionId);
}

/**
 * The "<projectId>:<issueNumber>" key the sidenav indexes its per-issue state by
 * (#108), parsed straight out of a session id (#43) -- used to place a `consoleAttention`
 * event (#130), which carries only a session id, onto the right issue row.
 */
export function projectIssueKeyFromSessionId(sessionId: string): string | null {
  const match = /^(\d+)-(\d+)-/.exec(sessionId);
  return match ? `${match[1]}:${match[2]}` : null;
}
