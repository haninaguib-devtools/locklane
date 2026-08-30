import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';

const STORAGE_KEY = 'locklane.defaultAgent';

export type DefaultAgent = 'claude' | 'codex' | 'opencode';

export const DEFAULT_AGENT_LABELS: Record<DefaultAgent, string> = {
  claude: 'Claude',
  codex: 'Codex',
  opencode: 'OpenCode',
};

const ALL_DEFAULT_AGENTS: DefaultAgent[] = ['claude', 'codex', 'opencode'];

/**
 * Which agent the user prefers to launch consoles with (#219), set from the settings
 * dialog. Client-only preference, persisted in localStorage -- consistent with
 * {@link AgentStore}, the engine deliberately does not persist a session's launch
 * command, so there is nothing server-side to keep this in sync with. Console-launch
 * call sites reading this in place of a hardcoded `'claude'` is separate work (#219's
 * Non-goals).
 *
 * Also exposes {@link installed} (#359): which of the three CLIs the engine detected on
 * its host `PATH` at startup, from `GET /api/agents/installed`, so the settings dialog
 * can render a button only for one that is actually installed. Defaults to all three
 * until {@link refreshInstalled} has been called and its fetch resolves (or if it
 * fails), so the picker never renders empty. The fetch is not made eagerly in the
 * constructor -- this store is injected from places (e.g. the sidenav, to launch a
 * console with the preferred agent) that have no need to trigger it, so a caller that
 * actually needs the installed set (the settings dialog) asks for it explicitly.
 */
@Injectable({ providedIn: 'root' })
export class DefaultAgentStore {
  private readonly http = inject(HttpClient);
  private readonly agentSignal = signal<DefaultAgent>(load());
  private readonly installedSignal = signal<DefaultAgent[]>(ALL_DEFAULT_AGENTS);
  private installedRequested = false;

  readonly agent = this.agentSignal.asReadonly();
  readonly installed = this.installedSignal.asReadonly();

  set(agent: DefaultAgent): void {
    this.agentSignal.set(agent);
    save(agent);
  }

  /** Fetches {@link installed} once per app load; a later call while it is already known is a no-op. */
  refreshInstalled(): void {
    if (this.installedRequested) {
      return;
    }
    this.installedRequested = true;
    this.http.get<{ installed: string[] }>('/api/agents/installed').subscribe({
      next: (result) => this.installedSignal.set(result.installed.filter(isDefaultAgent)),
      error: () => {
        // Leave the all-agents fallback in place -- a failed probe should not hide
        // every option -- but allow a retry next time the dialog opens.
        this.installedRequested = false;
      },
    });
  }
}

function isDefaultAgent(value: string): value is DefaultAgent {
  return value === 'claude' || value === 'codex' || value === 'opencode';
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
