import { Injectable } from '@angular/core';
import { Observable, Subject } from 'rxjs';

/** One message off the app-wide events channel (dev.locklane.engine.ws.EventsWebSocketHandler, /ws/events, #128). */
export interface AppEvent {
  type: string;
  [key: string]: unknown;
}

const INITIAL_BACKOFF_MS = 1000;
const MAX_BACKOFF_MS = 30000;

/**
 * Owns the single connection to the app-wide events channel (#128) -- separate from
 * each console tab's own terminal socket (terminal-session.ts). Connected once at app
 * start (see the `provideAppInitializer` in app.config.ts); reconnects on its own with
 * exponential backoff after a drop, since the engine may restart or a laptop may sleep
 * mid-session.
 *
 * No consumer is wired to `events$` yet -- that is #129/#130. `reconnected$` exists so
 * a future consumer can trigger a full re-fetch to catch up on whatever happened while
 * the socket was down, rather than trusting the stream to have delivered everything.
 */
@Injectable({ providedIn: 'root' })
export class EventsService {
  private socket: WebSocket | null = null;
  private backoffMs = INITIAL_BACKOFF_MS;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  // False until the first successful connect -- `reconnected$` fires on every
  // connect after that one, never on the first (there is nothing to catch up on yet).
  private everConnected = false;

  private readonly eventsSubject = new Subject<AppEvent>();
  private readonly reconnectedSubject = new Subject<void>();

  readonly events$: Observable<AppEvent> = this.eventsSubject.asObservable();
  readonly reconnected$: Observable<void> = this.reconnectedSubject.asObservable();

  /** Opens the connection. Idempotent -- a second call while already open/connecting is a no-op. */
  connect(): void {
    if (this.socket) {
      return;
    }
    this.open();
  }

  private open(): void {
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    const socket = new WebSocket(`${proto}://${location.host}/ws/events`);
    this.socket = socket;

    socket.onopen = () => {
      this.backoffMs = INITIAL_BACKOFF_MS;
      if (this.everConnected) {
        this.reconnectedSubject.next();
      }
      this.everConnected = true;
    };

    socket.onmessage = (event: MessageEvent<string>) => {
      try {
        this.eventsSubject.next(JSON.parse(event.data) as AppEvent);
      } catch {
        // Not valid JSON -- nothing productive to do with a malformed event.
      }
    };

    // A network error is always followed by the close event per the WebSocket spec,
    // so scheduling the reconnect there alone covers both cases.
    socket.onclose = () => {
      this.socket = null;
      this.scheduleReconnect();
    };
  }

  private scheduleReconnect(): void {
    if (this.reconnectTimer) {
      return;
    }
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.open();
    }, this.backoffMs);
    this.backoffMs = Math.min(this.backoffMs * 2, MAX_BACKOFF_MS);
  }
}
