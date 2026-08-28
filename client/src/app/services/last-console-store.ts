import { Injectable } from '@angular/core';

const STORAGE_KEY = 'locklane.lastConsole';

/**
 * Which console session a project's entry points (sidenav "+", project summary
 * button) should jump back into (#221): the session id the user most recently
 * selected in {@link ProjectConsoleComponent}, keyed by project id. Client-only
 * state, persisted in localStorage -- same pattern as {@link AgentStore}. A
 * project with no recorded entry (never visited, or storage cleared) has no
 * opinion here; callers fall back to picking a console some other way.
 */
@Injectable({ providedIn: 'root' })
export class LastConsoleStore {
  private entries: Record<string, string> = load();

  get(projectId: number): string | null {
    return this.entries[String(projectId)] ?? null;
  }

  set(projectId: number, sessionId: string): void {
    this.entries = { ...this.entries, [String(projectId)]: sessionId };
    save(this.entries);
  }
}

function load(): Record<string, string> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    const parsed = raw ? JSON.parse(raw) : {};
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
      return {};
    }
    const valid: Record<string, string> = {};
    for (const [projectId, sessionId] of Object.entries(parsed)) {
      if (typeof sessionId === 'string') {
        valid[projectId] = sessionId;
      }
    }
    return valid;
  } catch {
    return {};
  }
}

function save(entries: Record<string, string>): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(entries));
  } catch {
    // Storage unavailable (private browsing, quota) -- the choice still works for
    // this session, it just won't survive a reload.
  }
}
