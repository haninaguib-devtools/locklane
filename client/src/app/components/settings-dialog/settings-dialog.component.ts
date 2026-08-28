import { Component, EventEmitter, HostListener, OnInit, Output, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AccountService, TwoFactorEnrollment } from '../../services/account.service';
import { DefaultAgent, DefaultAgentStore } from '../../services/default-agent-store';

type TwoFactorStage = 'loading' | 'off' | 'enrolling' | 'backup-codes' | 'enabled';

/**
 * The settings dialog (#90): a title bar and a body holding a default-agent section
 * (#219) and the two-factor authentication section (#91) -- enable (enroll,
 * scan/enter, confirm), disable (password), and the current status in between.
 * Visually it follows `add-project-popup`: a full-page backdrop that dismisses on
 * click, holding a bordered panel whose own clicks do not. No approved mockup existed
 * for the three states, so this one is designed to match `portstow`'s settings-page
 * two-factor section (a plain status/enroll/confirm flow), restyled with locklane's
 * own tokens.
 *
 * <p>Confirming an enrollment, and regenerating from the enabled state, both land on the
 * `backup-codes` stage (#93) so the freshly generated set is shown exactly once before
 * moving on to `enabled` -- the only place either flow's codes are ever visible.
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
  private readonly defaultAgentStore = inject(DefaultAgentStore);

  @Output() closed = new EventEmitter<void>();

  readonly defaultAgent = this.defaultAgentStore.agent;

  readonly stage = signal<TwoFactorStage>('loading');
  readonly loadError = signal<string | null>(null);
  readonly enrollment = signal<TwoFactorEnrollment | null>(null);
  readonly backupCodes = signal<string[]>([]);

  confirmCode = '';
  confirming = false;
  confirmError: string | null = null;

  password = '';
  disabling = false;
  disableError: string | null = null;

  readonly regeneratingBackupCodes = signal(false);
  regeneratePassword = '';
  regenerating = false;
  regenerateError: string | null = null;

  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.closed.emit();
  }

  chooseDefaultAgent(agent: DefaultAgent): void {
    this.defaultAgentStore.set(agent);
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
      next: (result) => {
        this.confirming = false;
        this.enrollment.set(null);
        this.backupCodes.set(result.backupCodes);
        this.stage.set('backup-codes');
      },
      error: (err: Error) => {
        this.confirming = false;
        this.confirmError = err.message;
      },
    });
  }

  /** Leaves the backup-codes stage, reached either from a fresh enrollment or a regeneration. */
  acknowledgeBackupCodes(): void {
    this.backupCodes.set([]);
    this.stage.set('enabled');
  }

  startRegenerateBackupCodes(): void {
    this.regeneratePassword = '';
    this.regenerateError = null;
    this.regeneratingBackupCodes.set(true);
  }

  cancelRegenerateBackupCodes(): void {
    this.regeneratePassword = '';
    this.regenerateError = null;
    this.regeneratingBackupCodes.set(false);
  }

  regenerateBackupCodes(): void {
    if (!this.regeneratePassword || this.regenerating) {
      return;
    }
    this.regenerating = true;
    this.regenerateError = null;
    this.accountService.regenerateBackupCodes(this.regeneratePassword).subscribe({
      next: (result) => {
        this.regenerating = false;
        this.regeneratePassword = '';
        this.regeneratingBackupCodes.set(false);
        this.backupCodes.set(result.backupCodes);
        this.stage.set('backup-codes');
      },
      error: (err: Error) => {
        this.regenerating = false;
        this.regenerateError = err.message;
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
