import { Injectable } from '@angular/core';

const STORAGE_KEY = 'locklane.sessionAgents';

// Any id the server's installed-agents list can send (#695), plus 'shell' -- a value
// the client adds itself for a plain shell session, never a detected CLI.
export type Agent = string;

/**
 * Which agent each console session was launched with, keyed by session id.
 * Client-only state, persisted in localStorage: the engine deliberately does not
 * persist a session's launch command (#29), so this is what lets tab labels keep
 * showing an agent's name after a reload. A session opened from another browser
 * (or after storage was cleared) simply has no known agent.
 */
@Injectable({ providedIn: 'root' })
export class AgentStore {
  private agents: Record<string, Agent> = load();

  get(sessionId: string): Agent | null {
    return this.agents[sessionId] ?? null;
  }

  set(sessionId: string, agent: Agent): void {
    this.agents = { ...this.agents, [sessionId]: agent };
    save(this.agents);
  }
}

function load(): Record<string, Agent> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    const parsed = raw ? JSON.parse(raw) : {};
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
      return {};
    }
    const valid: Record<string, Agent> = {};
    for (const [id, agent] of Object.entries(parsed)) {
      if (typeof agent === 'string' && agent.length > 0) {
        valid[id] = agent;
      }
    }
    return valid;
  } catch {
    return {};
  }
}

function save(agents: Record<string, Agent>): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(agents));
  } catch {
    // Storage unavailable (private browsing, quota) — labels still work for this
    // session, they just won't survive a reload.
  }
}
