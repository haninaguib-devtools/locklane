import { Injectable, OnDestroy, signal } from '@angular/core';
import { Observable, Subject } from 'rxjs';

/** One message off the app-wide events channel (dev.locklane.engine.ws.EventsWebSocketHandler, /ws/events, #128). */
export interface AppEvent {
  type: string;
  [key: string]: unknown;
}

/**
 * A `consoleAttention` message (#130): an agent session started or stopped waiting
 * for the user (a bell, or output going quiet with no input since -- see
 * dev.locklane.engine.pty.PtySession). Shared here since both the sidenav's per-issue
 * dot and the header agent session indicator react to it.
 */
export interface AgentSessionAttentionEvent extends AppEvent {
  type: 'consoleAttention';
  sessionId: string;
  state: 'waiting' | 'active';
}

export function isAgentSessionAttentionEvent(event: AppEvent): event is AgentSessionAttentionEvent {
  return (
    event.type === 'consoleAttention' &&
    typeof event['sessionId'] === 'string' &&
    (event['state'] === 'waiting' || event['state'] === 'active')
  );
}

/**
 * A `consolesChanged` message (#195): an agent session was opened or closed
 * somewhere in the app -- possibly a different browser tab or session watching the
 * same project's header widget. `projectId` names the affected project when the
 * originating session id could be parsed as one server-side; every real agent session id
 * can, so this is present in practice.
 */
export interface AgentSessionsChangedEvent extends AppEvent {
  type: 'consolesChanged';
  projectId?: number;
}

