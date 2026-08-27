import { Injectable } from '@angular/core';

const STORAGE_KEY = 'locklane.activeTabByIssue';

/**
 * Which tab (Overview, or an open console by session id) was last selected on
 * a given issue's page, remembered per-browser (#135) — returning to the
 * issue restores that tab instead of always opening on Overview.
 */
@Injectable({ providedIn: 'root' })
export class ActiveTabStore {
  private active: Record<number, string> = load();

  get(issueNumber: number): string | null {
    return this.active[issueNumber] ?? null;
  }

  set(issueNumber: number, tabId: string): void {
    this.active = { ...this.active, [issueNumber]: tabId };
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
