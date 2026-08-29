import { Injectable, signal } from '@angular/core';

const STORAGE_KEY = 'locklane.defaultAgent';

export type DefaultAgent = 'claude' | 'codex' | 'opencode';

/**
 * Which agent the user prefers to launch consoles with (#219), set from the settings
 * dialog. Client-only preference, persisted in localStorage -- consistent with
 * {@link AgentStore}, the engine deliberately does not persist a session's launch
 * command, so there is nothing server-side to keep this in sync with. Console-launch
 * call sites reading this in place of a hardcoded `'claude'` is separate work (#219's
 * Non-goals).
 */
@Injectable({ providedIn: 'root' })
export class DefaultAgentStore {
  private readonly agentSignal = signal<DefaultAgent>(load());
  readonly agent = this.agentSignal.asReadonly();

  set(agent: DefaultAgent): void {
    this.agentSignal.set(agent);
    save(agent);
  }
}

function load(): DefaultAgent {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return stored === 'codex' || stored === 'opencode' ? stored : 'claude';
  } catch {
    return 'claude';
  }
}

function save(agent: DefaultAgent): void {
  try {
    localStorage.setItem(STORAGE_KEY, agent);
  } catch {
    // Storage unavailable (private browsing, quota) -- the choice still works for this
    // session, it just won't survive a reload.
  }
}
