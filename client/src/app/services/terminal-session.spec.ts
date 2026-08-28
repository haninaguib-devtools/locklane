import { TerminalSession } from './terminal-session';

class FakeWebSocket {
  static readonly OPEN = 1;
  static instances: FakeWebSocket[] = [];

  readyState = FakeWebSocket.OPEN;
  sent: string[] = [];
  onopen: (() => void) | null = null;
  onmessage: ((event: MessageEvent) => void) | null = null;
  onclose: (() => void) | null = null;

  constructor(public readonly url: string) {
    FakeWebSocket.instances.push(this);
  }

  send(data: string): void {
    this.sent.push(data);
  }

  close(): void {
    this.onclose?.();
  }
}

describe('TerminalSession', () => {
  let originalWebSocket: typeof WebSocket;

  beforeEach(() => {
    originalWebSocket = window.WebSocket;
    FakeWebSocket.instances = [];
    (window as unknown as { WebSocket: unknown }).WebSocket = FakeWebSocket;
  });

  afterEach(() => {
    (window as unknown as { WebSocket: unknown }).WebSocket = originalWebSocket;
  });

  // Connects a fresh session and returns its underlying fake socket, so each
  // test drives one session/socket pair instead of juggling several.
  function connect(
    cols: number | null = null,
    rows: number | null = null,
    initiallyFocused = false,
  ): { session: TerminalSession; socket: FakeWebSocket } {
    const session = new TerminalSession('7-worktree', '/repo', 'claude', null, cols, rows, initiallyFocused);
    session.connect(
      () => {},
      () => {},
    );
    return { session, socket: FakeWebSocket.instances[FakeWebSocket.instances.length - 1] };
  }

  it('includes cols and rows in the connect URL when given', () => {
    const { socket } = connect(120, 40);

    expect(socket.url).toContain('cols=120');
    expect(socket.url).toContain('rows=40');
  });

  it('omits cols and rows from the connect URL when not given', () => {
    const { socket } = connect();

    expect(socket.url).not.toContain('cols=');
    expect(socket.url).not.toContain('rows=');
  });

  it('includes the resume id in the connect URL when given (#103)', () => {
    const session = new TerminalSession('7-worktree', '/repo', 'claude', 'abc-123');
    session.connect(
      () => {},
      () => {},
    );
    const socket = FakeWebSocket.instances[FakeWebSocket.instances.length - 1];

    expect(socket.url).toContain('resume=abc-123');
  });

  it('omits the resume param from the connect URL when not given (#103)', () => {
    const { socket } = connect();

    expect(socket.url).not.toContain('resume=');
  });

  it('tags keystroke input with the input type', () => {
    const { session, socket } = connect();

    session.send('ls\n');

    expect(socket.sent).toEqual(['0ls\n']);
  });

  it('tags a resize with the resize type and cols x rows', () => {
    const { session, socket } = connect();

    session.resize(133, 42);

    expect(socket.sent).toEqual(['1133x42']);
  });

  it('does not send while the socket is not open', () => {
    const { session, socket } = connect();
    socket.readyState = 0;

    session.send('ls\n');
    session.resize(80, 24);

    expect(socket.sent).toEqual([]);
  });

  it('delivers a resize requested before the socket opened once it opens (#268)', () => {
    const { session, socket } = connect();
    socket.readyState = 0;

    session.resize(133, 42);
    expect(socket.sent).toEqual([]);

    socket.readyState = FakeWebSocket.OPEN;
    socket.onopen?.();

    expect(socket.sent).toEqual(['1133x42']);
  });

  it('delivers only the most recent size requested before the socket opened (#268)', () => {
    const { session, socket } = connect();
    socket.readyState = 0;

    session.resize(80, 24);
    session.resize(133, 42);

    socket.readyState = FakeWebSocket.OPEN;
    socket.onopen?.();

    expect(socket.sent).toEqual(['1133x42']);
  });

  it('holds no further size once a pending resize has been delivered (#268)', () => {
    const { session, socket } = connect();
    socket.readyState = 0;
    session.resize(133, 42);
    socket.readyState = FakeWebSocket.OPEN;
    socket.onopen?.();

    socket.onopen?.();

    expect(socket.sent).toEqual(['1133x42']);
  });

  it('delivers a pending resize alongside the opening focus notification (#268)', () => {
    const { session, socket } = connect(null, null, true);
    socket.readyState = 0;

    session.resize(133, 42);

    socket.readyState = FakeWebSocket.OPEN;
    socket.onopen?.();

    expect(socket.sent).toEqual(['2', '1133x42']);
  });

  it('tags a focus notification with the focus type and no body (#130)', () => {
    const { session, socket } = connect();

    session.focus();

    expect(socket.sent).toEqual(['2']);
  });

  it('sends focus as soon as the socket opens when the tab started out focused (#130)', () => {
    const { socket } = connect(null, null, true);

    socket.onopen?.();

    expect(socket.sent).toEqual(['2']);
  });

  it('sends no focus notification on open when the tab did not start out focused (#130)', () => {
    const { socket } = connect();

    socket.onopen?.();

    expect(socket.sent).toEqual([]);
  });
});
