import { TerminalSession } from './terminal-session';

class FakeWebSocket {
  // Matches the real WebSocket API's readyState values (#279's reconnect/checkConnection
  // logic branches on CONNECTING/OPEN/CLOSED, not just the pre-existing OPEN).
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSING = 2;
  static readonly CLOSED = 3;
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
    this.readyState = FakeWebSocket.CLOSED;
    this.onclose?.();
  }

  /** Simulates the connection dropping on its own (network/server), not a deliberate close(). */
  triggerUnexpectedClose(): void {
    this.readyState = FakeWebSocket.CLOSED;
    this.onclose?.();
  }
}

describe('TerminalSession', () => {
  let originalWebSocket: typeof WebSocket;

  beforeEach(() => {
    originalWebSocket = window.WebSocket;
    FakeWebSocket.instances = [];
    (window as unknown as { WebSocket: unknown }).WebSocket = FakeWebSocket;
    jasmine.clock().install();
  });

  afterEach(() => {
    jasmine.clock().uninstall();
    (window as unknown as { WebSocket: unknown }).WebSocket = originalWebSocket;
  });

  function latestSocket(): FakeWebSocket {
    return FakeWebSocket.instances[FakeWebSocket.instances.length - 1];
  }

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

  it('delivers the connect-time size as a resize once the socket opens, for a tab that started active (#271)', () => {
    const { socket } = connect(133, 42, true);

    socket.onopen?.();

    expect(socket.sent).toEqual(['2', '1133x42']);
  });

  it('delivers the connect-time size even when unchanged from xterm defaults, for a tab that started active (#271)', () => {
    const { socket } = connect(80, 24, true);

    socket.onopen?.();

    expect(socket.sent).toEqual(['2', '180x24']);
  });

  it('does not invent a resize at connect for a tab that started inactive (#271)', () => {
    const { socket } = connect(80, 24, false);

    socket.onopen?.();

    expect(socket.sent).toEqual([]);
  });

  it('does not invent a resize at connect when no size was given, even if the tab started active (#271)', () => {
    const { socket } = connect(null, null, true);

    socket.onopen?.();

    expect(socket.sent).toEqual(['2']);
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

  it('reconnects with backoff after an unexpected close (#279)', () => {
    connect();
    expect(FakeWebSocket.instances.length).toBe(1);

    latestSocket().triggerUnexpectedClose();
    expect(FakeWebSocket.instances.length).toBe(1);

    jasmine.clock().tick(1000);
    expect(FakeWebSocket.instances.length).toBe(2);
  });

  it('backs off further on each consecutive failure to reconnect (#279)', () => {
    connect();

    latestSocket().triggerUnexpectedClose();
    jasmine.clock().tick(1000);
    expect(FakeWebSocket.instances.length).toBe(2);

    latestSocket().triggerUnexpectedClose();
    jasmine.clock().tick(1000);
    expect(FakeWebSocket.instances.length).toBe(2); // not yet -- backoff doubled to 2s

    jasmine.clock().tick(1000);
    expect(FakeWebSocket.instances.length).toBe(3);
  });

  it('resets backoff to the initial delay once a reconnect actually opens (#279)', () => {
    connect();

    latestSocket().triggerUnexpectedClose();
    jasmine.clock().tick(1000);
    latestSocket().onopen?.();

    latestSocket().triggerUnexpectedClose();
    jasmine.clock().tick(1000);
    expect(FakeWebSocket.instances.length).toBe(3);
  });

  it('does not reconnect after a deliberate close() (#279)', () => {
    const { session } = connect();

    session.close();
    jasmine.clock().tick(60000);

    expect(FakeWebSocket.instances.length).toBe(1);
  });

  it('calls the onClose callback again on every reconnect, not just the first drop (#279)', () => {
    const closes: void[] = [];
    const session = new TerminalSession('7-worktree', '/repo');
    session.connect(
      () => {},
      () => closes.push(undefined),
    );

    latestSocket().triggerUnexpectedClose();
    jasmine.clock().tick(1000);
    latestSocket().triggerUnexpectedClose();

    expect(closes.length).toBe(2);
  });

  describe('checkConnection() (#279)', () => {
    it('reconnects immediately, bypassing any pending backoff delay', () => {
      const { session } = connect();
      latestSocket().triggerUnexpectedClose();
      expect(FakeWebSocket.instances.length).toBe(1);

      session.checkConnection();

      expect(FakeWebSocket.instances.length).toBe(2);
    });

    it('does not also fire the pending backoff timer as a second reconnect', () => {
      const { session } = connect();
      latestSocket().triggerUnexpectedClose();

      session.checkConnection();
      jasmine.clock().tick(60000);

      expect(FakeWebSocket.instances.length).toBe(2);
    });

    it('does nothing while the connection is already open', () => {
      const { session } = connect();

      session.checkConnection();

      expect(FakeWebSocket.instances.length).toBe(1);
    });

    it('does nothing after a deliberate close()', () => {
      const { session } = connect();
      session.close();

      session.checkConnection();

      expect(FakeWebSocket.instances.length).toBe(1);
    });

    it('resends the focus notification on the reconnect it triggers, once the tab was ever focused', () => {
      const { session } = connect();
      session.focus();
      latestSocket().sent = [];
      latestSocket().triggerUnexpectedClose();

      session.checkConnection();
      latestSocket().onopen?.();

      expect(latestSocket().sent).toEqual(['2']);
    });
  });
});
