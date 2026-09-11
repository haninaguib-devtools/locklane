import { Injectable, Injector, inject } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { SwPush } from '@angular/service-worker';
import { take } from 'rxjs';
import { AttentionStore } from './attention-store';
import { NotificationsStore } from './notifications-store';
import { AgentSessionEntriesService, AgentSessionEntry } from './agent-session-entries.service';
import { ActiveAgentSessionStore } from './active-agent-session-store';
import { CurrentProjectService } from './current-project.service';
import { PushService } from './push.service';

/** What identifies the agent a session id names, without fetching anything (#860). */
export type AgentTarget = Pick<AgentSessionEntry, 'sessionId' | 'projectId' | 'issueNumber'>;

/**
 * The agent a session id names (#860), from the id's own shape -- "<projectId>-<issue>-…"
 * for an issue's agent, "<projectId>-console[-…]" for a project agent ('console' is the
 * persisted id shape, a compatibility surface kept under ADR-112) -- or `null` for a
 * shell or anything else. Mirrors the engine's own PushNotifier.targetOf.
 * Exported for the spec; not otherwise used outside this file.
 */
export function agentTargetOf(sessionId: string): AgentTarget | null {
  const issue = /^(\d+)-(\d+)-/.exec(sessionId);
  if (issue) {
    return { sessionId, projectId: Number(issue[1]), issueNumber: Number(issue[2]) };
  }
  const projectAgent = /^(\d+)-console(-.+)?$/.exec(sessionId);
  if (projectAgent) {
    return { sessionId, projectId: Number(projectAgent[1]), issueNumber: null };
  }
  return null;
}

/** The payload the engine pushes (#860): the notification ngsw-worker.js showed, with the session in its data. */
interface PushPayload {
  notification?: { tag?: string; data?: { sessionId?: unknown; onActionClick?: { default?: { url?: unknown } } } };
}

/** The title/body pair a notification shows for one waiting entry (#859). */
export interface NotificationContent {
  title: string;
  body: string;
}

/**
 * Builds the title/body a notification shows for one waiting agent session (#859):
 * "Agent on #<issue> is waiting" with the issue's own title as the body for an
 * issue's agent session, or the plain "Agent is waiting" with the project's name as
 * the body for a project agent session -- there is no issue number to name in the
 * title there. *Agent*, never *session* or *console*, in either line (ADR-112).
 * Exported for the spec; not otherwise used outside this file.
 */
export function notificationContentFor(entry: AgentSessionEntry): NotificationContent {
  return entry.issueNumber !== null
    ? { title: `Agent on #${entry.issueNumber} is waiting`, body: entry.title }
    : { title: 'Agent is waiting', body: entry.projectName };
}

/**
 * Shows a browser notification when an agent rings the bell and its tab is not in
 * view (#859) -- the one place in the client that calls the Notification API.
 * Constructed from {@link AgentSessionIndicatorComponent} (unused beyond
 * construction there): that component only ever mounts once signed in
 * (`app.component.html`'s `@else` branch), which is what makes it the safe place to
 * start this watching {@link AttentionStore.changes$}.
 *
 * Nothing here fetches anything until a bell actually rings and passes the
 * enabled/permission checks below: {@link CurrentProjectService} is read through a
 * lazy getter, not an eager field, the same reason {@code AppComponent}'s own
 * `currentProject` getter exists -- constructing it fires its own `/api/projects`
 * fetch immediately, and this service is itself constructed the moment the
 * indicator mounts, before any bell has necessarily rung at all. Deliberately keeps
 * no background cache of every open agent session either: a session's {@link
 * AgentSessionEntry} (needed for the notification's own title/body and for
 * navigating to it) is fetched fresh, through {@link AgentSessionEntriesService},
 * only at that moment -- across every project the user has, never narrowed to a
 * popped-out focused window the way the header indicator's own fetch is, since a
 * background notification is not scoped to whichever window happens to be focused.
 * A session id that fetch does not recognise as an entry (a shell) is never
 * notified for.
 *
 * A notification is shown only when: the preference is on ({@link
 * NotificationsStore.enabled}) and permission is actually granted; the session is a
 * known agent session (not a shell); and the session is not already the one on
 * screen with the document visible -- the engine's own bell detection fires
 * regardless of client-side focus (#130), so a tab already being looked at would
 * otherwise still trigger a redundant system notification for something the user is
 * already seeing.
 *
 * <p>Once the app is closed, the same notification arrives by Web Push instead
 * (#860): the engine pushes it, and the service worker shows it with no page
 * involved. Two things are still the page's to do when one *is* open: a pushed
 * notification for the very agent on screen is closed again (the engine cannot see
 * focus; the worker shows it before this code hears of it), and a click on a
 * pushed notification, which the worker answers by focusing this window, is routed
 * to the agent the way the in-app one is. Constructing {@link PushService} here is
 * also what keeps the browser's subscription in step with the preference.
 */
