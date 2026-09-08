import { Injectable, OnDestroy, inject, signal } from '@angular/core';
import { Subscription, filter } from 'rxjs';
import { AgentSessionAttentionEvent, EventsService, isAgentSessionAttentionEvent } from './events.service';

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
 * State is one signal holding the set of waiting session ids: readers that key by
 * something other than the session id (the sidenav, by issue or by project) walk
 * {@link waiting} and classify each id with the session-id parsers in
 * `agent-sessions.service.ts`; readers that ask about one id use {@link isWaiting}. Both are
 * signal reads, so a template binding re-evaluates as events land.
 */
@Injectable({ providedIn: 'root' })
export class AttentionStore implements OnDestroy {
  private readonly eventsService = inject(EventsService);
  private readonly waitingSignal = signal<ReadonlySet<string>>(new Set());
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
   * Applies one `consoleAttention` event by session id. A repeat of the current state
   * (`waiting` for an id already waiting, `active` for one that is not) changes
   * nothing and does not notify readers.
   */
  apply(event: AgentSessionAttentionEvent): void {
    const current = this.waitingSignal();
    const isWaiting = current.has(event.sessionId);
    if (event.state === 'waiting' ? isWaiting : !isWaiting) {
      return;
    }
    const next = new Set(current);
    if (event.state === 'waiting') {
      next.add(event.sessionId);
    } else {
      next.delete(event.sessionId);
    }
    this.waitingSignal.set(next);
  }
}
