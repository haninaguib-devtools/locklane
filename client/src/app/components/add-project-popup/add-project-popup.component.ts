import { HttpErrorResponse } from '@angular/common/http';
import { Component, EventEmitter, Output, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Project } from '../../models/issue.model';
import { ProjectsService } from '../../services/projects.service';
import { deriveProjectName } from './derive-project-name';

export type AddProjectMode = 'import' | 'create';

// The "Add Project" popup (#45, #491): either imports an existing repo from a git URL
// (name prefills from the URL until the user edits it directly), or creates a brand-new
// GitHub repository from an org and a name, optionally bootstrapped with t-workflow.
@Component({
  selector: 'app-add-project-popup',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './add-project-popup.component.html',
  styleUrl: './add-project-popup.component.css',
})
export class AddProjectPopupComponent {
  private readonly projectsService = inject(ProjectsService);

  @Output() created = new EventEmitter<Project>();
  @Output() closed = new EventEmitter<void>();

  mode: AddProjectMode = 'import';

  // Import existing
  gitUrl = '';
  name = '';
  private nameManuallyEdited = false;

  // Create new
  org = '';
  newRepoName = '';
  bootstrapTWorkflow = false;

  submitting = false;
  error: string | null = null;

  setMode(mode: AddProjectMode): void {
    if (this.submitting) {
      return;
    }
    this.mode = mode;
    this.error = null;
  }

  onUrlChange(): void {
    if (!this.nameManuallyEdited) {
      this.name = deriveProjectName(this.gitUrl.trim());
    }
  }

  onNameChange(): void {
    this.nameManuallyEdited = true;
  }

  submit(): void {
    if (this.submitting) {
      return;
    }
    if (this.mode === 'import') {
      this.submitImport();
    } else {
      this.submitCreate();
    }
  }

  private submitImport(): void {
    if (!this.gitUrl.trim()) {
      return;
    }
    this.submitting = true;
    this.error = null;
    this.projectsService.create(this.gitUrl.trim(), this.name.trim()).subscribe({
      next: (project) => {
        this.submitting = false;
        this.created.emit(project);
      },
      error: (err: HttpErrorResponse) => {
        this.submitting = false;
        this.error = err.error?.error ?? 'could not create project';
      },
    });
  }

  private submitCreate(): void {
    if (!this.org.trim() || !this.newRepoName.trim()) {
      return;
    }
    this.submitting = true;
    this.error = null;
    this.projectsService.createNew(this.org.trim(), this.newRepoName.trim(), this.bootstrapTWorkflow).subscribe({
      next: (project) => {
        this.submitting = false;
        this.created.emit(project);
      },
      error: (err: HttpErrorResponse) => {
        this.submitting = false;
        this.error = err.error?.error ?? 'could not create project';
      },
    });
  }
}
