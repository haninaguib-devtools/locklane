import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';

/**
 * A `401` from any endpoint means the server no longer honors this session -- expired,
 * invalidated, or lost to a restart (#246). Wherever it happens, clear the logged-in
 * state so the app falls back to the login screen immediately, instead of only on the
 * one-time startup check `checkSession` already does.
 */
export const unauthorizedInterceptor: HttpInterceptorFn = (req, next) => {
  // inject() must run synchronously here, not inside catchError's callback --
  // by the time that runs the response may have arrived asynchronously, outside
  // the injection context the interceptor was invoked in.
  const auth = inject(AuthService);
  return next(req).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401) {
        auth.sessionExpired();
      }
      return throwError(() => error);
    }),
  );
};
