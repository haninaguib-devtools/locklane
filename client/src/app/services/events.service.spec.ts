import { EventsService } from './events.service';

class FakeWebSocket {
  // Matches the real WebSocket API's readyState values (#665's checkConnection() logic
  // branches on CONNECTING/OPEN, the same convention terminal-session.spec.ts's fake
  // already uses for TerminalSession.checkConnection()).
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSING = 2;
  static readonly CLOSED = 3;
  static instances: FakeWebSocket[] = [];

  readyState = FakeWebSocket.OPEN;
  // How many times the service itself asked this socket to close (#762's self-initiated
  // reconnect) -- distinct from triggerClose(), which is the far side going away.
  closeCalls = 0;
  onopen: (() => void) | null = null;
  onmessage: ((event: MessageEvent<string>) => void) | null = null;
  onclose: (() => void) | null = null;

  constructor(public readonly url: string) {
    FakeWebSocket.instances.push(this);
  }

  // Like the real thing, close() only starts the closing handshake; the `close` event
  // (triggerClose()) arrives later, if ever, on a dead connection.
  close(): void {
    this.closeCalls++;
    this.readyState = FakeWebSocket.CLOSING;
  }

  triggerOpen(): void {
    this.readyState = FakeWebSocket.OPEN;
    this.onopen?.();
  }

  triggerMessage(data: string): void {
    this.onmessage?.({ data } as MessageEvent<string>);
  }

  triggerClose(): void {
    this.readyState = FakeWebSocket.CLOSED;
    this.onclose?.();
  }
}

