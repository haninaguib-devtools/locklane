import { Component, ElementRef, ViewChild, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { CurrentProjectService, WORKSPACE_QUERY_PARAM } from '../../services/current-project.service';
import { Workspace, WorkspaceStore } from '../../services/workspace-store';
import { ConfirmDialogComponent } from '../confirm-dialog/confirm-dialog.component';
import { WorkspaceEdit, WorkspaceProjectsDialogComponent } from '../workspace-projects-dialog/workspace-projects-dialog.component';

/** One keyboard-navigable row of the dropdown (#936). */
export type WorkspaceRow = { kind: 'all' } | { kind: 'workspace'; workspace: Workspace } | { kind: 'new' };

type Dialog = { kind: 'create' } | { kind: 'edit'; workspace: Workspace } | { kind: 'rename'; workspace: Workspace } | { kind: 'delete'; workspace: Workspace };

/**
 * The header's workspace dropdown (#934, #936), next to the open-agents widget: lists
 * "All projects", one row per saved workspace, and "New workspace…". Selecting a row
 * navigates with `ws=<id>` set or removed -- the active workspace lives in the URL
 * alone (see CurrentProjectService), so this never holds it itself. Every workspace
 * row carries Open in new window first, so the icon lines up down the list (#969);
 * the active row adds Edit projects / Rename / Delete after it. Scrim, focus trap and
 * arrow/enter/escape follow `agent-session-indicator`.
 */
@Component({
  selector: 'app-workspace-picker',
  standalone: true,
  imports: [ConfirmDialogComponent, WorkspaceProjectsDialogComponent],
  templateUrl: './workspace-picker.component.html',
  styleUrl: './workspace-picker.component.css',
})
export class WorkspacePickerComponent {
  private readonly router = inject(Router);
  private readonly currentProject = inject(CurrentProjectService);
  private readonly store = inject(WorkspaceStore);

  @ViewChild('results') private readonly resultsRef?: ElementRef<HTMLElement>;
  @ViewChild('trigger') private readonly triggerRef?: ElementRef<HTMLElement>;

  readonly activeWorkspaceId = this.currentProject.activeWorkspaceId;
  readonly activeWorkspace = computed(
    () => this.store.workspaces().find((w) => w.id === this.activeWorkspaceId()) ?? null,
  );
  // An unknown id in the URL reads as "All projects", the same way the service does.
  readonly label = computed(() => this.activeWorkspace()?.name ?? 'All projects');

  readonly rows = computed<WorkspaceRow[]>(() => [
    { kind: 'all' },
    ...this.store.workspaces().map((workspace) => ({ kind: 'workspace' as const, workspace })),
    { kind: 'new' },
  ]);

  readonly open = signal(false);
  readonly selected = signal(0);
  readonly dialog = signal<Dialog | null>(null);

  isActive(row: WorkspaceRow): boolean {
    const active = this.activeWorkspace();
    return row.kind === 'workspace' ? row.workspace.id === active?.id : row.kind === 'all' && active === null;
  }

  rowId(index: number): string {
    return 'workspace-row-' + index;
  }

  toggle(): void {
    const next = !this.open();
    this.open.set(next);
    if (next) {
      const active = this.rows().findIndex((row) => this.isActive(row));
      this.selected.set(active >= 0 ? active : 0);
      queueMicrotask(() => this.resultsRef?.nativeElement.focus());
    } else {
      queueMicrotask(() => this.triggerRef?.nativeElement.focus());
    }
  }

  close(): void {
    this.open.set(false);
    queueMicrotask(() => this.triggerRef?.nativeElement.focus());
  }

  onKey(event: KeyboardEvent): void {
    switch (event.key) {
      case 'Escape':
        event.preventDefault();
        this.close();
        break;
      case 'ArrowDown':
        event.preventDefault();
        this.move(1);
        break;
      case 'ArrowUp':
        event.preventDefault();
        this.move(-1);
        break;
      case 'Enter':
        event.preventDefault();
        this.choose(this.rows()[this.selected()]);
        break;
      case 'Tab':
        event.preventDefault();
        break;
    }
  }

  choose(row: WorkspaceRow | undefined): void {
    if (!row) {
      return;
    }
    this.open.set(false);
    switch (row.kind) {
      case 'all':
        this.activate(null);
        break;
      case 'workspace':
        this.activate(row.workspace.id);
        break;
      case 'new':
        this.dialog.set({ kind: 'create' });
        break;
    }
  }

  editProjects(workspace: Workspace, event: Event): void {
    event.stopPropagation();
    this.open.set(false);
    this.dialog.set({ kind: 'edit', workspace });
  }

  rename(workspace: Workspace, event: Event): void {
    event.stopPropagation();
    this.open.set(false);
    this.dialog.set({ kind: 'rename', workspace });
  }

  // Opens the current page in a new window with this workspace active (#934): the same
  // way the sidenav pops a project out with `focus=1`, the workspace rides in the URL.
  openInNewWindow(workspace: Workspace, event: Event): void {
    event.stopPropagation();
    this.open.set(false);
    const tree = this.router.parseUrl(this.router.url);
    tree.queryParams = { ...tree.queryParams, [WORKSPACE_QUERY_PARAM]: workspace.id };
    window.open(this.router.serializeUrl(tree), '_blank');
  }

  askDelete(workspace: Workspace, event: Event): void {
    event.stopPropagation();
    this.open.set(false);
    this.dialog.set({ kind: 'delete', workspace });
  }

  onDialogSaved(edit: WorkspaceEdit): void {
    const dialog = this.dialog();
    this.dialog.set(null);
    switch (dialog?.kind) {
      case 'create': {
        const created = this.store.create(edit.name, edit.projectIds);
        this.activate(created.id);
        break;
      }
      case 'edit':
        this.store.setProjects(dialog.workspace.id, edit.projectIds);
        break;
      case 'rename':
        this.store.rename(dialog.workspace.id, edit.name);
        break;
    }
  }

  confirmDelete(): void {
    const dialog = this.dialog();
    this.dialog.set(null);
    if (dialog?.kind !== 'delete') {
      return;
    }
    const wasActive = dialog.workspace.id === this.activeWorkspaceId();
    this.store.delete(dialog.workspace.id);
    if (wasActive) {
      this.activate(null);
    }
  }

  closeDialog(): void {
    this.dialog.set(null);
  }

  // Stays on the current page; only the `ws` param changes. Naming `ws` here is what
  // stops FocusPreservingRouter carrying the old value forward (null removes it).
  private activate(id: string | null): void {
    this.router.navigate([], { queryParams: { [WORKSPACE_QUERY_PARAM]: id }, queryParamsHandling: 'merge' });
  }

  private move(delta: number): void {
    const count = this.rows().length;
    this.selected.set((this.selected() + delta + count) % count);
  }
}
