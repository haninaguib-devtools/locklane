import { NgTemplateOutlet } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, EventEmitter, OnInit, Output, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Project } from '../../models/issue.model';
import { GithubAccount, ProjectTemplate, ProjectsService } from '../../services/projects.service';
import { deriveProjectName } from './derive-project-name';

export type AddProjectMode = 'import' | 'create';

// The "Add Project" popup (#45, #491): either imports an existing repo from a git URL
// (name prefills from the URL until the user edits it directly), or creates a brand-new
// GitHub repository from an org and a name, optionally bootstrapped with t-workflow.
// Both forms carry a "GitHub account" picker (#532) listing the accounts `gh` is logged
// into on the engine host, so the project acts as the chosen one from its first fetch.
// The create form alone carries a "template" pull-down (#536) listing the project
// templates on the engine host, defaulting to none; the chosen one is committed into
// the new repository by the engine.
@Component({
  selector: 'app-add-project-popup',
  standalone: true,
  imports: [FormsModule, NgTemplateOutlet],
  templateUrl: './add-project-popup.component.html',
  styleUrl: './add-project-popup.component.css',
})
export class AddProjectPopupComponent implements OnInit {
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

  // GitHub account picker (#532). `accountsLoaded` stays false until the engine has
  // answered, so the create button is held disabled rather than briefly enabled with
  // no account behind it; a failed request counts as "no accounts", which is also the
  // engine's own answer when `gh` is missing.
  accounts: GithubAccount[] = [];
  accountsLoaded = false;
  githubLogin: string | null = null;

  // Template pull-down (#536), create tab only. `null` is the "none" option and the
  // default, so creating without a template is exactly the pre-#536 request; a failed
  // listing counts as "no templates", leaving the select with just "none".
  templates: ProjectTemplate[] = [];
  template: string | null = null;

  submitting = false;
  error: string | null = null;

  ngOnInit(): void {
    this.projectsService.templates().subscribe({
      next: (templates) => (this.templates = templates),
      error: () => (this.templates = []),
    });
    this.projectsService.githubAccounts().subscribe({
      next: (accounts) => {
        this.accounts = accounts;
        this.githubLogin = accounts.find((a) => a.active)?.login ?? accounts[0]?.login ?? null;
        this.accountsLoaded = true;
      },
      error: () => {
        this.accounts = [];
        this.githubLogin = null;
        this.accountsLoaded = true;
      },
    });
  }

  /** Creating needs an account to create as; the template disables the create button on this (#532). */
  get canCreate(): boolean {
    return this.accountsLoaded && this.accounts.length > 0;
  }

  /** The chosen template's one-line description (#536), shown under the select; empty for "none". */
  get templateDescription(): string {
    return this.templates.find((t) => t.name === this.template)?.description ?? '';
  }

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
    this.projectsService.create(this.gitUrl.trim(), this.name.trim(), this.chosenLogin()).subscribe({
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
    this.projectsService
      .createNew(
        this.org.trim(),
        this.newRepoName.trim(),
        this.bootstrapTWorkflow,
        this.chosenLogin(),
        this.template ?? undefined,
      )
      .subscribe({
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

  /** The chosen account's login, or undefined when there is none — the request then carries no `githubLogin`. */
  private chosenLogin(): string | undefined {
    return this.githubLogin ?? undefined;
  }
}
