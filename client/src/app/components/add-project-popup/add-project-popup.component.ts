import { HttpErrorResponse } from '@angular/common/http';
import { Component, EventEmitter, Output, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Project } from '../../models/issue.model';
import { ProjectsService } from '../../services/projects.service';
import { deriveProjectName } from './derive-project-name';

// The "Add Project" popup (#45): collects a git URL and an optional name, which
// prefills from the URL until the user edits it directly.
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

  gitUrl = '';
  name = '';
  private nameManuallyEdited = false;

  submitting = false;
  error: string | null = null;

  onUrlChange(): void {
    if (!this.nameManuallyEdited) {
      this.name = deriveProjectName(this.gitUrl.trim());
    }
  }

  onNameChange(): void {
    this.nameManuallyEdited = true;
  }

  submit(): void {
    if (!this.gitUrl.trim() || this.submitting) {
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
}