@Injectable({ providedIn: 'root' })
export class NotificationService {
  private readonly attentionStore = inject(AttentionStore);
  private readonly notificationsStore = inject(NotificationsStore);
  private readonly agentSessionEntries = inject(AgentSessionEntriesService);
  private readonly activeAgentSessionStore = inject(ActiveAgentSessionStore);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly injector = inject(Injector);
  // Absent without provideServiceWorker (a test, or a context with no worker at
  // all): then nothing is ever pushed to this page and there is nothing to hear.
  private readonly swPush = inject(SwPush, { optional: true });
  // Constructed for its effect alone (#860) -- see the class comment.
  private readonly pushService = inject(PushService);

  // Injected lazily, on first real need (a bell that passes the enabled/permission
  // checks below) rather than as an eager field -- the same reason AppComponent's
  // own `currentProject` getter exists: constructing CurrentProjectService fires its
  // own `/api/projects` fetch immediately, and this service is constructed the
  // moment AgentSessionIndicatorComponent mounts, before any bell has necessarily
  // rung at all.
  private get currentProject(): CurrentProjectService {
    return this.injector.get(CurrentProjectService);
  }

  // Handles for a notification shown via the plain constructor (#859) -- the only
  // path this class can reliably close or attach a click handler to directly; one
  // shown through an active service worker registration is instead closed by tag
  // (see `close()`), and (a real-browser platform limitation, recorded in the task's
  // own record) is not wired to navigate on click the same way.
  private readonly shown = new Map<string, Notification>();

  constructor() {
    this.attentionStore.changes$.subscribe((change) => {
      if (!change.waiting) {
        this.close(change.sessionId);
        return;
      }
      if (change.reason === 'bell') {
        this.maybeNotify(change.sessionId);
      }
    });
    this.swPush?.messages.subscribe((message) => this.onPushMessage(message as PushPayload));
    this.swPush?.notificationClicks.subscribe(({ notification }) => this.onPushClick(notification as PushPayload['notification']));
  }

  /**
   * A push the worker just showed (#860): if it is for the agent on screen right
   * now, with the document visible, close it again -- the same silence rule
   * `maybeNotify` applies before showing an in-app one, applied after the fact,
   * since the engine cannot know what this window is looking at.
   */
  private onPushMessage(message: PushPayload): void {
    const tag = message?.notification?.tag;
    if (typeof tag !== 'string') {
      return;
    }
    const target = agentTargetOf(tag);
    if (target && this.isCurrentlyViewedAndVisible(target)) {
      this.close(tag);
    }
  }

  /**
   * A click on a pushed notification, with this window open (#860): the worker has
   * focused the window; landing on the agent is done here, through the same
   * `jumpTo` the in-app click uses, so an issue's agent tab is selected exactly.
   * A session gone since (the entry is not found) falls back to the URL the
   * worker itself would have opened had no window been open.
   */
  private onPushClick(notification: PushPayload['notification']): void {
    const sessionId = notification?.data?.sessionId;
    if (typeof sessionId !== 'string') {
      return;
    }
    window.focus();
    this.currentProject.projects$.pipe(take(1)).subscribe((projects) => {
      this.agentSessionEntries.fetchEntries(projects).subscribe((entries) => {
        const entry = entries.find((candidate) => candidate.sessionId === sessionId);
        if (entry) {
          this.agentSessionEntries.jumpTo(entry);
          return;
        }
        const url = notification?.data?.onActionClick?.default?.url;
        if (typeof url === 'string') {
          this.router.navigateByUrl(url);
        }
      });
    });
  }

