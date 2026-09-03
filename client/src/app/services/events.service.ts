import { Injectable, signal } from '@angular/core';
import { Observable, Subject } from 'rxjs';

/** One message off the app-wide events channel (dev.locklane.engine.ws.EventsWebSocketHandler, /ws/events, #128). */
export interface AppEvent {
  type: string;
  [key: string]: unknown;
}

/**
 * A `consoleAttention` message (#130): a console session started or stopped waiting
 * for the user (a bell, or output going quiet with no input since -- see
 * dev.locklane.engine.pty.PtySession). Shared here since both the sidenav's per-issue
 * dot and the header console indicator react to it.
 */
export interface ConsoleAttentionEvent extends AppEvent {
  type: 'consoleAttention';
  sessionId: string;
  state: 'waiting' | 'active';
}

export function isConsoleAttentionEvent(event: AppEvent): event is ConsoleAttentionEvent {
  return (
    event.type === 'consoleAttention' &&
    typeof event['sessionId'] === 'string' &&
    (event['state'] === 'waiting' || event['state'] === 'active')
  );
}

/**
 * A `consolesChanged` message (#195): a console session was opened or closed
 * somewhere in the app -- possibly a different browser tab or session watching the
 * same project's header widget. `projectId` names the affected project when the
 * originating session id could be parsed as one server-side; every real console id
 * can, so this is present in practice.
 */
export interface ConsolesChangedEvent extends AppEvent {
  type: 'consolesChanged';
  projectId?: number;
}

export function isConsolesChangedEvent(event: AppEvent): event is ConsolesChangedEvent {
  return event.type === 'consolesChanged';
}

/**
 * The engine's build stamp (#273), sent as the first message on every `/ws/events`
 * connection -- see `dev.locklane.engine.ws.EventsWebSocketHandler`. A different stamp
 * on reconnect than the one seen at boot means the engine was redeployed with a
 * possibly-changed client bundle.
 *
 * `release` (#467) is the human-readable version the engine is running
 * (`BuildProperties#getVersion()`, e.g. `0.1.0-SNAPSHOT`) -- display-only, never part
 * of the staleness comparison above. Optional so a client rolled out ahead of its
 * engine still recognizes the old one-field greeting.
 */
export interface EngineVersionEvent extends AppEvent {
  type: 'engineVersion';
  version: string;
  release?: string;
}

export function isEngineVersionEvent(event: AppEvent): event is EngineVersionEvent {
  return event.type === 'engineVersion' && typeof event['version'] === 'string';
}

/**
 * A newer permanent GitHub release than the one running exists (#287), sent on connect
 * once the engine already knows about one, and broadcast to every connected client the
 * moment it finds out. Purely informational -- unlike `engineVersion` above, this never
 * triggers an in-app update of any kind.
 *
 * `url` (#466) is that release's GitHub Releases page, for the banner to link to.
 * Optional so a client rolled out ahead of its engine still accepts the old
 * version-only payload -- the banner then falls back to plain text.
 */
export interface ReleaseAvailableEvent extends AppEvent {
  type: 'releaseAvailable';
  version: string;
  url?: string;
}

export function isReleaseAvailableEvent(event: AppEvent): event is ReleaseAvailableEvent {
  return (
    event.type === 'releaseAvailable' &&
    typeof event['version'] === 'string' &&
    (event['url'] === undefined || typeof event['url'] === 'string')
  );
}

/**
 * A `githubRefreshStatus` message (#619): the outcome of the engine's GitHub fetch for
 * a project moved -- it started failing, stopped failing, or is failing with different
 * text. Sent by the scheduled poll and by a forced refresh alike, so the sidenav
 * learns about a dead token without anyone clicking. `failure` and `lastSuccessAt`
 * are absent (not null) when unset.
 */
export interface GithubRefreshStatusEvent extends AppEvent {
  type: 'githubRefreshStatus';
  projectId: number;
  failing: boolean;
  failure?: string;
  lastSuccessAt?: string;
}

export function isGithubRefreshStatusEvent(event: AppEvent): event is GithubRefreshStatusEvent {
  return (
    event.type === 'githubRefreshStatus' &&
    typeof event['projectId'] === 'number' &&
    typeof event['failing'] === 'boolean' &&
    (event['failure'] === undefined || typeof event['failure'] === 'string') &&
    (event['lastSuccessAt'] === undefined || typeof event['lastSuccessAt'] === 'string')
  );
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
 * `reconnected$` exists so a consumer can trigger a full re-fetch to catch up on
 * whatever happened while the socket was down, rather than trusting the stream to
 * have delivered everything.
 */
@Injectable({ providedIn: 'root' })
export class EventsService {
  private socket: WebSocket | null = null;
  private backoffMs = INITIAL_BACKOFF_MS;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  // False until the first successful connect -- `reconnected$` fires on every
  // connect after that one, never on the first (there is nothing to catch up on yet).
  private everConnected = false;

  // The stamp from the first `engineVersion` message ever seen (#273) -- set once and
  // never overwritten, so every later message (one per reconnect) is compared against
  // what was running when this tab booted, not against the previous reconnect's stamp.
  private bootVersion: string | null = null;

  // The latest `engineVersion` greeting, as *state* rather than a stream (#595): the
  // engine sends it exactly once per connection, before anything else, and `events$`
  // does not replay -- so a consumer constructed after the socket opened (a lazily
  // injected service behind a dialog, say) would otherwise never see it. Every
  // connect and reconnect replaces it, so after the engine is upgraded mid-session
  // this is the new engine's greeting, not the one seen at boot.
  private readonly engineVersionSignal = signal<EngineVersionEvent | null>(null);

  private readonly eventsSubject = new Subject<AppEvent>();
  private readonly reconnectedSubject = new Subject<void>();
  private readonly versionChangedSubject = new Subject<void>();

  readonly events$: Observable<AppEvent> = this.eventsSubject.asObservable();
  /**
   * What the engine on the other end of the current connection last said about
   * itself (#273, #467): `null` until the first connection's greeting arrives, then
   * always the most recent greeting. Read this, not `events$`, for the running
   * version -- it is correct however late the reader is created.
   */
  readonly engineVersion = this.engineVersionSignal.asReadonly();
  readonly reconnected$: Observable<void> = this.reconnectedSubject.asObservable();
  /** Fires when a reconnect's `engineVersion` stamp differs from the one seen at boot. */
  readonly versionChanged$: Observable<void> = this.versionChangedSubject.asObservable();

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
        const parsed = JSON.parse(event.data) as AppEvent;
        if (isEngineVersionEvent(parsed)) {
          this.engineVersionSignal.set(parsed);
          if (this.bootVersion === null) {
            this.bootVersion = parsed.version;
          } else if (parsed.version !== this.bootVersion) {
            this.versionChangedSubject.next();
          }
        }
        this.eventsSubject.next(parsed);
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
