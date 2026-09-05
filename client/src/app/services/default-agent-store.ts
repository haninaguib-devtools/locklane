import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';

const STORAGE_KEY = 'locklane.defaultAgent';

export type DefaultAgent = string;

/** One entry from `GET /api/agents/installed` (#695) -- mirrors `dev.locklane.engine.agent.AgentInfo`. */
export interface InstalledAgent {
  id: DefaultAgent;
  label: string;
}

/**
 * Which agent the user prefers to launch consoles with (#219), set from the settings
 * dialog. Client-only preference, persisted in localStorage -- consistent with
 * {@link AgentStore}, the engine deliberately does not persist a session's launch
 * command, so there is nothing server-side to keep this in sync with. Console-launch
 * call sites reading this in place of a hardcoded default is separate work (#219's
 * Non-goals).
 *
 * Also exposes {@link installed} (#359, #695): which CLIs the engine detected on its
 * host `PATH` at startup, id and display label both, from `GET /api/agents/installed`,
 * so the settings dialog can render a button only for one that is actually installed --
 * without knowing any agent's name itself. Empty until {@link refreshInstalled} has been
 * called and its fetch resolves; a caller that needs the installed set (the settings
 * dialog, the project summary's "Open console" button) asks for it explicitly rather
 * than this store fetching eagerly for places that have no need to trigger it. With no
 * client-side agent list left to fall back on, {@link agent}'s correction to the first
 * installed agent (when none is stored or the stored one is no longer installed, #695)
 * only takes effect once some caller's fetch has resolved.
 */
@Injectable({ providedIn: 'root' })
export class DefaultAgentStore {
  private readonly http = inject(HttpClient);
  private readonly agentSignal = signal<DefaultAgent>(load());
  private readonly installedSignal = signal<InstalledAgent[]>([]);
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
    this.http.get<{ installed: InstalledAgent[] }>('/api/agents/installed').subscribe({
      next: (result) => {
        this.installedSignal.set(result.installed);
        // The stored preference may be empty (first load) or no longer installed --
        // either way, the first entry the server returned is the default (#695).
        if (!result.installed.some((agent) => agent.id === this.agentSignal())) {
          const first = result.installed[0];
          if (first) {
            this.agentSignal.set(first.id);
          }
        }
      },
      error: () => {
        // Leave whatever was known in place -- a failed probe should not clear the
        // picker -- but allow a retry next time the dialog opens.
        this.installedRequested = false;
      },
    });
  }
}

function load(): DefaultAgent {
  try {
    return localStorage.getItem(STORAGE_KEY) ?? '';
  } catch {
    return '';
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
