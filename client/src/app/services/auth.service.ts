import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, catchError, map, of, tap } from 'rxjs';

const FORM_HEADERS = new HttpHeaders({ 'Content-Type': 'application/x-www-form-urlencoded' });

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
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly loggedIn = signal(false);
  private readonly user = signal<string | null>(null);
  readonly isLoggedIn = this.loggedIn.asReadonly();
  readonly username = this.user.asReadonly();

  login(username: string, password: string): Observable<void> {
    const body = new HttpParams().set('username', username).set('password', password);
    return this.http.post<void>('/api/auth/login', body.toString(), { headers: FORM_HEADERS }).pipe(
      tap(() => {
        this.loggedIn.set(true);
        this.user.set(username);
      }),
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

  logout(): Observable<void> {
    return this.http.post<void>('/api/auth/logout', null).pipe(
      tap(() => {
        this.loggedIn.set(false);
        this.user.set(null);
      }),
    );
  }
}