describe('EventsService', () => {
  let originalWebSocket: typeof WebSocket;
  // Every service a test creates, so afterEach can detach its foreground listeners
  // (#665) -- left attached, a later test's own visibilitychange/focus dispatch would
  // also reconnect this one's now-orphaned socket, polluting that test's own counts.
  let services: EventsService[];

  beforeEach(() => {
    originalWebSocket = window.WebSocket;
    FakeWebSocket.instances = [];
    (window as unknown as { WebSocket: unknown }).WebSocket = FakeWebSocket;
    jasmine.clock().install();
    services = [];
  });

  afterEach(() => {
    services.forEach((service) => service.ngOnDestroy());
    jasmine.clock().uninstall();
    (window as unknown as { WebSocket: unknown }).WebSocket = originalWebSocket;
  });

  function newService(): EventsService {
    const service = new EventsService();
    services.push(service);
    return service;
  }

  function latestSocket(): FakeWebSocket {
    return FakeWebSocket.instances[FakeWebSocket.instances.length - 1];
  }

  it('connects to the app-wide events endpoint', () => {
    newService().connect();

    expect(latestSocket().url).toMatch(/\/ws\/events$/);
  });

  it('does not open a second socket on a repeated connect() call', () => {
    const service = newService();
    service.connect();
    service.connect();

    expect(FakeWebSocket.instances.length).toBe(1);
  });

  it('emits a parsed event on events$', () => {
    const service = newService();
    const received: unknown[] = [];
    service.events$.subscribe((event) => received.push(event));
    service.connect();

    latestSocket().triggerMessage('{"type":"ping","value":1}');

    expect(received).toEqual([{ type: 'ping', value: 1 }]);
  });

  it('ignores a message that is not valid JSON', () => {
    const service = newService();
    const received: unknown[] = [];
    service.events$.subscribe((event) => received.push(event));
    service.connect();

    latestSocket().triggerMessage('not json');

    expect(received).toEqual([]);
  });

  it('does not fire reconnected$ on the first successful connect', () => {
    const service = newService();
    const reconnects: void[] = [];
    service.reconnected$.subscribe(() => reconnects.push(undefined));
    service.connect();

    latestSocket().triggerOpen();

    expect(reconnects.length).toBe(0);
  });

  it('reconnects with backoff after a drop and fires reconnected$', () => {
    const service = newService();
    const reconnects: void[] = [];
    service.reconnected$.subscribe(() => reconnects.push(undefined));
    service.connect();
    latestSocket().triggerOpen();

    latestSocket().triggerClose();
    expect(FakeWebSocket.instances.length).toBe(1);

    jasmine.clock().tick(1000);
    expect(FakeWebSocket.instances.length).toBe(2);

    latestSocket().triggerOpen();
    expect(reconnects.length).toBe(1);
  });

  it('backs off further on each consecutive failure to reconnect', () => {
    const service = newService();
    service.connect();
    latestSocket().triggerOpen();

    latestSocket().triggerClose();
    jasmine.clock().tick(1000);
    expect(FakeWebSocket.instances.length).toBe(2);

    latestSocket().triggerClose();
    jasmine.clock().tick(1000);
    expect(FakeWebSocket.instances.length).toBe(2); // not yet -- backoff doubled to 2s

    jasmine.clock().tick(1000);
    expect(FakeWebSocket.instances.length).toBe(3);
  });

  it('has no engineVersion before any greeting has arrived (#595)', () => {
    const service = newService();
    service.connect();

    expect(service.engineVersion()).toBeNull();
  });

  it('keeps the first engineVersion greeting as state for a reader that arrives later (#595)', () => {
    const service = newService();
    service.connect();
    latestSocket().triggerMessage('{"type":"engineVersion","version":"a","release":"0.1.11"}');

    // Nothing subscribed to events$ when the greeting went by -- the state is what
    // a lazily created consumer (the About dialog) reads afterwards.
    expect(service.engineVersion()).toEqual({ type: 'engineVersion', version: 'a', release: '0.1.11' });
  });

  it('keeps releaseUrl alongside release when the greeting carries one (#799)', () => {
    const service = newService();
    service.connect();

    latestSocket().triggerMessage(
      '{"type":"engineVersion","version":"a","release":"0.2.20",' +
        '"releaseUrl":"https://github.com/o/r/releases/tag/v0.2.20"}',
    );

    expect(service.engineVersion()?.releaseUrl).toBe('https://github.com/o/r/releases/tag/v0.2.20');
  });

  it('still recognizes an engineVersion greeting with no releaseUrl, from an older engine (#799)', () => {
    const service = newService();
    service.connect();

    latestSocket().triggerMessage('{"type":"engineVersion","version":"a","release":"0.1.11"}');

    expect(service.engineVersion()?.release).toBe('0.1.11');
    expect(service.engineVersion()?.releaseUrl).toBeUndefined();
  });

  it("replaces engineVersion with each reconnect's greeting, release included (#595)", () => {
    const service = newService();
    service.connect();
    latestSocket().triggerOpen();
    latestSocket().triggerMessage('{"type":"engineVersion","version":"a","release":"0.1.11"}');

    latestSocket().triggerClose();
    jasmine.clock().tick(1000);
    latestSocket().triggerOpen();
    latestSocket().triggerMessage('{"type":"engineVersion","version":"b","release":"0.1.12"}');

    expect(service.engineVersion()?.release).toBe('0.1.12');
    expect(service.engineVersion()?.version).toBe('b');
  });

  it('records the stamp from the first engineVersion message without firing versionChanged$', () => {
    const service = newService();
    const changes: void[] = [];
    service.versionChanged$.subscribe(() => changes.push(undefined));
    service.connect();

    latestSocket().triggerMessage('{"type":"engineVersion","version":"a"}');

    expect(changes.length).toBe(0);
  });

  it('fires versionChanged$ when a reconnect delivers a stamp different from the one seen at boot', () => {
    const service = newService();
    const changes: void[] = [];
    service.versionChanged$.subscribe(() => changes.push(undefined));
    service.connect();
    latestSocket().triggerMessage('{"type":"engineVersion","version":"a"}');

    latestSocket().triggerMessage('{"type":"engineVersion","version":"b"}');

    expect(changes.length).toBe(1);
  });

  it('does not fire versionChanged$ when the stamp is unchanged after reconnect', () => {
    const service = newService();
    const changes: void[] = [];
    service.versionChanged$.subscribe(() => changes.push(undefined));
    service.connect();
    latestSocket().triggerMessage('{"type":"engineVersion","version":"a"}');

    latestSocket().triggerMessage('{"type":"engineVersion","version":"a"}');

    expect(changes.length).toBe(0);
  });

  it('keeps comparing every later stamp against the one seen at boot, not the previous message', () => {
    const service = newService();
    const changes: void[] = [];
    service.versionChanged$.subscribe(() => changes.push(undefined));
    service.connect();
    latestSocket().triggerMessage('{"type":"engineVersion","version":"a"}');
    latestSocket().triggerMessage('{"type":"engineVersion","version":"b"}');

    // Still differs from the boot stamp "a" -- if this compared against the previous
    // message ("b") instead, it would stay silent here.
    latestSocket().triggerMessage('{"type":"engineVersion","version":"b"}');

    expect(changes.length).toBe(2);
  });

  describe('checkConnection() (#665)', () => {
    it('reconnects immediately, bypassing any pending backoff delay', () => {
      const service = newService();
      service.connect();
      latestSocket().triggerOpen();
      latestSocket().triggerClose();
      expect(FakeWebSocket.instances.length).toBe(1);

      service.checkConnection();

      expect(FakeWebSocket.instances.length).toBe(2);
    });

    it('does not also fire the pending backoff timer as a second reconnect', () => {
      const service = newService();
      service.connect();
      latestSocket().triggerOpen();
      latestSocket().triggerClose();

      service.checkConnection();
      jasmine.clock().tick(60000);

      expect(FakeWebSocket.instances.length).toBe(2);
    });

    it('does nothing while the connection is already open', () => {
      const service = newService();
      service.connect();
      latestSocket().triggerOpen();

      service.checkConnection();

      expect(FakeWebSocket.instances.length).toBe(1);
    });
  });

  describe('foreground listeners (#665)', () => {
    it('reconnects when the document becomes visible again', () => {
      const service = newService();
      service.connect();
      latestSocket().triggerOpen();
      latestSocket().triggerClose();
      expect(FakeWebSocket.instances.length).toBe(1);

      document.dispatchEvent(new Event('visibilitychange'));

      expect(FakeWebSocket.instances.length).toBe(2);
    });

    it('does not reconnect on visibilitychange while the document is still hidden', () => {
      const service = newService();
      service.connect();
      latestSocket().triggerOpen();
      latestSocket().triggerClose();
      Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true });

      try {
        document.dispatchEvent(new Event('visibilitychange'));
        expect(FakeWebSocket.instances.length).toBe(1);
      } finally {
        delete (document as unknown as { visibilityState?: string }).visibilityState;
      }
    });

    it('reconnects when the window regains focus', () => {
      const service = newService();
      service.connect();
      latestSocket().triggerOpen();
      latestSocket().triggerClose();
      expect(FakeWebSocket.instances.length).toBe(1);

      window.dispatchEvent(new Event('focus'));

      expect(FakeWebSocket.instances.length).toBe(2);
    });

    it('does not attach a second pair of listeners on a repeated connect() call', () => {
      const service = newService();
      service.connect();
      service.connect();
      latestSocket().triggerOpen();
      latestSocket().triggerClose();
      expect(FakeWebSocket.instances.length).toBe(1);

      window.dispatchEvent(new Event('focus'));

      // Exactly one more, not two -- a doubled listener pair would reconnect twice.
      expect(FakeWebSocket.instances.length).toBe(2);
    });
  });

  describe('liveness check (#762)', () => {
    const INTERVAL_MS = 1000;
    const INITIAL_BACKOFF_MS = 1000;
    const GREETING = `{"type":"engineVersion","version":"a","heartbeatIntervalMs":${INTERVAL_MS}}`;
    const HEARTBEAT = '{"type":"heartbeat"}';

    beforeEach(() => {
      // Every decision under test compares Date.now() against the last message's
      // time, so the mock clock must move the date as well as fire timers.
      jasmine.clock().mockDate(new Date(2026, 8, 7, 12, 0, 0));
    });

    // Connects, opens, and delivers the engine's greeting naming the interval -- the
    // point from which the service is watching the socket.
    function openGreeted(): EventsService {
      const service = newService();
      service.connect();
      latestSocket().triggerOpen();
      latestSocket().triggerMessage(GREETING);
      return service;
    }

    // Moves the wall clock forward without running any timer -- what a suspended
    // laptop, or a background tab whose timers the browser stopped, looks like from
    // inside the page.
    function suspendFor(ms: number): void {
      jasmine.clock().mockDate(new Date(Date.now() + ms));
    }

    it('closes the socket itself and reconnects when more than two intervals pass with no message, and reconnected$ fires (a)', () => {
      const service = openGreeted();
      const reconnects: void[] = [];
      service.reconnected$.subscribe(() => reconnects.push(undefined));
      const original = latestSocket();

      jasmine.clock().tick(INTERVAL_MS * 2);
      // Exactly two intervals of silence is still within the allowance.
      expect(original.closeCalls).toBe(0);
      expect(FakeWebSocket.instances.length).toBe(1);

      jasmine.clock().tick(INTERVAL_MS);

      expect(original.closeCalls).toBe(1);
      expect(FakeWebSocket.instances.length).toBe(2);
      expect(latestSocket()).not.toBe(original);

      latestSocket().triggerOpen();
      expect(reconnects.length).toBe(1);
    });

    it('reconnects from checkConnection() on foreground when the last message is stale, even though the socket reports OPEN (b)', () => {
      const service = openGreeted();
      const reconnects: void[] = [];
      service.reconnected$.subscribe(() => reconnects.push(undefined));
      const original = latestSocket();

      suspendFor(5 * 60 * 1000);
      expect(original.readyState).toBe(FakeWebSocket.OPEN);
      window.dispatchEvent(new Event('focus'));

      expect(original.closeCalls).toBe(1);
      expect(FakeWebSocket.instances.length).toBe(2);
      latestSocket().triggerOpen();
      expect(reconnects.length).toBe(1);
    });

    it('never closes a socket that receives heartbeats on time (c)', () => {
      openGreeted();
      const original = latestSocket();

      for (let i = 0; i < 20; i++) {
        jasmine.clock().tick(INTERVAL_MS);
        original.triggerMessage(HEARTBEAT);
      }

      expect(original.closeCalls).toBe(0);
      expect(FakeWebSocket.instances.length).toBe(1);
    });

    it('decides from elapsed time, not from how many times the timer ran, when a throttled tab fires it late (d)', () => {
      openGreeted();
      const original = latestSocket();

      // Ten intervals go by with the timer never firing (a throttled background tab),
      // then it fires exactly once. A check that counted its own runs would see one
      // run and wait; the elapsed time says the socket is long dead.
      suspendFor(INTERVAL_MS * 10);
      jasmine.clock().tick(INTERVAL_MS);

      expect(original.closeCalls).toBe(1);
      expect(FakeWebSocket.instances.length).toBe(2);
    });

    it('leaves the socket alone when the timer runs late but a message arrived meanwhile (d)', () => {
      openGreeted();
      const original = latestSocket();

      suspendFor(INTERVAL_MS * 10);
      original.triggerMessage(HEARTBEAT);
      jasmine.clock().tick(INTERVAL_MS);

      expect(original.closeCalls).toBe(0);
      expect(FakeWebSocket.instances.length).toBe(1);
    });

    it('filters heartbeat messages out of events$', () => {
      const service = openGreeted();
      const received: unknown[] = [];
      service.events$.subscribe((event) => received.push(event));

      latestSocket().triggerMessage(HEARTBEAT);
      latestSocket().triggerMessage('{"type":"issuesChanged"}');

      expect(received).toEqual([{ type: 'issuesChanged' }]);
    });

    it('counts any message as a sign of life, not only a heartbeat', () => {
      openGreeted();
      const original = latestSocket();

      jasmine.clock().tick(INTERVAL_MS);
      original.triggerMessage('{"type":"issuesChanged"}');
      jasmine.clock().tick(INTERVAL_MS * 2);

      // Three intervals since the greeting, but only two since the last message.
      expect(original.closeCalls).toBe(0);
      expect(FakeWebSocket.instances.length).toBe(1);
    });

    it('leaves a fresh OPEN socket alone on foreground', () => {
      openGreeted();
      const original = latestSocket();

      suspendFor(INTERVAL_MS);
      window.dispatchEvent(new Event('focus'));

      expect(original.closeCalls).toBe(0);
      expect(FakeWebSocket.instances.length).toBe(1);
    });

    it('runs no liveness check before a greeting has named the interval', () => {
      const service = newService();
      service.connect();
      latestSocket().triggerOpen();
      latestSocket().triggerMessage('{"type":"engineVersion","version":"a"}');

      jasmine.clock().tick(60 * 60 * 1000);
      window.dispatchEvent(new Event('focus'));

      expect(latestSocket().closeCalls).toBe(0);
      expect(FakeWebSocket.instances.length).toBe(1);
    });

    it("ignores the replaced socket's late close event after a self-initiated reconnect", () => {
      openGreeted();
      const original = latestSocket();
      jasmine.clock().tick(INTERVAL_MS * 3);
      expect(FakeWebSocket.instances.length).toBe(2);
      const replacement = latestSocket();

      // The dead connection's close finally completes long after it was asked for.
      original.triggerClose();
      // One full initial backoff period: had that close scheduled a reconnect, it
      // would have opened a third socket by now.
      jasmine.clock().tick(INITIAL_BACKOFF_MS);

      // No backoff reconnect was scheduled for it, and the replacement is untouched.
      expect(FakeWebSocket.instances.length).toBe(2);
      expect(latestSocket()).toBe(replacement);
      expect(replacement.closeCalls).toBe(0);
    });

    it('watches a reconnected socket from the moment it opens, before its own greeting arrives', () => {
      openGreeted();
      jasmine.clock().tick(INTERVAL_MS * 3);
      const replacement = latestSocket();
      replacement.triggerOpen();

      // The interval learned from the first greeting still applies; a replacement that
      // opens and then delivers nothing at all is dead too.
      jasmine.clock().tick(INTERVAL_MS * 3);

      expect(replacement.closeCalls).toBe(1);
      expect(FakeWebSocket.instances.length).toBe(3);
    });

    it('keeps engineVersion state exposing the interval it was told', () => {
      const service = openGreeted();

      expect(service.engineVersion()?.heartbeatIntervalMs).toBe(INTERVAL_MS);
    });
  });
});
