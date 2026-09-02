import { HttpErrorResponse } from '@angular/common/http';
import { Component, EventEmitter, HostListener, OnDestroy, OnInit, Output, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DeviceFlowStarted, GithubAccountsService } from '../../services/github-accounts.service';
import { GithubAccount } from '../../services/projects.service';
import { ConfirmDialogComponent } from '../confirm-dialog/confirm-dialog.component';

const POLL_INTERVAL_MS = 2000;

/**
 * The GitHub accounts page (#550): every account the caller has signed in to
 * Locklane, either through GitHub's own device flow ("Sign in with GitHub" — a code
 * and a link, approved from any browser) or by pasting an existing token, plus
 * removing one (refused, 409, while a project still acts as it). Reached from the
 * account menu, alongside Settings and Manage users — implementer's call per #550's
 * own issue text, which left "reachable from the settings dialog, or its own route"
 * open; a sibling menu entry keeps `AppComponent`'s existing one-flag-per-dialog
 * shape instead of nesting one dialog inside another.
 *
 * Visually follows `admin-users`: a full-page backdrop that dismisses on click,
 * holding a bordered panel whose own clicks do not.
 */
@Component({
  selector: 'app-github-accounts',
  standalone: true,
  imports: [FormsModule, ConfirmDialogComponent],
  templateUrl: './github-accounts.component.html',
  styleUrl: './github-accounts.component.css',
})
export class GithubAccountsComponent implements OnInit, OnDestroy {
  private readonly accountsService = inject(GithubAccountsService);
  private pollHandle: ReturnType<typeof setTimeout> | null = null;

  @Output() closed = new EventEmitter<void>();

  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.closed.emit();
  }

  readonly accounts = signal<GithubAccount[]>([]);
  readonly loading = signal(true);
  readonly loadError = signal<string | null>(null);

  // Device flow (#550).
  readonly deviceFlow = signal<DeviceFlowStarted | null>(null);
  readonly deviceFlowStarting = signal(false);
  readonly deviceFlowError = signal<string | null>(null);
  readonly deviceFlowNotConfigured = signal(false);

  // Paste-a-token (#550).
  pastedToken = '';
  addingToken = false;
  addTokenError: string | null = null;

  removeTarget: GithubAccount | null = null;
  removing = false;
  removeError: string | null = null;

  ngOnInit(): void {
    this.refresh();
  }

  ngOnDestroy(): void {
    this.stopPolling();
  }

  refresh(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.accountsService.list().subscribe({
      next: (accounts) => {
        this.loading.set(false);
        this.accounts.set(accounts);
      },
      error: (err: HttpErrorResponse) => {
        this.loading.set(false);
        this.loadError.set(err.error?.error ?? 'could not load your GitHub accounts');
      },
    });
  }

  startDeviceFlow(): void {
    if (this.deviceFlowStarting()) {
      return;
    }
    this.deviceFlowStarting.set(true);
    this.deviceFlowError.set(null);
    this.deviceFlowNotConfigured.set(false);
    this.accountsService.startDeviceFlow().subscribe({
      next: (started) => {
        this.deviceFlowStarting.set(false);
        this.deviceFlow.set(started);
        this.pollDeviceFlow(started.flowId);
      },
      error: (err: HttpErrorResponse) => {
        this.deviceFlowStarting.set(false);
        if (err.status === 501) {
          this.deviceFlowNotConfigured.set(true);
        } else {
          this.deviceFlowError.set(err.error?.error ?? 'could not start sign-in with GitHub');
        }
      },
    });
  }

  cancelDeviceFlow(): void {
    this.stopPolling();
    this.deviceFlow.set(null);
    this.deviceFlowError.set(null);
  }

  private pollDeviceFlow(flowId: string): void {
    this.accountsService.deviceFlowStatus(flowId).subscribe({
      next: (status) => {
        if (status.status === 'COMPLETE') {
          this.deviceFlow.set(null);
          this.refresh();
        } else if (status.status === 'FAILED') {
          this.deviceFlow.set(null);
          this.deviceFlowError.set(status.errorMessage ?? 'sign-in did not complete');
        } else {
          this.pollHandle = setTimeout(() => this.pollDeviceFlow(flowId), POLL_INTERVAL_MS);
        }
      },
      error: () => {
        // A transient network hiccup shouldn't drop the whole flow -- keep polling
        // until the device code itself expires server-side.
        this.pollHandle = setTimeout(() => this.pollDeviceFlow(flowId), POLL_INTERVAL_MS);
      },
    });
  }

  private stopPolling(): void {
    if (this.pollHandle !== null) {
      clearTimeout(this.pollHandle);
      this.pollHandle = null;
    }
  }

  addToken(): void {
    const token = this.pastedToken.trim();
    if (!token || this.addingToken) {
      return;
    }
    this.addingToken = true;
    this.addTokenError = null;
    this.accountsService.addByToken(token).subscribe({
      next: () => {
        this.addingToken = false;
        this.pastedToken = '';
        this.refresh();
      },
      error: (err: HttpErrorResponse) => {
        this.addingToken = false;
        this.addTokenError = err.error?.error ?? 'could not add this token';
      },
    });
  }

  requestRemove(account: GithubAccount): void {
    this.removeError = null;
    this.removeTarget = account;
  }

  cancelRemove(): void {
    this.removeTarget = null;
  }

  confirmRemove(): void {
    const target = this.removeTarget;
    if (!target || this.removing) {
      return;
    }
    this.removing = true;
    this.accountsService.remove(target.id).subscribe({
      next: () => {
        this.removing = false;
        this.removeTarget = null;
        this.refresh();
      },
      error: (err: HttpErrorResponse) => {
        this.removing = false;
        this.removeTarget = null;
        this.removeError = err.error?.error ?? 'could not remove this account';
      },
    });
  }
}
