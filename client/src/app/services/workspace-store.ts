import { Injectable, signal } from '@angular/core';

const STORAGE_KEY = 'locklane.workspaces';

/**
 * A workspace (#934): a named, saved view that narrows the app to a chosen subset of
 * projects and remembers its own sidenav filter settings.
 */
export interface Workspace {
  id: string;
  name: string;
  projectIds: number[];
  filterText: string;
  hideShipped: boolean;
}

/**
 * The saved workspaces (#935). Client-only state persisted in localStorage, the same
 * way pins (`pin-store.ts`) and initiative folds (`collapse-store.ts`) are -- never
 * synced to the engine (#934's Non-goals). Which workspace is *active* is not stored
 * here: it lives in the URL's `ws=<id>` query param, read by CurrentProjectService.
 *
 * Exposed as a signal so consumers re-derive when the list changes; every mutation
 * replaces the array rather than editing it in place.
 */
@Injectable({ providedIn: 'root' })
export class WorkspaceStore {
  private readonly workspacesSignal = signal<Workspace[]>(load());

  /** Every saved workspace, in creation order. */
  readonly workspaces = this.workspacesSignal.asReadonly();

  list(): Workspace[] {
    return [...this.workspacesSignal()];
  }

  get(id: string): Workspace | null {
    return this.workspacesSignal().find((w) => w.id === id) ?? null;
  }

  /** A new workspace starts with an empty filter and hide-shipped on (#938). */
  create(name: string, projectIds: number[] = []): Workspace {
    const workspace: Workspace = {
      id: newId(),
      name,
      projectIds: unique(projectIds),
      filterText: '',
      hideShipped: true,
    };
    this.update([...this.workspacesSignal(), workspace]);
    return workspace;
  }

  rename(id: string, name: string): void {
    this.patch(id, { name });
  }

  delete(id: string): void {
    this.update(this.workspacesSignal().filter((w) => w.id !== id));
  }

  setProjects(id: string, projectIds: number[]): void {
    this.patch(id, { projectIds: unique(projectIds) });
  }

  updateFilters(id: string, filters: Partial<Pick<Workspace, 'filterText' | 'hideShipped'>>): void {
    this.patch(id, filters);
  }

  private patch(id: string, changes: Partial<Omit<Workspace, 'id'>>): void {
    if (!this.workspacesSignal().some((w) => w.id === id)) {
      return;
    }
    this.update(this.workspacesSignal().map((w) => (w.id === id ? { ...w, ...changes } : w)));
  }

  private update(workspaces: Workspace[]): void {
    this.workspacesSignal.set(workspaces);
    save(workspaces);
  }
}

function unique(ids: number[]): number[] {
  return [...new Set(ids)];
}

function newId(): string {
  // crypto.randomUUID is available in every browser the PWA targets; the fallback
  // only matters for an unusual test host.
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

function load(): Workspace[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    const parsed = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed.filter(isWorkspace).map(normalize) : [];
  } catch {
    return [];
  }
}

function isWorkspace(w: unknown): w is Workspace {
  if (!w || typeof w !== 'object') {
    return false;
  }
  const c = w as Record<string, unknown>;
  return typeof c['id'] === 'string' && typeof c['name'] === 'string' && Array.isArray(c['projectIds']);
}

function normalize(w: Workspace): Workspace {
  return {
    id: w.id,
    name: w.name,
    projectIds: unique(w.projectIds.filter((id): id is number => typeof id === 'number')),
    filterText: typeof w.filterText === 'string' ? w.filterText : '',
    hideShipped: typeof w.hideShipped === 'boolean' ? w.hideShipped : true,
  };
}

function save(workspaces: Workspace[]): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(workspaces));
  } catch {
    // Storage unavailable (private browsing, quota) -- workspaces still work for
    // this session, they just won't survive a reload.
  }
}
