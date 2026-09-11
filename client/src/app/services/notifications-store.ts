import { Injectable, signal } from '@angular/core';

const STORAGE_KEY = 'locklane.notificationsEnabled';

/**
 * Whether the user has turned on "Notify me when an agent is waiting" (#859),
 * client-only and persisted in localStorage -- consistent with
 * {@link DefaultAgentStore}, there is nothing server-side to keep this in sync with.
 *
 * Requesting the browser's notification permission happens here, and only from
 * {@link setEnabled} -- i.e. only when the settings dialog's toggle is actually
 * flipped on, never on page load merely because a previous session left the
 * preference on: a page load re-reads whatever the browser's own permission state
 * already is (it survives a reload on its own; nothing to request), while turning
 * the toggle on is the one moment a fresh prompt is appropriate. Turning it on
 * without the browser granting permission leaves {@link enabled} false and
 * {@link permission} carrying whatever the browser decided (typically `"denied"`),
 * so the toggle can explain itself instead of silently doing nothing.
 */
@Injectable({ providedIn: 'root' })
export class NotificationsStore {
  private readonly enabledSignal = signal<boolean>(load());
  private readonly permissionSignal = signal<NotificationPermission>(currentPermission());

  readonly enabled = this.enabledSignal.asReadonly();
  readonly permission = this.permissionSignal.asReadonly();

  setEnabled(enabled: boolean): void {
    if (!enabled) {
      this.enabledSignal.set(false);
      save(false);
      return;
    }
    if (typeof Notification === 'undefined') {
      // Not supported in this browser -- leave enabled false; there is no
      // permission state to show either.
      return;
    }
    Notification.requestPermission().then((permission) => {
      this.permissionSignal.set(permission);
      const granted = permission === 'granted';
      this.enabledSignal.set(granted);
      save(granted);
    });
  }
}

function currentPermission(): NotificationPermission {
  try {
    return typeof Notification !== 'undefined' ? Notification.permission : 'denied';
  } catch {
    return 'denied';
  }
}

function load(): boolean {
  try {
    return localStorage.getItem(STORAGE_KEY) === 'true';
  } catch {
    return false;
  }
}

function save(enabled: boolean): void {
  try {
    localStorage.setItem(STORAGE_KEY, String(enabled));
  } catch {
    // Storage unavailable (private browsing, quota) -- the choice still works for this
    // session, it just won't survive a reload.
  }
}
