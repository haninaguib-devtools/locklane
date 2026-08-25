import { Injectable } from '@angular/core';

const STORAGE_KEY = 'locklane.activeConsoleByIssue';

/**
 * Which console tab was last active for a given issue, remembered
 * per-browser (#32) — not baked into the URL, since it's a personal,
 * ephemeral choice, not something worth putting in a link someone else might
 * open. Written by both the console tab bar and the header picker.
 */
@Injectable({ providedIn: 'root' })
export class ActiveConsoleStore {
  private active: Record<number, string> = load();

  get(issueNumber: number): string | null {
    return this.active[issueNumber] ?? null;
  }

  set(issueNumber: number, sessionId: string): void {
    this.active = { ...this.active, [issueNumber]: sessionId };
    save(this.active);
  }
}

function load(): Record<number, string> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    const parsed = raw ? JSON.parse(raw) : {};
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
      return {};
    }
    const valid: Record<number, string> = {};
    for (const [key, value] of Object.entries(parsed)) {
      const issueNumber = Number(key);
      if (Number.isFinite(issueNumber) && typeof value === 'string') {
        valid[issueNumber] = value;
      }
    }
    return valid;
  } catch {
    return {};
  }
}

function save(active: Record<number, string>): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(active));
  } catch {
    // Storage unavailable (private browsing, quota) -- tab memory still works
    // for this session, it just won't survive a reload.
  }
}
