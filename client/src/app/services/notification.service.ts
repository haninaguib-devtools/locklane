import { Injectable, Injector, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { take } from 'rxjs';
import { AttentionStore } from './attention-store';
import { NotificationsStore } from './notifications-store';
import { AgentSessionEntriesService, AgentSessionEntry } from './agent-session-entries.service';
import { ActiveAgentSessionStore } from './active-agent-session-store';
import { CurrentProjectService } from './current-project.service';

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
 */
@Injectable({ providedIn: 'root' })
export class NotificationService {
  private readonly attentionStore = inject(AttentionStore);
  private readonly notificationsStore = inject(NotificationsStore);
  private readonly agentSessionEntries = inject(AgentSessionEntriesService);
  private readonly activeAgentSessionStore = inject(ActiveAgentSessionStore);
  private readonly route = inject(ActivatedRoute);
  private readonly injector = inject(Injector);

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
  private isCurrentlyViewedAndVisible(entry: AgentSessionEntry): boolean {
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
