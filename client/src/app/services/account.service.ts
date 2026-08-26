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

/** Maps the backend's `{"error": "..."}` body onto a plain message a template can show. */
function withPlainError<T>(source: Observable<T>): Observable<T> {
  return source.pipe(
    catchError((err: HttpErrorResponse) => throwError(() => new Error(err.error?.error ?? 'something went wrong'))),
  );
}

/**
 * Account self-service: the two-factor endpoints under `/api/account/2fa/**` added by
 * #88 (`AccountTwoFactorController`) — enroll starts (or restarts) a pending, unconfirmed
 * secret; confirm checks a code against it and turns 2FA on; disable checks the current
 * password and turns it off (#91).
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

  confirmTwoFactor(code: string): Observable<TwoFactorStatus> {
    return withPlainError(this.http.post<TwoFactorStatus>('/api/account/2fa/confirm', { code }));
  }

  disableTwoFactor(password: string): Observable<TwoFactorStatus> {
    return withPlainError(this.http.post<TwoFactorStatus>('/api/account/2fa/disable', { password }));
  }
}
