// A thin wrapper around one worktree's WebSocket connection
// (dev.locklane.engine.ws.TerminalWebSocketHandler, /ws/sessions/{worktreeId}).
// Not an Angular service: each terminal tab owns its own instance and its own
// connection, so this is plain state a component creates and destroys directly.
export class TerminalSession {
  private socket: WebSocket | null = null;

  constructor(
    private readonly worktreeId: string,
    private readonly dir: string | null,
  ) {}

  connect(onMessage: (text: string) => void, onClose: () => void): void {
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    const query = this.dir ? `?dir=${encodeURIComponent(this.dir)}` : '';
    this.socket = new WebSocket(`${proto}://${location.host}/ws/sessions/${this.worktreeId}${query}`);
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
