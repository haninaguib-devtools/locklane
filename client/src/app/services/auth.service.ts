import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

const FORM_HEADERS = new HttpHeaders({ 'Content-Type': 'application/x-www-form-urlencoded' });

/**
 * The engine's login/logout are Spring Security's `formLogin` endpoints (#47) --
 * cookie-session-based, expecting `username`/`password` as form fields, not JSON.
 * No "who am I" endpoint exists yet, so `isLoggedIn` only reflects this tab's own
 * login calls -- it starts false on every fresh load even if the session cookie is
 * still valid server-side (#49's record).
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

  logout(): Observable<void> {
    return this.http.post<void>('/api/auth/logout', null).pipe(tap(() => this.loggedIn.set(false)));
  }
}
