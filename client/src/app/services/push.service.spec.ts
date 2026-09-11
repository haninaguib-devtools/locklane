import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { SwPush } from '@angular/service-worker';
import { Subject, of } from 'rxjs';
import { PushService } from './push.service';
import { NotificationsStore } from './notifications-store';

describe('PushService (#860)', () => {
  let httpMock: HttpTestingController;
  let originalNotification: typeof Notification | undefined;

  const FAKE_SUBSCRIPTION = {
    endpoint: 'https://push.example.net/send/abc',
    toJSON: () => ({ endpoint: 'https://push.example.net/send/abc', keys: { p256dh: 'BCVx', auth: 'BTBZ' } }),
  } as unknown as PushSubscription;

  /** A controllable stand-in for Angular's SwPush. */
  function fakeSwPush(isEnabled: boolean, existing: PushSubscription | null = null) {
    return {
      isEnabled,
      messages: new Subject<object>(),
      notificationClicks: new Subject<unknown>(),
      subscription: of(existing),
      requestSubscription: jasmine.createSpy('requestSubscription').and.resolveTo(FAKE_SUBSCRIPTION),
      unsubscribe: jasmine.createSpy('unsubscribe').and.resolveTo(undefined),
    };
  }

  function configure(swPush: ReturnType<typeof fakeSwPush> | null): void {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), ...(swPush ? [{ provide: SwPush, useValue: swPush }] : [])],
    });
    httpMock = TestBed.inject(HttpTestingController);
  }

  async function flushMicrotasks(): Promise<void> {
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
  }

  beforeEach(() => {
    localStorage.removeItem('locklane.notificationsEnabled');
    originalNotification = (window as unknown as { Notification?: typeof Notification }).Notification;
    (window as unknown as { Notification: unknown }).Notification = {
      permission: 'granted',
      requestPermission: () => Promise.resolve('granted' as NotificationPermission),
    };
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.removeItem('locklane.notificationsEnabled');
    (window as unknown as { Notification?: typeof Notification }).Notification = originalNotification;
  });

  it('is unavailable, and touches nothing, without a service worker', () => {
    configure(null);

    const service = TestBed.inject(PushService);
    TestBed.flushEffects();

    expect(service.status()).toBe('unavailable');
    httpMock.expectNone('/api/push/vapid-public-key');
  });

  it('is unavailable when the service worker is not enabled (a dev build)', () => {
    configure(fakeSwPush(false));

    const service = TestBed.inject(PushService);
    TestBed.flushEffects();

    expect(service.status()).toBe('unavailable');
    httpMock.expectNone('/api/push/vapid-public-key');
  });

  it('turning the preference on subscribes with the engine key and registers the subscription', async () => {
    const swPush = fakeSwPush(true);
    configure(swPush);
    const service = TestBed.inject(PushService);
    TestBed.inject(NotificationsStore).setEnabled(true);
    await flushMicrotasks();

    TestBed.flushEffects();
    httpMock.expectOne('/api/push/vapid-public-key').flush({ publicKey: 'BP4z-key' });
    await flushMicrotasks();

    expect(swPush.requestSubscription).toHaveBeenCalledWith({ serverPublicKey: 'BP4z-key' });
    const registration = httpMock.expectOne('/api/push/subscriptions');
    expect(registration.request.method).toBe('POST');
    expect(registration.request.body).toEqual({ endpoint: 'https://push.example.net/send/abc', keys: { p256dh: 'BCVx', auth: 'BTBZ' } });
    registration.flush(null, { status: 204, statusText: 'No Content' });
    expect(service.status()).toBe('subscribed');
  });

  it('a preference already on re-registers on start, so a dropped subscription comes back', () => {
    localStorage.setItem('locklane.notificationsEnabled', 'true');
    const swPush = fakeSwPush(true);
    configure(swPush);

    TestBed.inject(PushService);
    TestBed.flushEffects();

    httpMock.expectOne('/api/push/vapid-public-key').flush({ publicKey: 'BP4z-key' });
  });

  it('the browser refusing a subscription is reported as failed, not thrown', async () => {
    const swPush = fakeSwPush(true);
    swPush.requestSubscription.and.rejectWith(new DOMException('denied', 'NotAllowedError'));
    configure(swPush);
    const service = TestBed.inject(PushService);
    TestBed.inject(NotificationsStore).setEnabled(true);
    await flushMicrotasks();

    TestBed.flushEffects();
    httpMock.expectOne('/api/push/vapid-public-key').flush({ publicKey: 'BP4z-key' });
    await flushMicrotasks();

    expect(service.status()).toBe('failed');
    httpMock.expectNone('/api/push/subscriptions');
  });

  it('with the preference off, an existing subscription is deregistered and dropped', () => {
    const swPush = fakeSwPush(true, FAKE_SUBSCRIPTION);
    configure(swPush);

    const service = TestBed.inject(PushService);
    TestBed.flushEffects();

    const removal = httpMock.expectOne('/api/push/subscriptions');
    expect(removal.request.method).toBe('DELETE');
    expect(removal.request.body).toEqual({ endpoint: 'https://push.example.net/send/abc' });
    removal.flush(null, { status: 204, statusText: 'No Content' });
    expect(swPush.unsubscribe).toHaveBeenCalled();
    expect(service.status()).toBe('off');
  });

  it('with the preference off and nothing subscribed, nothing is sent', () => {
    const swPush = fakeSwPush(true, null);
    configure(swPush);

    TestBed.inject(PushService);
    TestBed.flushEffects();

    httpMock.expectNone('/api/push/subscriptions');
    expect(swPush.unsubscribe).not.toHaveBeenCalled();
  });
});
