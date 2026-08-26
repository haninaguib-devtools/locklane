import { Injectable } from '@angular/core';

const STORAGE_KEY = 'locklane.collapsedInitiatives';

/**
 * Which initiatives are folded shut, per project (#44 -- keyed the same way as
 * PinStore, for the same reason: a bare issue number would fold "#1" across every
 * project's section at once). Persisted in localStorage (#22).
 */
@Injectable({ providedIn: 'root' })
export class CollapseStore {
  private collapsed = new Set<string>(load());

  isCollapsed(projectId: number, issueNumber: number): boolean {
    return this.collapsed.has(key(projectId, issueNumber));
  }

  toggle(projectId: number, issueNumber: number): void {
    const k = key(projectId, issueNumber);
    if (this.collapsed.has(k)) {
      this.collapsed.delete(k);
    } else {
      this.collapsed.add(k);
    }
    save(this.collapsed);
  }
}

function key(projectId: number, issueNumber: number): string {
  return `${projectId}-${issueNumber}`;
}

function load(): string[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    const parsed = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed.filter((k) => typeof k === 'string') : [];
  } catch {
    return [];
  }
}

function save(collapsed: Set<string>): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify([...collapsed]));
  } catch {
    // Storage unavailable -- folding still works for this session.
  }
}
