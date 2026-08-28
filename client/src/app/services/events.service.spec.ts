import { EventsService } from './events.service';

class FakeWebSocket {
  static instances: FakeWebSocket[] = [];

  onopen: (() => void) | null = null;
  onmessage: ((event: MessageEvent<string>) => void) | null = null;
  onclose: (() => void) | null = null;

  constructor(public readonly url: string) {
    FakeWebSocket.instances.push(this);
  }

  triggerOpen(): void {
    this.onopen?.();
  }

  triggerMessage(data: string): void {
    this.onmessage?.({ data } as MessageEvent<string>);
  }

  triggerClose(): void {
    this.onclose?.();
  }
}

describe('EventsService', () => {
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

  it('connects to the app-wide events endpoint', () => {
    new EventsService().connect();

    expect(latestSocket().url).toMatch(/\/ws\/events$/);
  });

  it('does not open a second socket on a repeated connect() call', () => {
    const service = new EventsService();
    service.connect();
    service.connect();

    expect(FakeWebSocket.instances.length).toBe(1);
  });

  it('emits a parsed event on events$', () => {
    const service = new EventsService();
    const received: unknown[] = [];
    service.events$.subscribe((event) => received.push(event));
    service.connect();

    latestSocket().triggerMessage('{"type":"ping","value":1}');

    expect(received).toEqual([{ type: 'ping', value: 1 }]);
  });

  it('ignores a message that is not valid JSON', () => {
    const service = new EventsService();
    const received: unknown[] = [];
    service.events$.subscribe((event) => received.push(event));
    service.connect();

    latestSocket().triggerMessage('not json');

    expect(received).toEqual([]);
  });

  it('does not fire reconnected$ on the first successful connect', () => {
    const service = new EventsService();
    const reconnects: void[] = [];
    service.reconnected$.subscribe(() => reconnects.push(undefined));
    service.connect();

    latestSocket().triggerOpen();

    expect(reconnects.length).toBe(0);
  });

  it('reconnects with backoff after a drop and fires reconnected$', () => {
    const service = new EventsService();
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
    const service = new EventsService();
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

  it('records the stamp from the first engineVersion message without firing versionChanged$', () => {
    const service = new EventsService();
    const changes: void[] = [];
    service.versionChanged$.subscribe(() => changes.push(undefined));
    service.connect();

    latestSocket().triggerMessage('{"type":"engineVersion","version":"a"}');

    expect(changes.length).toBe(0);
  });

  it('fires versionChanged$ when a reconnect delivers a stamp different from the one seen at boot', () => {
    const service = new EventsService();
    const changes: void[] = [];
    service.versionChanged$.subscribe(() => changes.push(undefined));
    service.connect();
    latestSocket().triggerMessage('{"type":"engineVersion","version":"a"}');

    latestSocket().triggerMessage('{"type":"engineVersion","version":"b"}');

    expect(changes.length).toBe(1);
  });

  it('does not fire versionChanged$ when the stamp is unchanged after reconnect', () => {
    const service = new EventsService();
    const changes: void[] = [];
    service.versionChanged$.subscribe(() => changes.push(undefined));
    service.connect();
    latestSocket().triggerMessage('{"type":"engineVersion","version":"a"}');

    latestSocket().triggerMessage('{"type":"engineVersion","version":"a"}');

    expect(changes.length).toBe(0);
  });

  it('keeps comparing every later stamp against the one seen at boot, not the previous message', () => {
    const service = new EventsService();
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
});
