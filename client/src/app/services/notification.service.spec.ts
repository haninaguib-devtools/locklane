import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { SwPush } from '@angular/service-worker';
import { Subject } from 'rxjs';
import { NotificationService, agentTargetOf, notificationContentFor } from './notification.service';
import { AttentionStore } from './attention-store';
import { NotificationsStore } from './notifications-store';
import { ActiveAgentSessionStore } from './active-agent-session-store';
import { GhIssue, Project } from '../models/issue.model';
import { routes } from '../app.routes';

// Session ids ("<projectId>-console[-<hex>]") and the 'console' route segment below
// keep their persisted and on-the-wire shape: compatibility surfaces kept under
// ADR-112 (#766 renamed only the identifiers).

describe('NotificationService (#859)', () => {
  let httpMock: HttpTestingController;
  let notifications: FakeNotification[];
  // #860: what the service worker relays to the page -- a pushed notification's
  // payload, and a click on one. Not enabled, so PushService never subscribes here.
  let pushMessages: Subject<object>;
  let pushClicks: Subject<{ action: string; notification: NotificationOptions & { title: string } }>;

  const PROJECT_A: Project = {
    id: 1,
    name: 'Alpha',
    gitUrl: 'url-a',
    workareaPath: '/tmp/a',
    defaultBranch: 'main',
    accentColor: null,
    template: null,
    status: 'READY',
    createdAt: '',
  };

  /** A controllable stand-in for the real `Notification`, tracking every instance made. */
  class FakeNotification {
    onclick: (() => void) | null = null;
    closed = false;
    constructor(
      public title: string,
      public options: NotificationOptions,
    ) {
      notifications.push(this);
    }
    close(): void {
      this.closed = true;
    }
    static permission: NotificationPermission = 'granted';
    static requestPermission = (): Promise<NotificationPermission> => Promise.resolve('granted');
  }

  /** Flushes every microtask still pending (a settled Promise's `.then()` chain). */
  async function flushMicrotasks(): Promise<void> {
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
  }

  beforeEach(async () => {
    localStorage.removeItem('locklane.notificationsEnabled');
    localStorage.removeItem('locklane.activeConsoleByIssue');
    notifications = [];
    FakeNotification.requestPermission = jasmine.createSpy().and.resolveTo('granted' as NotificationPermission);
    FakeNotification.permission = 'granted';
    (window as unknown as { Notification: unknown }).Notification = FakeNotification;

    pushMessages = new Subject<object>();
    pushClicks = new Subject<{ action: string; notification: NotificationOptions & { title: string } }>();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter(routes),
        { provide: SwPush, useValue: { isEnabled: false, messages: pushMessages, notificationClicks: pushClicks } },
      ],
    });
    httpMock = TestBed.inject(HttpTestingController);

    // Deterministic: never let a real service worker registration (if this browser
    // happens to have one from another page under the same origin) change which
    // path `show()`/`close()` take.
    if ('serviceWorker' in navigator) {
      spyOn(navigator.serviceWorker, 'getRegistration').and.resolveTo(undefined);
    }

    // setEnabled(true) resolves the permission request asynchronously (a real
    // Notification.requestPermission() promise, even here against the fake) --
    // awaited so every test's synchronous body sees `enabled()` already true,
    // never a false negative from a not-yet-settled microtask.
    TestBed.inject(NotificationsStore).setEnabled(true);
    await flushMicrotasks();
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.removeItem('locklane.notificationsEnabled');
    localStorage.removeItem('locklane.activeConsoleByIssue');
  });

  function issue(number: number, title: string): GhIssue {
    return { number, title, state: 'OPEN', labels: [], body: '', createdAt: '', updatedAt: '' };
  }

  function setVisibility(state: DocumentVisibilityState): void {
    spyOnProperty(document, 'visibilityState', 'get').and.returnValue(state);
  }

  /**
   * Fires a bell event for `sessionId`, flushes the fetch cycle `maybeNotify`
   * triggers (`/api/projects` then one project's own three calls), and settles the
   * microtask chain `show()`'s own `serviceWorker.getRegistration().then(...)`
   * needs before a notification actually appears.
   */
  async function ringBell(
    sessionId: string,
    issues: GhIssue[] = [issue(7, 'Seven')],
    ids: string[] = ['1-7-rename-toggle'],
    message?: string,
  ): Promise<void> {
    TestBed.inject(AttentionStore).apply({
      type: 'consoleAttention',
      sessionId,
      state: 'waiting',
      reason: 'bell',
      ...(message !== undefined ? { message } : {}),
    });
    httpMock.expectOne('/api/projects').flush([PROJECT_A]);
    httpMock.expectOne('/api/projects/1/consoles').flush(ids);
    httpMock.expectOne('/api/projects/1/issues').flush(issues);
    httpMock.expectOne('/api/projects/1/console/sessions').flush([]);
    await flushMicrotasks();
  }

  it('notificationContentFor: an issue entry names the issue in the title, its own title as the body', () => {
    expect(notificationContentFor({ sessionId: '1-7-x', projectId: 1, projectName: 'Alpha', issueNumber: 7, title: 'Seven' })).toEqual({
      title: 'Agent on #7 is waiting',
      body: 'Seven',
    });
  });

  it('notificationContentFor: a project agent entry has no issue to name, and the project name as the body', () => {
    expect(
      notificationContentFor({ sessionId: '1-console-a', projectId: 1, projectName: 'Alpha', issueNumber: null, title: 'Project - agent' }),
    ).toEqual({ title: 'Agent is waiting', body: 'Alpha' });
  });

  it('notificationContentFor: the agent message replaces the body when present (#861)', () => {
    expect(
      notificationContentFor(
        { sessionId: '1-7-x', projectId: 1, projectName: 'Alpha', issueNumber: 7, title: 'Seven' },
        'Merge PR #851 into main?',
      ),
    ).toEqual({ title: 'Agent on #7 is waiting', body: 'Merge PR #851 into main?' });
  });

  it('a bell shows a notification', async () => {
    TestBed.inject(NotificationService);

    await ringBell('1-7-rename-toggle');

    expect(notifications.length).toBe(1);
    expect(notifications[0].title).toBe('Agent on #7 is waiting');
    expect(notifications[0].options.body).toBe('Seven');
    expect(notifications[0].options.tag).toBe('1-7-rename-toggle');
  });

  it('a bell with the agent message shows it as the body (#861)', async () => {
    TestBed.inject(NotificationService);

    await ringBell('1-7-rename-toggle', [issue(7, 'Seven')], ['1-7-rename-toggle'], 'Merge PR #851 into main?');

    expect(notifications.length).toBe(1);
    expect(notifications[0].title).toBe('Agent on #7 is waiting');
    expect(notifications[0].options.body).toBe('Merge PR #851 into main?');
  });

  it('quiet shows nothing, and fetches nothing at all', () => {
    TestBed.inject(NotificationService);

    TestBed.inject(AttentionStore).apply({ type: 'consoleAttention', sessionId: '1-7-rename-toggle', state: 'waiting', reason: 'quiet' });

    expect(notifications.length).toBe(0);
    // Not even CurrentProjectService's own construction-time fetch: `maybeNotify`
    // is never called at all for a `quiet` change, so its lazily-injected
    // CurrentProjectService is never touched either.
    httpMock.expectNone('/api/projects');
  });

  it('a shell tab (no matching agent session entry) shows nothing', async () => {
    TestBed.inject(NotificationService);

    await ringBell('1-shell-abc', [], []);

    expect(notifications.length).toBe(0);
  });

  it('the active tab with the document visible shows nothing', async () => {
    setVisibility('visible');
    TestBed.inject(ActiveAgentSessionStore).set(7, '1-7-rename-toggle');
    const router = TestBed.inject(Router);
    TestBed.inject(NotificationService);
    await router.navigateByUrl('/projects/1/issues/7');

    await ringBell('1-7-rename-toggle');

    expect(notifications.length).toBe(0);
  });

  it('the same session waiting while a different issue is on screen still shows a notification', async () => {
    setVisibility('visible');
    TestBed.inject(ActiveAgentSessionStore).set(7, '1-7-rename-toggle');
    const router = TestBed.inject(Router);
    TestBed.inject(NotificationService);
    await router.navigateByUrl('/projects/1/issues/9');

    await ringBell('1-7-rename-toggle');

    expect(notifications.length).toBe(1);
  });

  it('the active tab while the document is hidden still shows a notification', async () => {
    setVisibility('hidden');
    TestBed.inject(ActiveAgentSessionStore).set(7, '1-7-rename-toggle');
    const router = TestBed.inject(Router);
    TestBed.inject(NotificationService);
    await router.navigateByUrl('/projects/1/issues/7');

    await ringBell('1-7-rename-toggle');

    expect(notifications.length).toBe(1);
  });

  it('a project agent session already on screen and visible shows nothing', async () => {
    setVisibility('visible');
    const router = TestBed.inject(Router);
    TestBed.inject(NotificationService);
    await router.navigateByUrl('/projects/1/console');

    TestBed.inject(AttentionStore).apply({ type: 'consoleAttention', sessionId: '1-console-a1b2c3d4', state: 'waiting', reason: 'bell' });
    httpMock.expectOne('/api/projects').flush([PROJECT_A]);
    httpMock.expectOne('/api/projects/1/consoles').flush([]);
    httpMock.expectOne('/api/projects/1/issues').flush([]);
    httpMock
      .expectOne('/api/projects/1/console/sessions')
      .flush([{ sessionId: '1-console-a1b2c3d4', workingDirectory: '/tmp', createdAt: '', lastAttachedAt: '', displayName: null }]);
    await flushMicrotasks();

    expect(notifications.length).toBe(0);
  });

  it('the session going active closes the shown notification', async () => {
    TestBed.inject(NotificationService);
    await ringBell('1-7-rename-toggle');
    expect(notifications.length).toBe(1);

    TestBed.inject(AttentionStore).apply({ type: 'consoleAttention', sessionId: '1-7-rename-toggle', state: 'active' });

    expect(notifications[0].closed).toBeTrue();
  });

  it('clicking the notification focuses the window, navigates to the agent, and closes it', async () => {
    const router = TestBed.inject(Router);
    const navigateSpy = spyOn(router, 'navigate');
    const focusSpy = spyOn(window, 'focus');
    TestBed.inject(NotificationService);

    await ringBell('1-7-rename-toggle');
    notifications[0].onclick?.();

    expect(focusSpy).toHaveBeenCalled();
    expect(navigateSpy).toHaveBeenCalledWith(['/projects', 1, 'issues', 7]);
    expect(TestBed.inject(ActiveAgentSessionStore).get(7)).toBe('1-7-rename-toggle');
    expect(notifications[0].closed).toBeTrue();
  });

  it('does nothing when the preference is off', () => {
    TestBed.inject(NotificationsStore).setEnabled(false);
    TestBed.inject(NotificationService);

    TestBed.inject(AttentionStore).apply({ type: 'consoleAttention', sessionId: '1-7-rename-toggle', state: 'waiting', reason: 'bell' });

    expect(notifications.length).toBe(0);
    httpMock.expectNone('/api/projects');
  });

  it('does nothing when permission is not granted, even with the preference on', () => {
    FakeNotification.permission = 'denied';
    TestBed.inject(NotificationService);

    TestBed.inject(AttentionStore).apply({ type: 'consoleAttention', sessionId: '1-7-rename-toggle', state: 'waiting', reason: 'bell' });

    expect(notifications.length).toBe(0);
    httpMock.expectNone('/api/projects');
  });
  // #860: the same notification, pushed by the engine once the app is closed.
  describe('pushed notifications', () => {
    /** A fake service worker registration holding one notification for `tag`. */
    function registrationHolding(tag: string) {
      const held = { tag, close: jasmine.createSpy('close') };
      const getNotifications = jasmine.createSpy('getNotifications').and.callFake((filter?: { tag?: string }) =>
        Promise.resolve(filter?.tag === tag ? [held] : []),
      );
      (navigator.serviceWorker.getRegistration as jasmine.Spy).and.resolveTo({ getNotifications } as unknown as ServiceWorkerRegistration);
      return { held, getNotifications };
    }

    it('agentTargetOf reads the agent out of a session id, and nothing out of a shell', () => {
      expect(agentTargetOf('12-345-fix-the-thing')).toEqual({ sessionId: '12-345-fix-the-thing', projectId: 12, issueNumber: 345 });
      expect(agentTargetOf('12-console')).toEqual({ sessionId: '12-console', projectId: 12, issueNumber: null });
      expect(agentTargetOf('12-console-a1b2c3d4')).toEqual({ sessionId: '12-console-a1b2c3d4', projectId: 12, issueNumber: null });
      expect(agentTargetOf('12-shell-345-a1b2c3d4')).toBeNull();
      expect(agentTargetOf('nonsense')).toBeNull();
    });

    it('a push for the agent on screen, with the document visible, is closed again', async () => {
      setVisibility('visible');
      TestBed.inject(ActiveAgentSessionStore).set(7, '1-7-rename-toggle');
      await TestBed.inject(Router).navigateByUrl('/projects/1/issues/7');
      TestBed.inject(NotificationService);
      const { held } = registrationHolding('1-7-rename-toggle');

      pushMessages.next({ notification: { tag: '1-7-rename-toggle', title: 'Agent on #7 is waiting' } });
      await flushMicrotasks();

      expect(held.close).toHaveBeenCalled();
    });

    it('a push for a different agent, or with the document hidden, is left showing', async () => {
      setVisibility('hidden');
      TestBed.inject(ActiveAgentSessionStore).set(7, '1-7-rename-toggle');
      await TestBed.inject(Router).navigateByUrl('/projects/1/issues/7');
      TestBed.inject(NotificationService);
      const { held, getNotifications } = registrationHolding('1-7-rename-toggle');

      pushMessages.next({ notification: { tag: '1-7-rename-toggle', title: 'Agent on #7 is waiting' } });
      pushMessages.next({ notification: { tag: '1-9-other', title: 'Agent on #9 is waiting' } });
      pushMessages.next({ notification: { tag: '1-shell-7-a1b2c3d4', title: 'nonsense' } });
      await flushMicrotasks();

      expect(getNotifications).not.toHaveBeenCalled();
      expect(held.close).not.toHaveBeenCalled();
    });

    it('a click on a pushed notification lands on the agent the way the in-app click does', async () => {
      const router = TestBed.inject(Router);
      const navigateSpy = spyOn(router, 'navigate');
      const focusSpy = spyOn(window, 'focus');
      TestBed.inject(NotificationService);

      pushClicks.next({
        action: '',
        notification: {
          title: 'Agent on #7 is waiting',
          data: { sessionId: '1-7-rename-toggle', onActionClick: { default: { operation: 'focusLastFocusedOrOpen', url: '/projects/1/issues/7' } } },
        },
      });
      httpMock.expectOne('/api/projects').flush([PROJECT_A]);
      httpMock.expectOne('/api/projects/1/consoles').flush(['1-7-rename-toggle']);
      httpMock.expectOne('/api/projects/1/issues').flush([issue(7, 'Seven')]);
      httpMock.expectOne('/api/projects/1/console/sessions').flush([]);
      await flushMicrotasks();

      expect(focusSpy).toHaveBeenCalled();
      expect(navigateSpy).toHaveBeenCalledWith(['/projects', 1, 'issues', 7]);
      expect(TestBed.inject(ActiveAgentSessionStore).get(7)).toBe('1-7-rename-toggle');
    });

    it('a click for an agent that has since gone falls back to the URL the worker carried', async () => {
      const router = TestBed.inject(Router);
      const navigateByUrlSpy = spyOn(router, 'navigateByUrl');
      spyOn(window, 'focus');
      TestBed.inject(NotificationService);

      pushClicks.next({
        action: '',
        notification: {
          title: 'Agent on #7 is waiting',
          data: { sessionId: '1-7-rename-toggle', onActionClick: { default: { operation: 'focusLastFocusedOrOpen', url: '/projects/1/issues/7' } } },
        },
      });
      httpMock.expectOne('/api/projects').flush([PROJECT_A]);
      httpMock.expectOne('/api/projects/1/consoles').flush([]);
      httpMock.expectOne('/api/projects/1/issues').flush([]);
      httpMock.expectOne('/api/projects/1/console/sessions').flush([]);
      await flushMicrotasks();

      expect(navigateByUrlSpy).toHaveBeenCalledWith('/projects/1/issues/7');
    });

    it('a click carrying no session does nothing', () => {
      TestBed.inject(NotificationService);
      const focusSpy = spyOn(window, 'focus');

      pushClicks.next({ action: '', notification: { title: 'something else' } });

      expect(focusSpy).not.toHaveBeenCalled();
      httpMock.expectNone('/api/projects');
    });
  });
});
