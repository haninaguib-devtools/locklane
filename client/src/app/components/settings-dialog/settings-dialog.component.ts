import { Component, EventEmitter, HostListener, OnInit, Output, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AccountService, TwoFactorEnrollment } from '../../services/account.service';

type TwoFactorStage = 'loading' | 'off' | 'enrolling' | 'enabled';

/**
 * The settings dialog (#90): a title bar and a body holding, for now, only the
 * two-factor authentication section (#91) -- enable (enroll, scan/enter, confirm),
 * disable (password), and the current status in between. Visually it follows
 * `add-project-popup`: a full-page backdrop that dismisses on click, holding a bordered
 * panel whose own clicks do not. No approved mockup existed for the three states, so
 * this one is designed to match `portstow`'s settings-page two-factor section (a plain
 * status/enroll/confirm flow), restyled with locklane's own tokens.
 */
@Component({
  selector: 'app-settings-dialog',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './settings-dialog.component.html',
  styleUrl: './settings-dialog.component.css',
})
export class SettingsDialogComponent implements OnInit {
  private readonly accountService = inject(AccountService);

  @Output() closed = new EventEmitter<void>();

  readonly stage = signal<TwoFactorStage>('loading');
  readonly loadError = signal<string | null>(null);
  readonly enrollment = signal<TwoFactorEnrollment | null>(null);

  confirmCode = '';
  confirming = false;
  confirmError: string | null = null;

  password = '';
  disabling = false;
  disableError: string | null = null;

  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.closed.emit();
  }

  ngOnInit(): void {
    this.loadStatus();
  }

  private loadStatus(): void {
    this.stage.set('loading');
    this.loadError.set(null);
    this.accountService.twoFactorStatus().subscribe({
      next: (status) => this.stage.set(status.enabled ? 'enabled' : 'off'),
      error: (err: Error) => {
        this.loadError.set(err.message);
        this.stage.set('off');
      },
    });
  }

  startEnroll(): void {
    this.loadError.set(null);
    this.accountService.enrollTwoFactor().subscribe({
      next: (enrollment) => {
        this.enrollment.set(enrollment);
        this.confirmCode = '';
        this.confirmError = null;
        this.stage.set('enrolling');
      },
      error: (err: Error) => this.loadError.set(err.message),
    });
  }

  cancelEnroll(): void {
    this.enrollment.set(null);
    this.stage.set('off');
  }

  confirmEnroll(): void {
    if (!this.confirmCode.trim() || this.confirming) {
      return;
    }
    this.confirming = true;
    this.confirmError = null;
    this.accountService.confirmTwoFactor(this.confirmCode.trim()).subscribe({
      next: () => {
        this.confirming = false;
        this.enrollment.set(null);
        this.stage.set('enabled');
      },
      error: (err: Error) => {
        this.confirming = false;
        this.confirmError = err.message;
      },
    });
  }

  disable(): void {
    if (!this.password || this.disabling) {
      return;
    }
    this.disabling = true;
    this.disableError = null;
    this.accountService.disableTwoFactor(this.password).subscribe({
      next: () => {
        this.disabling = false;
        this.password = '';
        this.stage.set('off');
      },
      error: (err: Error) => {
        this.disabling = false;
        this.disableError = err.message;
      },
    });
  }
}
