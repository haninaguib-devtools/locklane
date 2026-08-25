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
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly loggedIn = signal(false);
  readonly isLoggedIn = this.loggedIn.asReadonly();

  login(username: string, password: string): Observable<void> {
    const body = new HttpParams().set('username', username).set('password', password);
    return this.http
      .post<void>('/api/auth/login', body.toString(), { headers: FORM_HEADERS })
      .pipe(tap(() => this.loggedIn.set(true)));
  }

  /** Restores `isLoggedIn` from the server-side session; never errors. */
  checkSession(): Observable<boolean> {
    return this.http.get('/api/auth/me').pipe(
      map(() => true),
      catchError(() => of(false)),
      tap((loggedIn) => this.loggedIn.set(loggedIn)),
    );
  }

  logout(): Observable<void> {
    return this.http.post<void>('/api/auth/logout', null).pipe(tap(() => this.loggedIn.set(false)));
  }
}
