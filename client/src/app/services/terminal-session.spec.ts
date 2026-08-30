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

  /* Replaces the #268 spec 'holds no further size once a pending resize has been
     delivered', which asserted the opposite: that the held size was consumed on the
     first open and never sent again. That one-shot rule is what #376 removes -- it is
     precisely why a reattached PTY kept a stale size -- so the expectation is inverted
     rather than the coverage dropped. */
  it('re-sends the size it currently holds on every open, not only the first (#376)', () => {
    const { session, socket } = connect();
    socket.readyState = 0;
    session.resize(133, 42);
    socket.readyState = FakeWebSocket.OPEN;
    socket.onopen?.();

    socket.onopen?.();

    expect(socket.sent).toEqual(['1133x42', '1133x42']);
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

  /* Replaces the #271 spec 'does not invent a resize at connect for a tab that started
     inactive'. Under #271 an inactive tab could not be measured, so its 80x24 was
     xterm's unfitted default and asserting it would have been a lie -- staying quiet
     was right. Since #375 every tab is fitted before it connects, so an inactive tab's
     size is a real measurement and withholding it is what leaves a reattached PTY
     wrong. Same scenario, opposite expectation, for a reason that changed underneath. */
  it('sends the connect-time size even for a tab that started inactive (#376)', () => {
    const { socket } = connect(80, 24, false);

    socket.onopen?.();

    expect(socket.sent).toEqual(['180x24']);
  });

  it('does not invent a resize at connect when no size was given, even if the tab started active (#271)', () => {
    const { socket } = connect(null, null, true);

    socket.onopen?.();

    expect(socket.sent).toEqual(['2']);
  });

  it("puts the terminal's current size on a reconnect URL, not the size it was constructed with (#376)", () => {
    // The engine creates a fresh PTY at the URL's size when it finds no live session --
    // which is what happens after an engine restart. Advertising the construction-time
    // size there is how a tab ended up stuck at a size nothing ever corrected.
    const { session } = connect(80, 24);
    session.resize(200, 50);

    latestSocket().triggerUnexpectedClose();
    jasmine.clock().tick(1000);

    expect(latestSocket().url).toContain('cols=200');
    expect(latestSocket().url).toContain('rows=50');
    expect(latestSocket().url).not.toContain('cols=80');
  });

  it('asserts the current size again on the reconnect itself, not just in its URL (#376)', () => {
    // A live session ignores the connect URL's size on reattach, so the URL alone does
    // not reach an already-running PTY -- only the '1'-tagged message does.
    const { session } = connect(80, 24);
    session.resize(200, 50);
    latestSocket().triggerUnexpectedClose();
    jasmine.clock().tick(1000);

    latestSocket().onopen?.();

    expect(latestSocket().sent).toEqual(['1200x50']);
  });

  it('honours a resize that lands while a reconnect is still in flight (#376)', () => {
    const { session } = connect(80, 24);
    latestSocket().triggerUnexpectedClose();
    jasmine.clock().tick(1000);
    latestSocket().readyState = FakeWebSocket.CONNECTING;

    session.resize(200, 50);
    latestSocket().readyState = FakeWebSocket.OPEN;
    latestSocket().onopen?.();

    expect(latestSocket().sent).toEqual(['1200x50']);
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