  private maybeNotify(sessionId: string): void {
    if (!this.notificationsStore.enabled()) {
      return;
    }
    if (typeof Notification === 'undefined' || Notification.permission !== 'granted') {
      return;
    }
    this.currentProject.projects$.pipe(take(1)).subscribe((projects) => {
      this.agentSessionEntries.fetchEntries(projects).subscribe((entries) => {
        const entry = entries.find((candidate) => candidate.sessionId === sessionId);
        if (!entry) {
          // A shell -- never notified.
          return;
        }
        if (this.isCurrentlyViewedAndVisible(entry)) {
          return;
        }
        const { title, body } = notificationContentFor(entry);
        this.show(entry, title, body);
      });
    });
  }

  private show(entry: AgentSessionEntry, title: string, body: string): void {
    const options: NotificationOptions = { body, tag: entry.sessionId };
    if ('serviceWorker' in navigator) {
      navigator.serviceWorker
        .getRegistration()
        .then((registration) => {
          if (registration) {
            registration.showNotification(title, options);
          } else {
            this.showViaConstructor(entry, title, options);
          }
        })
        .catch(() => this.showViaConstructor(entry, title, options));
    } else {
      this.showViaConstructor(entry, title, options);
    }
  }

  private showViaConstructor(entry: AgentSessionEntry, title: string, options: NotificationOptions): void {
    const notification = new Notification(title, options);
    notification.onclick = () => {
      window.focus();
      this.agentSessionEntries.jumpTo(entry);
      notification.close();
    };
    this.shown.set(entry.sessionId, notification);
  }

  private close(sessionId: string): void {
    const notification = this.shown.get(sessionId);
    if (notification) {
      notification.close();
      this.shown.delete(sessionId);
    }
    if ('serviceWorker' in navigator) {
      navigator.serviceWorker
        .getRegistration()
        .then((registration) => registration?.getNotifications({ tag: sessionId }))
        .then((matching) => matching?.forEach((n) => n.close()))
        .catch(() => {
          // silent: nothing productive to do if the registration can't be reached;
          // the notification (if any) simply outlives the session going active.
        });
    }
  }

  /**
   * Whether `entry`'s session is the one on screen right now, with the document
   * visible (#859) -- an issue's agent session is "on screen" when the route is on
   * that exact issue and it is the issue's remembered active agent session tab
   * ({@link ActiveAgentSessionStore}); a project agent session, when the route is on
   * that exact project's agent-session page.
   */
  private isCurrentlyViewedAndVisible(entry: AgentTarget): boolean {
    if (document.visibilityState !== 'visible') {
      return false;
    }
    if (entry.issueNumber !== null) {
      return this.currentIssueNumber() === entry.issueNumber && this.activeAgentSessionStore.get(entry.issueNumber) === entry.sessionId;
    }
    return this.currentProjectId() === entry.projectId && this.onProjectAgentSessionRoute();
  }

  private currentIssueNumber(): number | null {
    const raw = this.route.snapshot.firstChild?.paramMap.get('id') ?? null;
    const id = raw !== null ? Number(raw) : NaN;
    return Number.isFinite(id) ? id : null;
  }

  private currentProjectId(): number | null {
    const raw = this.route.snapshot.firstChild?.paramMap.get('projectId') ?? null;
    const id = raw !== null ? Number(raw) : NaN;
    return Number.isFinite(id) ? id : null;
  }

  // 'console' is the route path segment -- a compatibility surface kept under ADR-112.
  private onProjectAgentSessionRoute(): boolean {
    const segments = this.route.snapshot.firstChild?.url ?? [];
    return segments.some((segment) => segment.path === 'console');
  }
}
