import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../services/auth.service';

/** Renders whenever {@link AuthService#isLoggedIn} is false -- see AppComponent. */
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
  error: string | null = null;
  submitting = false;

  submit(): void {
    this.error = null;
    this.submitting = true;
    this.auth.login(this.username, this.password).subscribe({
      // Nothing else to do on success -- AppComponent's @if switches on
      // AuthService's own isLoggedIn signal, which login() already set.
      next: () => (this.submitting = false),
      error: () => {
        this.submitting = false;
        this.error = 'Incorrect username or password.';
      },
    });
  }
}
