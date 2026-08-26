import { Injectable } from '@angular/core';

const STORAGE_KEY = 'locklane.collapsedProjectSections';

/**
 * Which project sections are folded shut in the sidenav (#44) -- a different
 * dimension from CollapseStore's per-initiative fold within a section.
 * Persisted in localStorage, per session as the other sidenav folds are (#22).
 */
@Injectable({ providedIn: 'root' })
export class ProjectSectionStore {
  private collapsed = new Set<number>(load());

  isCollapsed(projectId: number): boolean {
    return this.collapsed.has(projectId);
  }

  toggle(projectId: number): void {
    if (this.collapsed.has(projectId)) {
      this.collapsed.delete(projectId);
    } else {
      this.collapsed.add(projectId);
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
