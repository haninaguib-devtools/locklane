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

export class TerminalSession {
  private socket: WebSocket | null = null;
  /* A size the terminal settled on before the socket finished its handshake (#268).
     Sending it then would silently drop it, and nothing re-emits a size once the
     browser terminal is already correct — so it is held here and delivered on open.
     One slot, not a queue: only the size the terminal actually ended at matters, and
     replaying superseded ones would just make the PTY reflow more than once. */
  private pendingResize: string | null = null;

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
    if (this.initiallyFocused && this.cols !== null && this.rows !== null) {
      this.pendingResize = `${this.cols}x${this.rows}`;
    }
  }

  connect(onMessage: (text: string) => void, onClose: () => void): void {
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
    this.socket = new WebSocket(`${proto}://${location.host}/ws/sessions/${this.sessionId}${query}`);
    this.socket.onopen = () => {
      if (this.initiallyFocused) {
        this.focus();
      }
      if (this.pendingResize !== null) {
        const size = this.pendingResize;
        this.pendingResize = null;
        this.sendFramed(RESIZE, size);
      }
    };
    this.socket.onmessage = (event) => onMessage(event.data);
    this.socket.onclose = () => onClose();
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
    this.sendFramed(FOCUS, '');
  }

  close(): void {
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
