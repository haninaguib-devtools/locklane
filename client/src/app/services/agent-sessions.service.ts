import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, Subject, filter, map, merge } from 'rxjs';
import { EventsService, isAgentSessionsChangedEvent } from './events.service';

/**
 * What `open-ide` returns (#628, #781): the proxied code-server URL to open, or `null`
 * after the engine launched a desktop IDE on its own host -- nothing for the browser to open.
 */
export interface OpenedIde {
  url: string | null;
}

/** What minting the project's main-checkout IDE session returns (#831): the id to pass {@link AgentSessionsService.openIde}. */
export interface MainCheckoutIdeSession {
  sessionId: string;
}

@Injectable({ providedIn: 'root' })
export class AgentSessionsService {
  private readonly http = inject(HttpClient);
  private readonly eventsService = inject(EventsService);
  private readonly closed$ = new Subject<void>();
  private readonly opened$ = new Subject<void>();
  private readonly renamed$ = new Subject<void>();

  // An agent session opening or closing in another browser tab/session reaches this one
  // over the app-wide events channel (#195) as `consolesChanged`. Neither local
  // notify call below distinguishes open from close for its subscribers either
  // (both are folded into one merged trigger by every current consumer), so a
  // remote change is folded into both `onOpened` and `onClosed` the same way, plus
  // a reconnect (EventsService#reconnected$) in case a change was missed while the
  // socket was down.
  private readonly remoteOrReconnected$ = merge(
    this.eventsService.events$.pipe(filter(isAgentSessionsChangedEvent)),
    this.eventsService.reconnected$,
  ).pipe(map(() => undefined));

  /**
   * Every open agent session id the caller may see, across all of one project's
   * issues (#32) — nested under a project id since #43.
   */
  list(projectId: number): Observable<string[]> {
    // The /consoles REST path is a compatibility surface kept under ADR-112.
    return this.http.get<string[]>(`/api/projects/${projectId}/consoles`);
  }

  /**
   * Reveals an agent session's worktree in the local OS's file manager (#441). The engine
   * resolves the path server-side from the agent session id, so no path is ever sent here.
   */
  reveal(projectId: number, id: string): Observable<void> {
    // The /consoles REST path is a compatibility surface kept under ADR-112.
    return this.http.post<void>(`/api/projects/${projectId}/consoles/${id}/reveal-in-file-manager`, {});
  }

  /**
   * Opens an agent session's worktree in `ide` (#628, #782) -- an id from `GET /api/ides/installed`:
   * `code-server` starts (or reuses) a code-server process and returns its URL to open;
   * a desktop id makes the engine launch that editor on its own host and return no URL.
   * The engine resolves the working directory server-side from the agent session id, so no
   * path is ever sent here.
   */
  openIde(projectId: number, id: string, ide: string): Observable<OpenedIde> {
    // The /consoles REST path is a compatibility surface kept under ADR-112.
    return this.http.post<OpenedIde>(`/api/projects/${projectId}/consoles/${id}/open-ide`, { ide });
  }

  /**
   * Ensures the project's own main-checkout IDE session exists (#831) and reports its
   * id, for {@link openIde} to then open exactly as it does any other session -- the
   * project page's own "Open IDE" button, beside "Open shells", targets the project's
   * bare main checkout, never an issue or a project agent session. Idempotent: the
   * same session is reused across opens, unlike minting a shell.
   */
  openMainCheckoutIdeSession(projectId: number): Observable<MainCheckoutIdeSession> {
    return this.http.post<MainCheckoutIdeSession>(`/api/projects/${projectId}/consoles/main-checkout-ide`, {});
  }

  /**
   * Fires whenever an agent session is closed for good somewhere in the app (#75)
   * — including another browser tab or session (#195) — so the header indicator
   * can refresh its count without an unrelated reload.
   */
  readonly onClosed = merge(this.closed$, this.remoteOrReconnected$);

  notifyClosed(): void {
    this.closed$.next();
  }

  /**
   * Fires whenever a new agent session is opened somewhere in the app (#108) —
   * including another browser tab or session (#195) — so other views (the
   * sidebar's open-agent-session dot) can refresh without polling.
   */
  readonly onOpened = merge(this.opened$, this.remoteOrReconnected$);

  notifyOpened(): void {
    this.opened$.next();
  }

  /**
   * Fires whenever an agent session tab is renamed in this browser (#456), so the header
   * agent sessions widget can recompute its `Project - <tab text>` rows without a reload.
   * Local-only, unlike `onOpened`/`onClosed`: the events channel carries no rename
   * event, so a rename made in another browser/session stays invisible until the
   * next open/close or reload — a declared boundary of #456, not an oversight.
   */
  readonly onRenamed: Observable<void> = this.renamed$;

  notifyRenamed(): void {
    this.renamed$.next();
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
 * True for a project-level agent session's session id (#139/#177): the legacy
 * "<projectId>-console" shape or "<projectId>-console-<suffix>" -- mirrors the
 * engine's `ProjectAgentSessionService.AGENT_SESSION_ID`. These never match
 * `issueNumberFromSessionId`'s pattern, since their second segment is the
 * literal "console" (the persisted id shape, kept under ADR-112), never a number.
 */
export function isProjectAgentSessionId(sessionId: string): boolean {
  // The '-console' id segment is the persisted session id shape, kept under ADR-112.
  return /^\d+-console(-.+)?$/.test(sessionId);
}

/**
 * The owning project's id, parsed out of a project-level agent session's session id
 * (#450) -- the project-agent-session-aware counterpart of `projectIssueKeyFromSessionId`
 * below, for placing a `consoleAttention` event onto the right project row when its
 * session id carries no issue number.
 */
export function projectIdFromProjectAgentSessionId(sessionId: string): number | null {
  // The '-console' id segment is the persisted session id shape, kept under ADR-112.
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
