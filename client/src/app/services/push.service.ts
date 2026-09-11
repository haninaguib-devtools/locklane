import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject, signal } from '@angular/core';
import { SwPush } from '@angular/service-worker';
import { take } from 'rxjs';
import { NotificationsStore } from './notifications-store';

/**
 * Where this browser stands on being notified with the app closed (#860):
 * `unavailable` -- no active service worker here (a dev build, a plain-http page,
 * a browser without push); `off` -- the preference is off, nothing registered;
 * `subscribed` -- the engine holds this browser's subscription; `failed` -- the
 * preference is on but the browser refused a push subscription (iOS needs the app
 * added to the Home Screen first) or the engine could not be reached.
 */
export type PushStatus = 'unavailable' | 'off' | 'subscribed' | 'failed';

/**
 * Keeps this browser's Web Push subscription in step with the one notification
 * preference (#860): whenever "Notify me when an agent is waiting" is on --
 * including on every app start while it stays on, since a browser can silently
 * drop a subscription -- the browser subscribes through the service worker with
 * the engine's VAPID public key and the engine is told; whenever it is off, the
 * subscription is dropped on both sides. One toggle, both delivery paths: the
 * in-app notification (#859) while a tab is open, a pushed one once the app is
 * closed.
 *
 * Nothing here runs without an active service worker ({@link SwPush.isEnabled}
 * is false in a dev build, where none is registered), and {@link SwPush} itself is
 * optional so a test or a context without `provideServiceWorker` simply reports
 * `unavailable`. A failure never surfaces as an error: the in-app path is
 * untouched by it, and the settings dialog shows {@link status} instead.
 */
@Injectable({ providedIn: 'root' })
export class PushService {
  private readonly http = inject(HttpClient);
  private readonly swPush = inject(SwPush, { optional: true });
  private readonly notificationsStore = inject(NotificationsStore);
  private readonly statusSignal = signal<PushStatus>(this.swPush?.isEnabled ? 'off' : 'unavailable');

  readonly status = this.statusSignal.asReadonly();

  constructor() {
    effect(() => this.sync(this.notificationsStore.enabled()));
  }

  private sync(enabled: boolean): void {
    const swPush = this.swPush;
    if (!swPush?.isEnabled) {
      return;
    }
    if (enabled) {
      this.subscribe(swPush);
    } else {
      this.unsubscribe(swPush);
    }
  }

  private subscribe(swPush: SwPush): void {
    this.http.get<{ publicKey: string }>('/api/push/vapid-public-key').subscribe({
      next: ({ publicKey }) =>
        swPush
          .requestSubscription({ serverPublicKey: publicKey })
          .then((subscription) => this.register(subscription))
          .catch(() => this.statusSignal.set('failed')),
      error: () => this.statusSignal.set('failed'),
    });
  }

  private register(subscription: PushSubscription): void {
    // The browser's own JSON shape -- {endpoint, keys: {p256dh, auth}} -- is what
    // the engine stores; nothing is reshaped on the way.
    this.http.post('/api/push/subscriptions', subscription.toJSON()).subscribe({
      next: () => this.statusSignal.set('subscribed'),
      error: () => this.statusSignal.set('failed'),
    });
  }

  private unsubscribe(swPush: SwPush): void {
    swPush.subscription.pipe(take(1)).subscribe((subscription) => {
      this.statusSignal.set('off');
      if (!subscription) {
        return;
      }
      this.http.delete('/api/push/subscriptions', { body: { endpoint: subscription.endpoint } }).subscribe({
        error: () => {
          // silent: the engine prunes a subscription itself the first time the push
          // service reports it gone, so an unreached DELETE is not the end of it.
        },
      });
      swPush.unsubscribe().catch(() => {
        // silent: nothing left to do -- the browser either dropped it or never had it.
      });
    });
  }
}
