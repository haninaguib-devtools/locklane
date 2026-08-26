import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../services/auth.service';

/**
 * Renders whenever {@link AuthService#isLoggedIn} is false -- see AppComponent.
 *
 * Two steps (#92): credentials first, and -- only when login answers that a
 * two-factor code is pending -- a second step asking for the 6-digit code, or a
 * backup code (#93) when the authenticator device is unavailable; the engine tries
 * both shapes. Accounts without 2FA never see the second step.
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
  step: 'credentials' | 'code' = 'credentials';
  error: string | null = null;
  submitting = false;

  submit(): void {
    this.error = null;
    this.submitting = true;
    this.auth.login(this.username, this.password).subscribe({
      // On a plain success there is nothing else to do -- AppComponent's @if
      // switches on AuthService's own isLoggedIn signal, which login() already set.
      next: ({ twoFactorRequired }) => {
        this.submitting = false;
        if (twoFactorRequired) {
          this.step = 'code';
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
}
