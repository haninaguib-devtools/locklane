import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, Subject, filter, map, merge } from 'rxjs';
import { EventsService, isConsolesChangedEvent } from './events.service';

@Injectable({ providedIn: 'root' })
export class ConsolesService {
  private readonly http = inject(HttpClient);
  private readonly eventsService = inject(EventsService);
  private readonly closed$ = new Subject<void>();
  private readonly opened$ = new Subject<void>();

  // A console opening or closing in another browser tab/session reaches this one
  // over the app-wide events channel (#195) as `consolesChanged`. Neither local
  // notify call below distinguishes open from close for its subscribers either
  // (both are folded into one merged trigger by every current consumer), so a
  // remote change is folded into both `onOpened` and `onClosed` the same way, plus
  // a reconnect (EventsService#reconnected$) in case a change was missed while the
  // socket was down.
  private readonly remoteOrReconnected$ = merge(
    this.eventsService.events$.pipe(filter(isConsolesChangedEvent)),
    this.eventsService.reconnected$,
  ).pipe(map(() => undefined));

  /**
   * Every open console session id the caller may see, across all of one project's
   * issues (#32) — nested under a project id since #43.
   */
  list(projectId: number): Observable<string[]> {
    return this.http.get<string[]>(`/api/projects/${projectId}/consoles`);
  }

  /**
   * Reveals a console's worktree in the local OS's file manager (#441). The engine
   * resolves the path server-side from the console id, so no path is ever sent here.
   */
  reveal(projectId: number, id: string): Observable<void> {
    return this.http.post<void>(`/api/projects/${projectId}/consoles/${id}/reveal-in-file-manager`, {});
  }

  /**
   * Fires whenever a console session is closed for good somewhere in the app (#75)
   * — including another browser tab or session (#195) — so the header indicator
   * can refresh its count without an unrelated reload.
   */
  readonly onClosed = merge(this.closed$, this.remoteOrReconnected$);

  notifyClosed(): void {
    this.closed$.next();
  }

  /**
   * Fires whenever a new console session is opened somewhere in the app (#108) —
   * including another browser tab or session (#195) — so other views (the
   * sidebar's open-console dot) can refresh without polling.
   */
  readonly onOpened = merge(this.opened$, this.remoteOrReconnected$);

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
 * The owning project's id, parsed out of a project-level console's session id
 * (#450) -- the project-console-aware counterpart of `projectIssueKeyFromSessionId`
 * below, for placing a `consoleAttention` event onto the right project row when its
 * session id carries no issue number.
 */
export function projectIdFromProjectConsoleSessionId(sessionId: string): number | null {
  const match = /^(\d+)-console(-.+)?$/.exec(sessionId);
  return match ? Number(match[1]) : null;
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
