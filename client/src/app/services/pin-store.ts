import { Injectable } from '@angular/core';

const STORAGE_KEY = 'locklane.pinnedIssues';

/**
 * Which issues are pinned, most-recently-pinned first. Client-only state,
 * persisted in localStorage — never synced to the backend (#22's Non-goals).
 */
@Injectable({ providedIn: 'root' })
export class PinStore {
  private pinned: number[] = load();

  list(): number[] {
    return [...this.pinned];
  }

  isPinned(issueNumber: number): boolean {
    return this.pinned.includes(issueNumber);
  }

  toggle(issueNumber: number): void {
    this.pinned = this.isPinned(issueNumber)
      ? this.pinned.filter((n) => n !== issueNumber)
      : [issueNumber, ...this.pinned];
    save(this.pinned);
  }
}

function load(): number[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    const parsed = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed.filter((n) => typeof n === 'number') : [];
  } catch {
    return [];
  }
}

function save(pinned: number[]): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(pinned));
  } catch {
    // Storage unavailable (private browsing, quota) -- pinning still works for this
    // session, it just won't survive a reload.
  }
}
