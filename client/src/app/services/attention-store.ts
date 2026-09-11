import { Injectable, OnDestroy, inject, signal } from '@angular/core';
import { Subscription, filter } from 'rxjs';
import { AgentSessionAttentionEvent, EventsService, isAgentSessionAttentionEvent } from './events.service';

/** Why a session is waiting (#854): a deliberate bell, or the quiet fallback. */
export type AttentionReason = 'bell' | 'quiet';

/**
 * Which agent sessions are currently waiting for the user (#130, #789): a bell, or
 * output gone quiet with no input since -- see dev.locklane.engine.pty.PtySession.
 *
 * The one place in the client that listens for `consoleAttention` events. The
 * sidenav's per-issue and per-project dots, the header "agents (N)" badge and the
 * agent tab strip's per-tab dot (#791) all read from here instead of each keeping a
 * private copy of the same set fed from the same events channel. The engine's
 * connect-time snapshot (#790) arrives as ordinary `consoleAttention` events, so a
 * page loaded after an agent already rang the bell is caught up through the same path.
 *
 * State is one signal holding the set of waiting session ids, and a second holding
 * each waiting session's reason (#854): readers that key by something other than the
 * session id (the sidenav, by issue or by project) walk {@link waiting} and classify
 * each id with the session-id parsers in `agent-sessions.service.ts`; readers that ask
 * about one id use {@link isWaiting} or {@link reason}. All are signal reads, so a
 * template binding re-evaluates as events land.
 */
@Injectable({ providedIn: 'root' })
export class AttentionStore implements OnDestroy {
  private readonly eventsService = inject(EventsService);
  private readonly waitingSignal = signal<ReadonlySet<string>>(new Set());
  private readonly reasonsSignal = signal<ReadonlyMap<string, AttentionReason>>(new Map());
  private readonly sub: Subscription;

  /** Every session id currently waiting for attention, as a read-only signal. */
  readonly waiting = this.waitingSignal.asReadonly();

  constructor() {
    this.sub = this.eventsService.events$
      .pipe(filter(isAgentSessionAttentionEvent))
      .subscribe((event) => this.apply(event));
  }

  ngOnDestroy(): void {
    this.sub.unsubscribe();
  }

  /** Whether this one session is waiting for the user. A signal read, like {@link waiting}. */
  isWaiting(sessionId: string): boolean {
    return this.waitingSignal().has(sessionId);
  }

  /**
   * Why this session is waiting, or `null` when it is not (#854). A signal read, like
   * {@link isWaiting} -- a template binding re-evaluates as the reason changes, e.g.
   * on the quiet-to-bell upgrade.
   */
  reason(sessionId: string): AttentionReason | null {
    return this.reasonsSignal().get(sessionId) ?? null;
  }

  /**
   * Applies one `consoleAttention` event by session id. A repeat of the current state
   * with the same reason (`waiting`/`bell` for a session already waiting for that
   * reason, `active` for one that is not waiting at all) changes nothing and does not
   * notify readers -- but a `waiting` event whose reason differs from what is already
   * recorded (the quiet-to-bell upgrade, #854) still updates {@link reason} even
   * though {@link isWaiting} stays true throughout.
   */
  apply(event: AgentSessionAttentionEvent): void {
    const currentWaiting = this.waitingSignal();
    const currentReasons = this.reasonsSignal();
    const wasWaiting = currentWaiting.has(event.sessionId);
    const previousReason = currentReasons.get(event.sessionId) ?? null;
    const nextReason = event.state === 'waiting' ? (event.reason ?? null) : null;
    const nextWaiting = event.state === 'waiting';
    if (wasWaiting === nextWaiting && previousReason === nextReason) {
      return;
    }
    if (wasWaiting !== nextWaiting) {
      const next = new Set(currentWaiting);
      if (nextWaiting) {
        next.add(event.sessionId);
      } else {
        next.delete(event.sessionId);
      }
      this.waitingSignal.set(next);
    }
    if (previousReason !== nextReason) {
      const next = new Map(currentReasons);
      if (nextReason === null) {
        next.delete(event.sessionId);
      } else {
        next.set(event.sessionId, nextReason);
      }
      this.reasonsSignal.set(next);
    }
  }
}
