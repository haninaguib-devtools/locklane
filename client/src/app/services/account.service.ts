import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, throwError } from 'rxjs';

export interface TwoFactorStatus {
  enabled: boolean;
}

export interface TwoFactorEnrollment {
  qrCodeDataUri: string;
  manualKey: string;
  otpauthUri: string;
}

/** What confirming an enrollment returns: 2FA is on, and a backup code set (#93) shown once. */
export interface TwoFactorConfirmResult {
  enabled: boolean;
  backupCodes: string[];
}

export interface BackupCodes {
  backupCodes: string[];
}

/** Maps the backend's `{"error": "..."}` body onto a plain message a template can show. */
function withPlainError<T>(source: Observable<T>): Observable<T> {
  return source.pipe(
    catchError((err: HttpErrorResponse) => throwError(() => new Error(err.error?.error ?? 'something went wrong'))),
  );
}

/**
 * Account self-service: the two-factor endpoints under `/api/account/2fa/**` added by
 * #88 (`AccountTwoFactorController`) — enroll starts (or restarts) a pending, unconfirmed
 * secret; confirm checks a code against it and turns 2FA on, handing back a backup code
 * set (#93) shown once; disable checks the current password and turns it off (#91).
 * `regenerateBackupCodes` replaces that set, also behind the current password.
 *
 * `changePassword` (#241) posts to `/api/account/password` (`AccountPasswordController`) --
 * the current password plus a new one, for a signed-in user changing their password
 * voluntarily. The forced-first-login version of the same change is a different, deliberately
 * unauthenticated endpoint handled by `AuthService.completePasswordChange` instead, since that
 * one runs before a session exists to call this one with.
 */
@Injectable({ providedIn: 'root' })
export class AccountService {
  private readonly http = inject(HttpClient);

  twoFactorStatus(): Observable<TwoFactorStatus> {
    return withPlainError(this.http.get<TwoFactorStatus>('/api/account/2fa/status'));
  }

  enrollTwoFactor(): Observable<TwoFactorEnrollment> {
    return withPlainError(this.http.post<TwoFactorEnrollment>('/api/account/2fa/enroll', null));
  }

  confirmTwoFactor(code: string): Observable<TwoFactorConfirmResult> {
    return withPlainError(this.http.post<TwoFactorConfirmResult>('/api/account/2fa/confirm', { code }));
  }

  disableTwoFactor(password: string): Observable<TwoFactorStatus> {
    return withPlainError(this.http.post<TwoFactorStatus>('/api/account/2fa/disable', { password }));
  }

  regenerateBackupCodes(password: string): Observable<BackupCodes> {
    return withPlainError(
      this.http.post<BackupCodes>('/api/account/2fa/backup-codes/regenerate', { password }),
    );
  }

  changePassword(currentPassword: string, newPassword: string): Observable<void> {
    return withPlainError(
      this.http.post<void>('/api/account/password', { currentPassword, newPassword }),
    );
  }
}
