import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';

const STORAGE_KEY = 'locklane.defaultIde';

/** The bundled, browser-served IDE's id -- `open-ide`'s default, and this store's fallback (#782). */
export const CODE_SERVER_ID = 'code-server';

/**
 * One entry from `GET /api/ides/installed` (#781) -- mirrors `dev.locklane.engine.ide.IdeInfo`.
 * `desktop` is true for an IDE that opens a window on the engine host's own desktop
 * (VS Code, IntelliJ IDEA) rather than a page in the browser (code-server).
 */
export interface InstalledIde {
  id: string;
  label: string;
  desktop: boolean;
}

/** What {@link DefaultIdeStore.effective} resolves to when nothing better is known -- and what a caller with no store at all acts on. */
export const CODE_SERVER_IDE: InstalledIde = { id: CODE_SERVER_ID, label: 'code-server', desktop: false };

/**
 * Which IDE the user prefers "Open IDE" to use (#782), set from the settings dialog.
 * Client-only preference, persisted in localStorage like {@link DefaultAgentStore}:
 * "local" here means the browser and the engine share a machine, which is a property of
 * this browser, not of the account.
 *
 * Exposes {@link installed} -- what the engine detected on its host at startup, from
 * `GET /api/ides/installed`, fetched once per app load on request -- and derives from it
 * {@link available}, the entries this browser may pick (a desktop IDE only on
 * `localhost`, the same gate "Folder" uses in the tab strip), and {@link effective},
 * what "Open IDE" will actually do: the stored choice when it is available, otherwise
 * code-server. Until some caller's fetch resolves nothing is known to be installed, so
 * the effective choice is code-server -- exactly the behaviour before this store existed.
 */
@Injectable({ providedIn: 'root' })
export class DefaultIdeStore {
  private readonly http = inject(HttpClient);
  private readonly ideSignal = signal<string>(load());
  private readonly installedSignal = signal<InstalledIde[]>([]);
  private installedRequested = false;

  /** The stored id, `''` when the user never chose. */
  readonly ide = this.ideSignal.asReadonly();
  readonly installed = this.installedSignal.asReadonly();

  /** The installed entries this browser may choose from: every one on `localhost`, only non-desktop ones elsewhere. */
  readonly available = computed<InstalledIde[]>(() =>
    this.installedSignal().filter((ide) => !ide.desktop || this.isLocalHost),
  );

  /** The entry "Open IDE" acts on: the stored choice when it is {@link available}, otherwise code-server. */
  readonly effective = computed<InstalledIde>(
    () => this.available().find((ide) => ide.id === this.ideSignal()) ?? CODE_SERVER_IDE,
  );

  set(ide: string): void {
    this.ideSignal.set(ide);
    save(ide);
  }

  /** Fetches {@link installed} once per app load; a later call while it is already known is a no-op. */
  refreshInstalled(): void {
    if (this.installedRequested) {
      return;
    }
    this.installedRequested = true;
    this.http.get<{ installed: InstalledIde[] }>('/api/ides/installed').subscribe({
      next: (result) => this.installedSignal.set(result.installed),
      error: () => {
        // Leave whatever was known in place -- a failed probe should not clear the
        // picker -- but allow a retry next time the dialog opens.
        this.installedRequested = false;
      },
    });
  }

  // A desktop IDE opens on the engine host's own desktop, which only makes sense when
  // this browser is on that machine -- the same gate "Folder" uses (#497).
  get isLocalHost(): boolean {
    return this.currentHostname() === 'localhost';
  }

  // Indirection for testability (#497): most browsers refuse to let a spy override
  // window.location.hostname, since it is not a configurable property.
  protected currentHostname(): string {
    return window.location.hostname;
  }
}

function load(): string {
  try {
    return localStorage.getItem(STORAGE_KEY) ?? '';
  } catch {
    return '';
  }
}

function save(ide: string): void {
  try {
    localStorage.setItem(STORAGE_KEY, ide);
  } catch {
    // Storage unavailable (private browsing, quota) -- the choice still works for this
    // session, it just won't survive a reload.
  }
}
