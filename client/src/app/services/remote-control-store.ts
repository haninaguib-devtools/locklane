import { Injectable, signal } from '@angular/core';

const STORAGE_KEY = 'locklane.remoteControl';

/**
 * Whether a brand-new Claude Code session Locklane launches should carry Claude
 * Code's own `--remote-control` flag (#979), off by default, client-only and
 * persisted in localStorage -- consistent with {@link DefaultAgentStore} and {@link
 * NotificationsStore}, there is nothing server-side to keep this in sync with.
 * Claude-Code-specific: a caller building a launch for any other agent CLI never
 * reads this.
 */
@Injectable({ providedIn: 'root' })
export class RemoteControlStore {
  private readonly enabledSignal = signal<boolean>(load());

  readonly enabled = this.enabledSignal.asReadonly();

  setEnabled(enabled: boolean): void {
    this.enabledSignal.set(enabled);
    save(enabled);
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
