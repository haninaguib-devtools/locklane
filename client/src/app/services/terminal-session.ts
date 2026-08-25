// A thin wrapper around one console session's WebSocket connection
// (dev.locklane.engine.ws.TerminalWebSocketHandler, /ws/sessions/{sessionId}).
// Not an Angular service: each terminal tab owns its own instance and its own
// connection, so this is plain state a component creates and destroys directly.
export class TerminalSession {
  private socket: WebSocket | null = null;

  constructor(
    private readonly sessionId: string,
    private readonly dir: string | null,
    private readonly cmd: string | null = null,
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
    const query = params.size > 0 ? `?${params.toString()}` : '';
    this.socket = new WebSocket(`${proto}://${location.host}/ws/sessions/${this.sessionId}${query}`);
    this.socket.onmessage = (event) => onMessage(event.data);
    this.socket.onclose = () => onClose();
  }

  send(input: string): void {
    if (this.socket && this.socket.readyState === WebSocket.OPEN) {
      this.socket.send(input);
    }
  }

  close(): void {
    this.socket?.close();
    this.socket = null;
  }
}
