import { NgTemplateOutlet } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, EventEmitter, HostListener, OnDestroy, OnInit, Output, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Project } from '../../models/issue.model';
import { GithubAccount, ProjectTemplate, ProjectsService } from '../../services/projects.service';
import { deriveProjectName } from './derive-project-name';
import { cloneStageHint } from '../clone-progress';

export type AddProjectMode = 'import' | 'create';

// The "Add Project" popup (#45, #491): either imports an existing repo from a git URL
// (name prefills from the URL until the user edits it directly), or creates a brand-new
// GitHub repository from an org and a name, optionally bootstrapped with t-workflow.
// Both forms carry a "GitHub account" picker (#532, reworked by #550) listing the
// accounts the caller has signed in to Locklane on the GitHub accounts page, so the
// project acts as the chosen one from its first fetch. The create form alone carries
// a "template" pull-down (#536) listing the project
// templates on the engine host, defaulting to none; the chosen one is committed into
// the new repository by the engine. A successful create also navigates straight to
// the new project's console page (#537), while it is still cloning -- that page waits
// for READY and, for a templated project, opens the seeded console itself. Import
// keeps today's behaviour: the dialog closes and the sidenav refreshes.
@Component({
  selector: 'app-add-project-popup',
  standalone: true,
  imports: [FormsModule, NgTemplateOutlet],
  templateUrl: './add-project-popup.component.html',
  styleUrl: './add-project-popup.component.css',
})
export class AddProjectPopupComponent implements OnInit, OnDestroy {
  private readonly projectsService = inject(ProjectsService);
  private readonly router = inject(Router);

  @Output() created = new EventEmitter<Project>();
  @Output() closed = new EventEmitter<void>();
  // Import success (#717): the dialog stays open -- locked, timer running -- until
  // the host's sidebar reveal completes, so there is no dead gap between the
  // dialog closing and the new row appearing. The host closes the dialog then.
  @Output() imported = new EventEmitter<Project>();

  mode: AddProjectMode = 'import';

  // Import existing
  gitUrl = '';
  name = '';
  private nameManuallyEdited = false;

  // Create new
  org = '';
  newRepoName = '';
  bootstrapTWorkflow = false;

  // GitHub account picker (#532, #550). `accountsLoaded` stays false until the engine
  // has answered, so the create button is held disabled rather than briefly enabled
  // with no account behind it; a failed request counts as "no accounts".
  accounts: GithubAccount[] = [];
  accountsLoaded = false;
  githubAccountId: number | null = null;

  // Template pull-down (#536), create tab only. `null` is the "none" option and the
  // default, so creating without a template is exactly the pre-#536 request; a failed
  // listing counts as "no templates", leaving the select with just "none".
  templates: ProjectTemplate[] = [];
  template: string | null = null;

  submitting = false;
  error: string | null = null;

  // Live submit progress (#717): while the request is in flight the dialog locks
  // (submit, tabs, inputs, close, backdrop, Escape) and shows a spinner plus a
  // staged hint derived from how long the wait has run. The 1s tick only wakes
  // change detection -- the getters below read the clock directly.
  private submitStartedAt: number | null = null;
  private submitTimer: ReturnType<typeof setInterval> | null = null;

  ngOnInit(): void {
    this.projectsService.templates().subscribe({
      next: (templates) => (this.templates = templates),
      error: () => (this.templates = []),
    });
    this.projectsService.githubAccounts().subscribe({
      next: (accounts) => {
        this.accounts = accounts;
        this.githubAccountId = accounts[0]?.id ?? null;
        this.accountsLoaded = true;
      },
      error: () => {
        this.accounts = [];
        this.githubAccountId = null;
        this.accountsLoaded = true;
      },
    });
  }

  ngOnDestroy(): void {
    this.stopSubmitTimer();
  }

  /** Seconds since the in-flight submit started (#717); 0 when idle. */
  get submitElapsedSec(): number {
    if (this.submitStartedAt === null) {
      return 0;
    }
    return Math.max(0, Math.floor((Date.now() - this.submitStartedAt) / 1000));
  }

  /** Staged hint for the in-flight submit (#717) -- same mapping as the sidenav row and console wait. */
  get submitStageHint(): string {
    return cloneStageHint(this.submitElapsedSec);
  }

  /** The close button and the backdrop both route here: locked while submitting (#717). */
  close(): void {
    if (this.submitting) {
      return;
    }
    this.closed.emit();
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.close();
  }

  private startSubmit(): void {
    this.submitting = true;
    this.error = null;
    this.submitStartedAt = Date.now();
    this.stopSubmitTimer();
    this.submitTimer = setInterval(() => {}, 1000);
  }

  private finishSubmit(): void {
    this.submitting = false;
    this.submitStartedAt = null;
    this.stopSubmitTimer();
  }

  private stopSubmitTimer(): void {
    if (this.submitTimer !== null) {
      clearInterval(this.submitTimer);
      this.submitTimer = null;
    }
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
    this.startSubmit();
    this.projectsService.create(this.gitUrl.trim(), this.name.trim(), this.chosenAccountId()).subscribe({
      next: (project) => {
        // No finishSubmit: the dialog stays locked with its timer running until
        // the host closes it after the sidebar reveal (see `imported`).
        this.imported.emit(project);
      },
      error: (err: HttpErrorResponse) => {
        this.finishSubmit();
        this.error = err.error?.error ?? 'could not create project';
      },
    });
  }

  private submitCreate(): void {
    if (!this.org.trim() || !this.newRepoName.trim()) {
      return;
    }
    this.startSubmit();
    this.projectsService
      .createNew(
        this.org.trim(),
        this.newRepoName.trim(),
        this.bootstrapTWorkflow,
        this.chosenAccountId(),
        this.template ?? undefined,
      )
      .subscribe({
        next: (project) => {
          this.finishSubmit();
          // Navigate before emitting: the host closes the popup on `created`, and the
          // console page should already be the destination when it does (#537).
          this.router.navigate(['/projects', project.id, 'console']);
          this.created.emit(project);
        },
        error: (err: HttpErrorResponse) => {
          this.finishSubmit();
          this.error = err.error?.error ?? 'could not create project';
        },
      });
  }

  /** The chosen account's id, or undefined when there is none — the request then carries no `githubAccountId`. */
  private chosenAccountId(): number | undefined {
    return this.githubAccountId ?? undefined;
  }
}
