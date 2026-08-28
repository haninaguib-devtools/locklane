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
  /* A size the terminal settled on before the socket finished its handshake (#268).
     Sending it then would silently drop it, and nothing re-emits a size once the
     browser terminal is already correct — so it is held here and delivered on open.
     One slot, not a queue: only the size the terminal actually ended at matters, and
     replaying superseded ones would just make the PTY reflow more than once. */
  private pendingResize: string | null = null;
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
    private readonly cols: number | null = null,
    private readonly rows: number | null = null,
    // Whether this tab is the visible one at connect time (#130) -- if so, a focus
    // notification is sent the moment the socket opens, so a session that was already
    // waiting for attention when its tab is (re)connected clears right away rather
    // than sitting there until the user happens to type. It also means cols/rows carry
    // a real fitted size rather than xterm's unfitted defaults, so that size is queued
    // as a resize too (#271): the connect URL's cols/rows are sent for a brand-new
    // session, but the engine deliberately ignores them on a reattach, so the '1'-tagged
    // resize message below is the only channel that reaches an already-running PTY.
    // Queued here rather than left to the terminal's onResize handler, which fires
    // before this session exists and never fires at all when the fitted size happens to
    // equal the defaults.
    private readonly initiallyFocused: boolean = false,
  ) {
    this.isFocused = initiallyFocused;
    if (this.initiallyFocused && this.cols !== null && this.rows !== null) {
      this.pendingResize = `${this.cols}x${this.rows}`;
    }
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
    if (this.cols) {
      params.set('cols', String(this.cols));
    }
    if (this.rows) {
      params.set('rows', String(this.rows));
    }
    const query = params.size > 0 ? `?${params.toString()}` : '';
    const socket = new WebSocket(`${proto}://${location.host}/ws/sessions/${this.sessionId}${query}`);
    this.socket = socket;
    socket.onopen = () => {
      this.backoffMs = INITIAL_BACKOFF_MS;
      // A reattach starts with the engine assuming this tab is unfocused (#130) --
      // resend if it wasn't. A queued resize (from the very first connect, or from a
      // resize() call that raced this reconnect) goes out the same way it always has.
      if (this.isFocused) {
        this.focus();
      }
      if (this.pendingResize !== null) {
        const size = this.pendingResize;
        this.pendingResize = null;
        this.sendFramed(RESIZE, size);
      }
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
    const size = `${cols}x${rows}`;
    if (!this.sendFramed(RESIZE, size)) {
      this.pendingResize = size;
    }
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
