import { Component, ElementRef, EventEmitter, HostListener, Input, OnInit, Output, ViewChild, inject } from '@angular/core';
import { CurrentProjectService } from '../../services/current-project.service';

/** What the dialog hands back on save (#936). */
export interface WorkspaceEdit {
  name: string;
  projectIds: number[];
}

/**
 * The small dialog behind the workspace dropdown's "New workspace…", "Edit projects"
 * and "Rename" (#936): a name field, one checkbox per project, or both, reading the
 * project list already shared through CurrentProjectService. Same backdrop/panel
 * pattern as `confirm-dialog`; Escape cancels, Enter in the name field saves.
 */
@Component({
  selector: 'app-workspace-projects-dialog',
  standalone: true,
  templateUrl: './workspace-projects-dialog.component.html',
  styleUrl: './workspace-projects-dialog.component.css',
})
export class WorkspaceProjectsDialogComponent implements OnInit {
  private readonly currentProject = inject(CurrentProjectService);

  @Input() title = 'Workspace';
  @Input() saveLabel = 'Save';
  @Input() showName = true;
  @Input() showProjects = true;
  @Input() name = '';
  @Input() projectIds: number[] = [];
  @Output() saved = new EventEmitter<WorkspaceEdit>();
  @Output() cancelled = new EventEmitter<void>();

  @ViewChild('nameInput') private readonly nameInputRef?: ElementRef<HTMLInputElement>;

  readonly projects = this.currentProject.projects;
  selected = new Set<number>();

  ngOnInit(): void {
    this.selected = new Set(this.projectIds);
    queueMicrotask(() => this.nameInputRef?.nativeElement.focus());
  }

  isSelected(projectId: number): boolean {
    return this.selected.has(projectId);
  }

  toggle(projectId: number): void {
    if (this.selected.has(projectId)) {
      this.selected.delete(projectId);
    } else {
      this.selected.add(projectId);
    }
  }

  canSave(): boolean {
    return !this.showName || this.name.trim().length > 0;
  }

  save(): void {
    if (!this.canSave()) {
      return;
    }
    // Keep the project list's own order rather than click order.
    const projectIds = this.projects()
      .map((p) => p.id)
      .filter((id) => this.selected.has(id));
    this.saved.emit({ name: this.name.trim(), projectIds });
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.cancelled.emit();
  }
}
