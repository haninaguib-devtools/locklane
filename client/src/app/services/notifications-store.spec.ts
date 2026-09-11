import { TestBed } from '@angular/core/testing';
import { NotificationsStore } from './notifications-store';

describe('NotificationsStore (#859)', () => {
  let originalNotification: typeof Notification | undefined;

  beforeEach(() => {
    localStorage.removeItem('locklane.notificationsEnabled');
    originalNotification = (window as unknown as { Notification?: typeof Notification }).Notification;
    TestBed.configureTestingModule({});
  });

  afterEach(() => {
    localStorage.removeItem('locklane.notificationsEnabled');
    (window as unknown as { Notification?: typeof Notification }).Notification = originalNotification;
  });

  /** Replaces the global Notification constructor with a controllable fake. */
  function fakeNotification(requestPermission: () => Promise<NotificationPermission>, permission: NotificationPermission): void {
    (window as unknown as { Notification: unknown }).Notification = { requestPermission, permission };
  }

  it('is disabled with no stored preference', () => {
    const store = TestBed.inject(NotificationsStore);

    expect(store.enabled()).toBeFalse();
  });

  it('turning it off never requests permission', () => {
    const requestPermission = jasmine.createSpy().and.resolveTo('granted' as NotificationPermission);
    fakeNotification(requestPermission, 'default');
    const store = TestBed.inject(NotificationsStore);

    store.setEnabled(false);

    expect(requestPermission).not.toHaveBeenCalled();
    expect(store.enabled()).toBeFalse();
  });

  it('turning it on requests permission, and enables once granted', async () => {
    const requestPermission = jasmine.createSpy().and.resolveTo('granted' as NotificationPermission);
    fakeNotification(requestPermission, 'default');
    const store = TestBed.inject(NotificationsStore);

    store.setEnabled(true);
    await Promise.resolve();
    await Promise.resolve();

    expect(requestPermission).toHaveBeenCalled();
    expect(store.enabled()).toBeTrue();
    expect(store.permission()).toBe('granted');
  });

  it('turning it on but being denied leaves it disabled, with the denial visible', async () => {
    const requestPermission = jasmine.createSpy().and.resolveTo('denied' as NotificationPermission);
    fakeNotification(requestPermission, 'default');
    const store = TestBed.inject(NotificationsStore);

    store.setEnabled(true);
    await Promise.resolve();
    await Promise.resolve();

    expect(store.enabled()).toBeFalse();
    expect(store.permission()).toBe('denied');
  });

  it('survives a reload once enabled', async () => {
    const requestPermission = jasmine.createSpy().and.resolveTo('granted' as NotificationPermission);
    fakeNotification(requestPermission, 'default');
    const store = TestBed.inject(NotificationsStore);
    store.setEnabled(true);
    await Promise.resolve();
    await Promise.resolve();

    const reloaded = new NotificationsStore();

    expect(reloaded.enabled()).toBeTrue();
  });

  it('does nothing when Notification is unsupported', () => {
    (window as unknown as { Notification: unknown }).Notification = undefined;
    const store = TestBed.inject(NotificationsStore);

    store.setEnabled(true);

    expect(store.enabled()).toBeFalse();
  });
});
