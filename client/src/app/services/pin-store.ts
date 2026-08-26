import { Injectable } from '@angular/core';

const STORAGE_KEY = 'locklane.pinnedIssues';

export interface PinnedIssue {
  projectId: number;
  issueNumber: number;
}

/**
 * Which issues are pinned, most-recently-pinned first, per project (#44 -- the
 * sidenav shows every project at once, so a pin needs to say which project's
 * issue it is, not just a bare issue number). Client-only state, persisted in
 * localStorage -- never synced to the backend (#22's Non-goals).
 */
@Injectable({ providedIn: 'root' })
export class PinStore {
  private pinned: PinnedIssue[] = load();

  list(): PinnedIssue[] {
    return [...this.pinned];
  }

  isPinned(projectId: number, issueNumber: number): boolean {
    return this.pinned.some((p) => p.projectId === projectId && p.issueNumber === issueNumber);
  }

  toggle(projectId: number, issueNumber: number): void {
    this.pinned = this.isPinned(projectId, issueNumber)
      ? this.pinned.filter((p) => !(p.projectId === projectId && p.issueNumber === issueNumber))
      : [{ projectId, issueNumber }, ...this.pinned];
    save(this.pinned);
  }
}

function load(): PinnedIssue[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    const parsed = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed)
      ? parsed.filter(
          (p): p is PinnedIssue =>
            p && typeof p.projectId === 'number' && typeof p.issueNumber === 'number',
        )
      : [];
  } catch {
    return [];
  }
}

function save(pinned: PinnedIssue[]): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(pinned));
  } catch {
    // Storage unavailable (private browsing, quota) -- pinning still works for this
    // session, it just won't survive a reload.
  }
}
