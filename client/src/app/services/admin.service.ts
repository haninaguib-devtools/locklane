import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, throwError } from 'rxjs';

export interface AdminUser {
  id: number;
  username: string;
  role: string;
  mustChangePassword: boolean;
  createdAt: string;
}

/**
 * What creating a user returns: the new account, plus the temporary password it was
 * given -- but only when the admin left the password blank and one was generated
 * server-side (#240). An admin-supplied password is never echoed back, since the admin
 * already knows it.
 */
export interface CreatedUser {
  user: AdminUser;
  temporaryPassword?: string;
}

/** Maps the backend's `{"error": "..."}` body onto a plain message a template can show. */
function withPlainError<T>(source: Observable<T>): Observable<T> {
  return source.pipe(
    catchError((err: HttpErrorResponse) => throwError(() => new Error(err.error?.error ?? 'something went wrong'))),
  );
}

/**
 * Admin-only account management (#240): `/api/admin/users`, gated server-side to an
 * admin caller (`SecurityConfig`'s `hasRole("ADMIN")` on `/api/admin/**`) -- this
 * service assumes its only caller, `AdminUsersDialogComponent`, is itself only ever
 * shown to a signed-in admin (`AuthService.isAdmin`); either way, a non-admin request
 * still gets rejected by the server, not merely hidden here.
 */
@Injectable({ providedIn: 'root' })
export class AdminService {
  private readonly http = inject(HttpClient);

  list(): Observable<AdminUser[]> {
    return withPlainError(this.http.get<AdminUser[]>('/api/admin/users'));
  }

  /**
   * `password` blank creates the account with a random temporary password, returned
   * once in the result (#240) -- the account has to change it on first login either
   * way (`must_change_password`, #238/#241).
   */
  create(username: string, password: string): Observable<CreatedUser> {
    return withPlainError(
      this.http.post<CreatedUser>('/api/admin/users', { username, password: password || null }),
    );
  }

  delete(id: number): Observable<void> {
    return withPlainError(this.http.delete<void>(`/api/admin/users/${id}`));
  }
}
