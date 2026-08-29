import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../services/auth.service';

/**
 * Renders whenever {@link AuthService#isLoggedIn} is false -- see AppComponent.
 *
 * Up to two steps: credentials first, then -- only when login answers that a second
 * step is pending -- either a 2FA code (#92, or a backup code (#93) when the
 * authenticator device is unavailable; the engine tries both shapes), or a forced
 * password change (#238, #241) for an account created with `must_change_password` set.
 * The two pending steps are mutually exclusive (see {@link LoginResult}), and an
 * ordinary account never sees either.
 */
@Component({
  selector: 'app-login',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './login.component.html',
  styleUrl: './login.component.css',
})
export class LoginComponent {
  private readonly auth = inject(AuthService);

  username = '';
  password = '';
  code = '';
  newPassword = '';
  step: 'credentials' | 'code' | 'password-change' = 'credentials';
  error: string | null = null;
  submitting = false;

  submit(): void {
    this.error = null;
    this.submitting = true;
    this.auth.login(this.username, this.password).subscribe({
      // On a plain success there is nothing else to do -- AppComponent's @if
      // switches on AuthService's own isLoggedIn signal, which login() already set.
      next: ({ twoFactorRequired, mustChangePasswordRequired }) => {
        this.submitting = false;
        if (twoFactorRequired) {
          this.step = 'code';
        } else if (mustChangePasswordRequired) {
          this.step = 'password-change';
        }
      },
      error: () => {
        this.submitting = false;
        this.error = 'Incorrect username or password.';
      },
    });
  }

  submitCode(): void {
    this.error = null;
    this.submitting = true;
    this.auth.verifyTwoFactor(this.code).subscribe({
      next: () => (this.submitting = false),
      error: () => {
        this.submitting = false;
        this.error = 'That code is not correct.';
      },
    });
  }

  /** `password` still holds the temporary current password from {@link submit} -- no need to re-ask for it. */
  submitPasswordChange(): void {
    this.error = null;
    this.submitting = true;
    this.auth.completePasswordChange(this.password, this.newPassword).subscribe({
      next: () => (this.submitting = false),
      error: () => {
        this.submitting = false;
        this.error = 'Could not set that password. Check the current password and try again.';
      },
    });
  }
}
