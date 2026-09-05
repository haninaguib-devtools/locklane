import { NgTemplateOutlet } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, EventEmitter, HostListener, OnDestroy, OnInit, Output, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Project } from '../../models/issue.model';
import { GithubAccount, ProjectTemplate, ProjectsService } from '../../services/projects.service';
import { cloneStageHint, elapsedSeconds } from './clone-progress';
import { deriveProjectName } from './derive-project-name';

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

  // The submitting wait's staged hint and elapsed counter (#717): ms timestamp the
  // in-flight request began, ticked forward once a second while it's outstanding.
  // `submitStartedAt` stays null except while submitting, so `stageHint` below reads
  // as empty rather than a stale value once the request settles.
  private submitStartedAt: number | null = null;
  private now = Date.now();
  private tickTimer: ReturnType<typeof setInterval> | null = null;

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

  /** Creating needs an account to create as; the template disables the create button on this (#532). */
  get canCreate(): boolean {
    return this.accountsLoaded && this.accounts.length > 0;
  }

  /** The chosen template's one-line description (#536), shown under the select; empty for "none". */
  get templateDescription(): string {
    return this.templates.find((t) => t.name === this.template)?.description ?? '';
  }

  /** The submitting wait's staged text (#717); empty when nothing is in flight. */
  get stageHint(): string {
    return this.submitStartedAt === null ? '' : cloneStageHint(elapsedSeconds(this.submitStartedAt, this.now));
  }

  ngOnDestroy(): void {
    this.stopProgress();
  }

  setMode(mode: AddProjectMode): void {
    if (this.submitting) {
      return;
    }
    this.mode = mode;
    this.error = null;
  }

  /** The dialog's close button, backdrop click, and Escape all route through here
   * (#717) so every dismissal path is held the same way while a request is in flight. */
  close(): void {
    if (!this.submitting) {
      this.closed.emit();
    }
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.close();
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
    this.startProgress();
    this.projectsService.create(this.gitUrl.trim(), this.name.trim(), this.chosenAccountId()).subscribe({
      next: (project) => {
        this.submitting = false;
        this.stopProgress();
        this.created.emit(project);
      },
      error: (err: HttpErrorResponse) => {
        this.submitting = false;
        this.stopProgress();
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
    this.startProgress();
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
          this.submitting = false;
          this.stopProgress();
          // Navigate before emitting: the host closes the popup on `created`, and the
          // console page should already be the destination when it does (#537).
          this.router.navigate(['/projects', project.id, 'console']);
          this.created.emit(project);
        },
        error: (err: HttpErrorResponse) => {
          this.submitting = false;
          this.stopProgress();
          this.error = err.error?.error ?? 'could not create project';
        },
      });
  }

  /** The chosen account's id, or undefined when there is none — the request then carries no `githubAccountId`. */
  private chosenAccountId(): number | undefined {
    return this.githubAccountId ?? undefined;
  }

  private startProgress(): void {
    this.submitStartedAt = Date.now();
    this.now = this.submitStartedAt;
    this.tickTimer = setInterval(() => (this.now = Date.now()), 1000);
  }

  private stopProgress(): void {
    if (this.tickTimer !== null) {
      clearInterval(this.tickTimer);
      this.tickTimer = null;
    }
    this.submitStartedAt = null;
  }
}
