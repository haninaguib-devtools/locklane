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
  onopen: (() => void) | null = null;
  onmessage: ((event: MessageEvent<string>) => void) | null = null;
  onclose: (() => void) | null = null;

  constructor(public readonly url: string) {
    FakeWebSocket.instances.push(this);
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
});
