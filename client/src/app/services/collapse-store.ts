import { Injectable } from '@angular/core';

const STORAGE_KEY = 'locklane.collapsedInitiatives';

/** Which initiatives are folded shut, persisted in localStorage (#22). */
@Injectable({ providedIn: 'root' })
export class CollapseStore {
  private collapsed = new Set<number>(load());

  isCollapsed(issueNumber: number): boolean {
    return this.collapsed.has(issueNumber);
  }

  toggle(issueNumber: number): void {
    if (this.collapsed.has(issueNumber)) {
      this.collapsed.delete(issueNumber);
    } else {
      this.collapsed.add(issueNumber);
    }
    save(this.collapsed);
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

function save(collapsed: Set<number>): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify([...collapsed]));
  } catch {
    // Storage unavailable -- folding still works for this session.
  }
}