export function isAgentSessionsChangedEvent(event: AppEvent): event is AgentSessionsChangedEvent {
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
 *
 * `heartbeatIntervalMs` (#762) is how often the engine sends its `heartbeat` message
 * on this connection -- what `EventsService`'s own liveness check counts against, so
 * the two sides agree on the interval without the client hardcoding one. Optional for
 * the same reason as `release`; an engine that does not send it gets no client-side
 * liveness check, exactly the behaviour before #762.
 *
 * `releaseUrl` (#799) is the running release's own GitHub Releases page, for the About
 * dialog to link the version to -- absent for a `-SNAPSHOT` build (no page exists) and
 * for an engine too old to send it, both of which the dialog renders as plain text.
 */
export interface EngineVersionEvent extends AppEvent {
  type: 'engineVersion';
  version: string;
  release?: string;
  heartbeatIntervalMs?: number;
  releaseUrl?: string;
}

export function isEngineVersionEvent(event: AppEvent): event is EngineVersionEvent {
  return (
    event.type === 'engineVersion' &&
    typeof event['version'] === 'string' &&
    (event['heartbeatIntervalMs'] === undefined || typeof event['heartbeatIntervalMs'] === 'number') &&
    (event['releaseUrl'] === undefined || typeof event['releaseUrl'] === 'string')
  );
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

/**
 * A `projectStatus` message (#721): a project's clone reached `READY` or `FAILED` --
 * broadcast from `ProjectCheckoutService`'s one choke point per transition, so the
 * sidenav, the project agent session page, and the overview all learn a clone settled the
 * moment it does, instead of re-polling every few seconds until it does.
 * `defaultBranch` is present only for `READY` (#582's per-project trunk).
 */
export interface ProjectStatusEvent extends AppEvent {
  type: 'projectStatus';
  projectId: number;
  status: 'READY' | 'FAILED';
  defaultBranch?: string;
}

export function isProjectStatusEvent(event: AppEvent): event is ProjectStatusEvent {
  return (
    event.type === 'projectStatus' &&
    typeof event['projectId'] === 'number' &&
    (event['status'] === 'READY' || event['status'] === 'FAILED') &&
    (event['defaultBranch'] === undefined || typeof event['defaultBranch'] === 'string')
  );
}

/**
 * A `projectCreated` message (#760): a project row was just inserted -- broadcast from
 * `ProjectCheckoutService` on both creation paths (importing an existing repository,
 * creating a new one) the moment the row exists, before its clone even starts, so it
 * always precedes that project's first `projectStatus`. The window that created the
 * project already reloads to reveal it; this is how every *other* open window learns
 * the project exists, so the `projectStatus` and `issuesChanged` events that follow
 * have a row to land on there too.
 */
export interface ProjectCreatedEvent extends AppEvent {
  type: 'projectCreated';
  projectId: number;
}

export function isProjectCreatedEvent(event: AppEvent): event is ProjectCreatedEvent {
  return event.type === 'projectCreated' && typeof event['projectId'] === 'number';
}

/**
 * A `projectDeleted` message (#721, absorbed from #720): a project was deleted --
 * broadcast from both `ProjectCheckoutService#delete` and `#forceDelete`, so a project
 * removed out of band (another tab, the API, a cascade-deleted account) drops out of
 * every open surface without waiting on some other reload to notice it is gone.
 */
export interface ProjectDeletedEvent extends AppEvent {
  type: 'projectDeleted';
  projectId: number;
}

export function isProjectDeletedEvent(event: AppEvent): event is ProjectDeletedEvent {
  return event.type === 'projectDeleted' && typeof event['projectId'] === 'number';
}

/**
 * The type of the engine's application-level liveness message (#762), sent to every
 * connection on each of its heartbeat ticks (`EventsWebSocketHandler`). Carries no
 * other fields, and is consumed entirely inside `EventsService` -- it never reaches
 * `events$`, so no consumer has to know it exists.
 */
const HEARTBEAT_TYPE = 'heartbeat';

const INITIAL_BACKOFF_MS = 1000;
const MAX_BACKOFF_MS = 30000;
/**
 * How many heartbeat intervals may pass with nothing received before the connection is
 * treated as dead (#762) -- the same allowance the engine gives a connection that stops
 * answering pongs (`TerminalHeartbeat.MISSED_PONGS_BEFORE_CLOSE`): one late heartbeat is
 * just a loaded engine or a slow network, two in a row is a socket that will never
 * deliver anything again.
 */
const MISSED_HEARTBEATS_BEFORE_RECONNECT = 2;

/**
 * Owns the single connection to the app-wide events channel (#128) -- separate from
 * each agent session tab's own terminal socket (terminal-session.ts). Connected once at app
 * start (see the `provideAppInitializer` in app.config.ts); reconnects on its own with
 * exponential backoff after a drop, since the engine may restart or a laptop may sleep
 * mid-session.
 *
 * The engine pings this connection on a schedule and closes it if it ever misses two
 * pongs in a row (`EventsWebSocketHandler`, #665) -- but that alone still leaves a gap
 * up to twice that interval before a dead connection (a proxy idle timeout, laptop
 * sleep, or a throttled background tab silently dropping it) produces a `close` event
 * to reconnect on. `checkConnection()` closes that gap the moment a human is actually
 * looking again, the same way `TerminalSession.checkConnection()` /
 * `TerminalComponent.checkConnectionOnForeground` already do for each agent session tab's
 * own socket (#279).
 *
 * Neither of those helps when the engine's close never arrives at all (#762): behind a
 * proxy the browser's TCP connection and the engine's are separate legs, so when the
 * browser's leg dies (laptop sleep, a network change) the engine closes its own side
 * and this socket stays OPEN forever, delivering nothing. Browsers never tell
 * JavaScript about protocol-level pings, so the engine also sends a `heartbeat` text
 * message every `heartbeatIntervalMs` (learned from the `engineVersion` greeting), and
 * this service remembers when it last received *any* message. Once more than
 * `MISSED_HEARTBEATS_BEFORE_RECONNECT` intervals pass with nothing -- noticed by a timer
 * that runs every interval, or by `checkConnection()` on returning to the foreground --
 * it closes the socket itself and reconnects immediately, which fires `reconnected$`
 * like any other reconnect. Every such decision compares timestamps, never counts
 * timer firings: a background tab's timer may run late or not at all, and a late
 * firing must still reach the right answer from how much real time has passed.
 *
 * `reconnected$` exists so a consumer can trigger a full re-fetch to catch up on
 * whatever happened while the socket was down, rather than trusting the stream to
 * have delivered everything.
 */
@Injectable({ providedIn: 'root' })
export class EventsService implements OnDestroy {
  private socket: WebSocket | null = null;
  private backoffMs = INITIAL_BACKOFF_MS;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  // False until the first successful connect -- `reconnected$` fires on every
  // connect after that one, never on the first (there is nothing to catch up on yet).
  private everConnected = false;
  // Guards against attaching the foreground listeners more than once across repeated
  // connect() calls (#665) -- connect() itself is already idempotent about the socket,
  // but that check returns early on a live socket, before this would otherwise run.
  private foregroundListenersAttached = false;

  // The liveness check (#762): the interval the engine named in its greeting (null
  // until one has -- an engine that never does gets no check at all), the timer that
  // runs the check once per interval, and when the current socket last delivered
  // anything at all. The interval is kept across reconnects, so a reconnected socket
  // is watched from the moment it opens, before its own greeting arrives.
  private heartbeatIntervalMs: number | null = null;
  private livenessTimer: ReturnType<typeof setInterval> | null = null;
  private lastMessageAt = 0;

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
    this.attachForegroundListeners();
    if (this.socket) {
      return;
    }
    this.open();
  }

  private attachForegroundListeners(): void {
    if (this.foregroundListenersAttached) {
      return;
    }
    this.foregroundListenersAttached = true;
    document.addEventListener('visibilitychange', this.checkConnectionOnForeground);
    window.addEventListener('focus', this.checkConnectionOnForeground);
  }

  // `visibilitychange` fires on the document (and can fire while a background tab
  // stays unfocused, e.g. a whole window minimizing); `focus` fires on the window but
  // can also fire while still hidden (a devtools panel taking it without the page
  // itself becoming visible) -- `visibilityState` re-checks that either way, mirroring
  // TerminalComponent.checkConnectionOnForeground (#279). Named rather than inline so
  // ngOnDestroy's removeEventListener below actually matches.
  private readonly checkConnectionOnForeground = (): void => {
    if (document.visibilityState === 'visible') {
      this.checkConnection();
    }
  };

  /**
   * This service is a root singleton the running app never destroys -- but a test
   * that constructs one directly (bypassing Angular's injector) needs a way to detach
   * its listeners afterwards, or a later test's own dispatch would also reconnect this
   * one's now-orphaned socket (#665).
   */
  ngOnDestroy(): void {
    document.removeEventListener('visibilitychange', this.checkConnectionOnForeground);
    window.removeEventListener('focus', this.checkConnectionOnForeground);
    if (this.livenessTimer !== null) {
      clearInterval(this.livenessTimer);
      this.livenessTimer = null;
    }
  }

  /**
   * Re-checks the connection when the tab regains focus/visibility (#665): if it is
   * not currently open, reconnect right away instead of waiting out whatever backoff
   * delay is pending -- mirrors `TerminalSession.checkConnection()` (#279). The whole
   * point of watching for this is to catch up the moment the user comes back, not
   * after a timer that may itself have been throttled while the tab was backgrounded.
   *
   * An OPEN socket is not taken at its word (#762): if it has delivered nothing for
   * more than `MISSED_HEARTBEATS_BEFORE_RECONNECT` heartbeat intervals -- a laptop that
   * just woke from a long sleep, typically -- it is dead on the far side, and it is
   * closed and replaced right here rather than waiting for the liveness timer's next
   * run. A socket still connecting is left alone either way.
   */
  checkConnection(): void {
    const state = this.socket?.readyState;
    if (state === WebSocket.CONNECTING) {
      return;
    }
    if (state === WebSocket.OPEN && !this.isStale()) {
      return;
    }
    this.reconnectNow();
  }

  /**
   * True once the current socket has gone more than the allowed number of heartbeat
   * intervals without delivering any message (#762). Always a comparison of real
   * timestamps -- never a count of how many times the liveness timer has fired, so a
   * timer that ran late (or not at all) in a throttled background tab still decides
   * correctly from the time that actually passed. False until the engine has named
   * its interval.
   */
  private isStale(): boolean {
    return (
      this.heartbeatIntervalMs !== null &&
      Date.now() - this.lastMessageAt > this.heartbeatIntervalMs * MISSED_HEARTBEATS_BEFORE_RECONNECT
    );
  }

  // The liveness timer's own check (#762): only an OPEN socket can be silently dead --
  // a closed one already reconnects through `onclose`, and a connecting one is not
  // expected to have delivered anything yet.
  private readonly checkLiveness = (): void => {
    if (this.socket?.readyState === WebSocket.OPEN && this.isStale()) {
      this.reconnectNow();
    }
  };

  /**
   * Starts (or restarts, if the engine now names a different interval) the timer that
   * runs `checkLiveness` once per heartbeat interval (#762). Once per interval is
   * enough: the check judges elapsed time, so the decision lands on the first run
   * after the allowance is exceeded regardless of how often the timer manages to fire.
   */
  private armLivenessCheck(intervalMs: number): void {
    if (!(intervalMs > 0)) {
      return;
    }
    if (this.livenessTimer !== null && this.heartbeatIntervalMs === intervalMs) {
      return;
    }
    if (this.livenessTimer !== null) {
      clearInterval(this.livenessTimer);
    }
    this.heartbeatIntervalMs = intervalMs;
    this.livenessTimer = setInterval(this.checkLiveness, intervalMs);
  }

  /**
   * Drops whatever socket is current -- a dead-but-OPEN one (#762), or one already
   * closed or closing -- and opens a fresh one immediately, with the backoff reset and
   * any pending backoff reconnect cancelled. The old socket's handlers are detached
   * first: on a dead connection the browser may take a long time to complete the close
   * it was asked for, and its eventual `close` event must not be mistaken for the new
   * socket's.
   */
  private reconnectNow(): void {
    const stale = this.socket;
    this.socket = null;
    if (stale) {
      stale.onopen = null;
      stale.onmessage = null;
      stale.onclose = null;
      stale.close();
    }
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.backoffMs = INITIAL_BACKOFF_MS;
    this.open();
  }

  private open(): void {
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    const socket = new WebSocket(`${proto}://${location.host}/ws/events`);
    this.socket = socket;
    // The liveness allowance starts from here (#762): a socket that opens but then
    // delivers nothing -- not even its greeting -- is as dead as one that went quiet
    // later, and must be judged from when it started, not from the previous socket's
    // last message.
    this.lastMessageAt = Date.now();

    socket.onopen = () => {
      if (this.socket !== socket) {
        return;
      }
      this.lastMessageAt = Date.now();
      this.backoffMs = INITIAL_BACKOFF_MS;
      if (this.everConnected) {
        this.reconnectedSubject.next();
      }
      this.everConnected = true;
    };

    socket.onmessage = (event: MessageEvent<string>) => {
      if (this.socket !== socket) {
        return;
      }
      // Any message at all proves the connection is alive (#762) -- recorded before
      // parsing, so even a malformed one counts.
      this.lastMessageAt = Date.now();
      try {
        const parsed = JSON.parse(event.data) as AppEvent;
        if (parsed.type === HEARTBEAT_TYPE) {
          // Consumed entirely by the timestamp above; no consumer ever sees it.
          return;
        }
        if (isEngineVersionEvent(parsed)) {
          this.engineVersionSignal.set(parsed);
          if (this.bootVersion === null) {
            this.bootVersion = parsed.version;
          } else if (parsed.version !== this.bootVersion) {
            this.versionChangedSubject.next();
          }
          if (parsed.heartbeatIntervalMs !== undefined) {
            this.armLivenessCheck(parsed.heartbeatIntervalMs);
          }
        }
        this.eventsSubject.next(parsed);
      } catch {
        // Not valid JSON -- nothing productive to do with a malformed event.
      }
    };

    // A network error is always followed by the close event per the WebSocket spec,
    // so scheduling the reconnect there alone covers both cases. A socket this service
    // already replaced (#762's reconnectNow detaches this handler, but a close racing
    // that detachment can still land) never speaks for the current one.
    socket.onclose = () => {
      if (this.socket !== socket) {
        return;
      }
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
