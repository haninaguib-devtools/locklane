import { ApplicationConfig, inject, provideAppInitializer, provideZoneChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { routes } from './app.routes';
import { AuthService } from './services/auth.service';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideHttpClient(),
    provideRouter(routes),
    // Ask the engine whether the session cookie is still valid before first
    // render (#58) -- otherwise a page refresh always starts logged-out and
    // bounces a still-authenticated user to the login page. checkSession never
    // errors, so a dead engine just renders the login page as before.
    provideAppInitializer(() => firstValueFrom(inject(AuthService).checkSession())),
  ],
};
