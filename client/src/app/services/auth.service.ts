import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, catchError, map, of, tap } from 'rxjs';
import { EventsService } from './events.service';

const FORM_HEADERS = new HttpHeaders({ 'Content-Type': 'application/x-www-form-urlencoded' });

/** What a successful `POST /api/auth/login` meant: session established, or a code pending. */
export interface LoginResult {
  twoFactorRequired: boolean;
}

/**
 * The engine's login/logout are Spring Security's `formLogin` endpoints (#47) --
 * cookie-session-based, expecting `username`/`password` as form fields, not JSON.
 * `checkSession` asks `GET /api/auth/me` whether the session cookie is still valid
 * (#58); the app initializer in `app.config.ts` runs it before first render, so a
 * page refresh restores `isLoggedIn` instead of bouncing to the login page.
 *
 * `username` (#90) is the signed-in account's name, for the header's account menu.
 * It comes from whichever call established the session -- the credentials on login,
 * the `/api/auth/me` body on a restore -- and is cleared on logout.
 *
 * With 2FA on the account (#89), correct credentials still answer 200 but the body
 * says `{"twoFactorRequired": true}` and the session is only *pending* -- `login`
 * surfaces that in its {@link LoginResult} without flipping `isLoggedIn`, and
 * `verifyTwoFactor` posts the 6-digit code that completes the login.
 *
 * The server-side session can also disappear later -- expired, invalidated, or lost to
 * a restart (#246). {@link sessionExpired} is what `unauthorizedInterceptor` calls on
 * any `401` to fall back to the login screen everywhere, not just from `checkSession`'s
 * own error path; the constructor also re-runs `checkSession` whenever the events
 * socket reconnects, so a tab sitting idle notices too.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly loggedIn = signal(false);
  private readonly user = signal<string | null>(null);
  readonly isLoggedIn = this.loggedIn.asReadonly();
  readonly username = this.user.asReadonly();

  constructor() {
    // The events socket reconnecting (#128) means the server was unreachable and is
    // back -- e.g. it restarted -- so an otherwise-idle tab that made no request in the
    // meantime still gets a chance to notice its session is gone (#246).
    inject(EventsService).reconnected$.subscribe(() => this.checkSession().subscribe());
  }

  login(username: string, password: string): Observable<LoginResult> {
    const body = new HttpParams().set('username', username).set('password', password);
    return this.http
      .post<{ twoFactorRequired?: boolean } | null>('/api/auth/login', body.toString(), { headers: FORM_HEADERS })
      .pipe(
        map((response) => ({ twoFactorRequired: response?.twoFactorRequired === true })),
        tap(({ twoFactorRequired }) => {
          if (!twoFactorRequired) {
            this.loggedIn.set(true);
            this.user.set(username);
          }
        }),
      );
  }

  /** Completes a login left pending by {@link login} -- errors with 401 on a wrong code. */
  verifyTwoFactor(code: string): Observable<void> {
    return this.http.post<{ username?: string }>('/api/auth/2fa/verify', { code }).pipe(
      tap((response) => {
        this.loggedIn.set(true);
        this.user.set(response?.username ?? null);
      }),
      map(() => undefined),
    );
  }

  /** Restores `isLoggedIn` from the server-side session; never errors. */
  checkSession(): Observable<boolean> {
    return this.http.get<{ username?: string }>('/api/auth/me').pipe(
      tap((body) => this.user.set(body?.username ?? null)),
      map(() => true),
      catchError(() => {
        this.user.set(null);
        return of(false);
      }),
      tap((loggedIn) => this.loggedIn.set(loggedIn)),
    );
  }

  /** Clears the logged-in state -- called by the 401 interceptor (#246) on any lost session. */
  sessionExpired(): void {
    this.loggedIn.set(false);
    this.user.set(null);
  }

  logout(): Observable<void> {
    return this.http.post<void>('/api/auth/logout', null).pipe(
      tap(() => {
        this.loggedIn.set(false);
        this.user.set(null);
      }),
    );
  }
}
