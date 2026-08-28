import { ApplicationConfig, inject, provideAppInitializer, provideZoneChangeDetection, isDevMode } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { routes } from './app.routes';
import { AuthService } from './services/auth.service';
import { EventsService } from './services/events.service';
import { unauthorizedInterceptor } from './services/unauthorized.interceptor';
import { provideServiceWorker } from '@angular/service-worker';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    // Falls back to the login screen on any 401, from any request (#246) -- see
    // unauthorized.interceptor.ts.
    provideHttpClient(withInterceptors([unauthorizedInterceptor])),
    provideRouter(routes),
    // Ask the engine whether the session cookie is still valid before first
    // render (#58) -- otherwise a page refresh always starts logged-out and
    // bounces a still-authenticated user to the login page. checkSession never
    // errors, so a dead engine just renders the login page as before.
    provideAppInitializer(() => firstValueFrom(inject(AuthService).checkSession())),
    // Opens the app-wide events channel (#128) as soon as the app boots; it manages
    // its own reconnects from here on, so nothing else needs to call connect() again.
    provideAppInitializer(() => inject(EventsService).connect()),
    provideServiceWorker('ngsw-worker.js', {
      enabled: !isDevMode(),
      registrationStrategy: 'registerWhenStable:30000',
    }),
  ],
};
