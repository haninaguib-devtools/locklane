// A thin wrapper around one console session's WebSocket connection
// (dev.locklane.engine.ws.TerminalWebSocketHandler, /ws/sessions/{sessionId}).
// Not an Angular service: each terminal tab owns its own instance and its own
// connection, so this is plain state a component creates and destroys directly.
//
// Every message this class sends is tagged with a one-character type prefix the
// server's TerminalWebSocketHandler expects (#62): '0' for keystroke input, '1' for
// a resize, '2' for a focus notification (#130, carries no body). Keystrokes are
// never sent untagged, so the tag is never confused with something the user typed.
const INPUT = '0';
const RESIZE = '1';
const FOCUS = '2';

// Reconnect backoff (#279) — same shape as EventsService's, tuned for a per-tab
// terminal socket rather than the one shared events channel.
const INITIAL_BACKOFF_MS = 1000;
const MAX_BACKOFF_MS = 30000;

export class TerminalSession {
  private socket: WebSocket | null = null;
  /* The terminal's current size, held as state rather than observed as an edge (#376).
     These used to be readonly constructor arguments, so every reconnect URL advertised
     whatever size the tab had when its component was built, and the only other channel
     was xterm's onResize -- which fires when the size *changes*, and never fires again
     once the browser terminal is already correct. After a reconnect the client then
     asserted no size at all, and an engine restart (which makes the reattach create a
     fresh PTY) left that PTY at the stale URL size forever. Updated on every resize(),
     put on every connect URL, and sent on every socket open, so the engine is told the
     truth on each attach instead of only the first. */
  private cols: number | null;
  private rows: number | null;
  // Whether the tab this session belongs to is currently the focused one (#130) —
  // tracked so a reconnect can resend the focus notification, since the engine only
  // learns it from this connection's own messages and a reattach starts blank.
  private isFocused: boolean;
  // True once close() has been called deliberately (tab torn down) — distinguishes
  // that from an unexpected drop, so a reconnect is never scheduled after one (#279).
  private closedByClient = false;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private backoffMs = INITIAL_BACKOFF_MS;
  private onMessage: ((text: string) => void) | null = null;
  private onClose: (() => void) | null = null;

  constructor(
    private readonly sessionId: string,
    private readonly dir: string | null,
    private readonly cmd: string | null = null,
    // A past conversation to resume (#103) — only meaningful with a claude/codex
    // cmd on a brand-new session; the server composes the actual resume command.
    private readonly resume: string | null = null,
    cols: number | null = null,
    rows: number | null = null,
    // Whether this tab is the visible one at connect time (#130) -- if so, a focus
    // notification is sent the moment the socket opens, so a session that was already
    // waiting for attention when its tab is (re)connected clears right away rather than
    // sitting there until the user happens to type. Focus is all this decides now: it
    // used to gate whether the connect-time size was also queued as a resize (#271),
    // because only a visible tab could be measured at all. Every tab is measured before
    // it connects since #375, and the size is re-asserted on every open below, so there
    // is nothing left for it to gate.
    initiallyFocused: boolean = false,
  ) {
    this.cols = cols;
    this.rows = rows;
    this.isFocused = initiallyFocused;
  }

  /** Opens the connection. `onMessage`/`onClose` are also reused by every later reconnect (#279). */
  connect(onMessage: (text: string) => void, onClose: () => void): void {
    this.onMessage = onMessage;
    this.onClose = onClose;
    this.open();
  }

  private open(): void {
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    const params = new URLSearchParams();
    if (this.dir) {
      params.set('dir', this.dir);
    }
    if (this.cmd) {
      params.set('cmd', this.cmd);
    }
    if (this.resume) {
      params.set('resume', this.resume);
    }
    // The size as it is *now*, not as it was when this session was constructed -- a
    // reconnect that makes the engine create the PTY (after an engine restart, say)
    // starts it at whatever this URL says (#376).
    if (this.cols !== null) {
      params.set('cols', String(this.cols));
    }
    if (this.rows !== null) {
      params.set('rows', String(this.rows));
    }
    const query = params.size > 0 ? `?${params.toString()}` : '';
    const socket = new WebSocket(`${proto}://${location.host}/ws/sessions/${this.sessionId}${query}`);
    this.socket = socket;
    socket.onopen = () => {
      this.backoffMs = INITIAL_BACKOFF_MS;
      // A reattach starts with the engine assuming this tab is unfocused (#130) --
      // resend if it wasn't.
      if (this.isFocused) {
        this.focus();
      }
      // Every open, not just the first (#376). The engine deliberately ignores the
      // connect URL's cols/rows when it finds a live session, so this '1'-tagged
      // message is the only channel that reaches an already-running PTY -- and a
      // resize() that raced this reconnect is carried here too, since the size it set
      // is simply the current one by the time this runs.
      this.sendSize();
    };
    socket.onmessage = (event) => this.onMessage?.(event.data);
    // A close the engine's keepalive forced (#279, dev.locklane.engine.ws.TerminalHeartbeat)
    // or the network dropping look identical here -- either way this is unexpected,
    // so reconnect with backoff unless close() tore this session down on purpose.
    socket.onclose = () => {
      this.socket = null;
      this.onClose?.();
      if (!this.closedByClient) {
        this.scheduleReconnect();
      }
    };
  }

  private scheduleReconnect(): void {
    if (this.reconnectTimer !== null) {
      return;
    }
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.open();
    }, this.backoffMs);
    this.backoffMs = Math.min(this.backoffMs * 2, MAX_BACKOFF_MS);
  }

  /**
   * Called when the tab regains focus/visibility (#279, terminal.component.ts): if
   * the connection is not currently open, reconnect right away instead of waiting
   * out whatever backoff delay is pending -- the whole point of watching for this is
   * to make the console interactive again the moment the user comes back, not after
   * a timer that may itself have been throttled while the tab was backgrounded.
   */
  checkConnection(): void {
    if (this.closedByClient) {
      return;
    }
    const state = this.socket?.readyState;
    if (state === WebSocket.CONNECTING || state === WebSocket.OPEN) {
      return;
    }
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.backoffMs = INITIAL_BACKOFF_MS;
    this.open();
  }

  send(input: string): void {
    this.sendFramed(INPUT, input);
  }

  resize(cols: number, rows: number): void {
    this.cols = cols;
    this.rows = rows;
    // Nothing is queued when the socket is not open: the size is state now, and the
    // next open sends whatever it currently holds (#376).
    this.sendSize();
  }

  /** Tells the engine the size the browser terminal is at, if one is known yet. */
  private sendSize(): void {
    if (this.cols === null || this.rows === null) {
      return;
    }
    this.sendFramed(RESIZE, `${this.cols}x${this.rows}`);
  }

  /** Tells the engine this session's tab is the one the user is looking at (#130). */
  focus(): void {
    this.isFocused = true;
    this.sendFramed(FOCUS, '');
  }

  close(): void {
    this.closedByClient = true;
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.socket?.close();
    this.socket = null;
  }

  /** Sends if the socket is open; returns whether it went out. */
  private sendFramed(type: string, payload: string): boolean {
    if (this.socket && this.socket.readyState === WebSocket.OPEN) {
      this.socket.send(type + payload);
      return true;
    }
    return false;
  }
}
