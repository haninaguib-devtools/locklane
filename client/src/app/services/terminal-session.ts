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

  constructor(
    private readonly sessionId: string,
    private readonly dir: string | null,
    private readonly cmd: string | null = null,
    private readonly cols: number | null = null,
    private readonly rows: number | null = null,
    // Whether this tab is the visible one at connect time (#130) -- if so, a focus
    // notification is sent the moment the socket opens, so a session that was already
    // waiting for attention when its tab is (re)connected clears right away rather
    // than sitting there until the user happens to type.
    private readonly initiallyFocused: boolean = false,
  ) {}

  connect(onMessage: (text: string) => void, onClose: () => void): void {
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    const params = new URLSearchParams();
    if (this.dir) {
      params.set('dir', this.dir);
    }
    if (this.cmd) {
      params.set('cmd', this.cmd);
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
    };
    this.socket.onmessage = (event) => onMessage(event.data);
    this.socket.onclose = () => onClose();
  }

  send(input: string): void {
    this.sendFramed(INPUT, input);
  }

  resize(cols: number, rows: number): void {
    this.sendFramed(RESIZE, `${cols}x${rows}`);
  }

  /** Tells the engine this session's tab is the one the user is looking at (#130). */
  focus(): void {
    this.sendFramed(FOCUS, '');
  }

  close(): void {
    this.socket?.close();
    this.socket = null;
  }

  private sendFramed(type: string, payload: string): void {
    if (this.socket && this.socket.readyState === WebSocket.OPEN) {
      this.socket.send(type + payload);
    }
  }
}
