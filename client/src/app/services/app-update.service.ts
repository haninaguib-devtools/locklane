import { DOCUMENT } from '@angular/common';
import { Injectable, inject, signal } from '@angular/core';
import { SwUpdate, VersionReadyEvent } from '@angular/service-worker';
import { filter, merge } from 'rxjs';
import { AuthService } from './auth.service';
import { EventsService } from './events.service';

/**
 * Turns an engine-side version change on the events channel (#273) into a
 * user-visible "reload for the new version" prompt: a redeploy alone tells us the
 * engine restarted, not that the service worker has actually fetched a new client
 * bundle, so `SwUpdate.checkForUpdate()` is what confirms one is ready before anything
 * is shown.
 *
 * Also checks on `AuthService.sessionEstablished$` (#953): a redeploy resets the
 * engine's session store, so the user logging back in is often a faster, more
 * reliable signal that the engine is back than waiting on the events socket's own
 * exponential-backoff reconnect.
 */
@Injectable({ providedIn: 'root' })
export class AppUpdateService {
  private readonly swUpdate = inject(SwUpdate);
  private readonly events = inject(EventsService);
  private readonly auth = inject(AuthService);
  // Injected rather than read off the global `document` directly, so a test can
  // supply a fake location without touching the real page (which spyOn can't safely
  // do -- `Location#reload` isn't a configurable property in Chrome).
  private readonly document = inject(DOCUMENT);

  private readonly updateReadySignal = signal(false);
  readonly updateReady = this.updateReadySignal.asReadonly();

  constructor() {
    merge(this.events.versionChanged$, this.auth.sessionEstablished$).subscribe(() => {
      if (this.swUpdate.isEnabled) {
        this.swUpdate.checkForUpdate().catch(() => {
          // Nothing productive to do with a failed check here -- the next reconnect's
          // stamp mismatch (if the engine is still ahead) will try again.
        });
      }
    });

    this.swUpdate.versionUpdates
      .pipe(filter((event): event is VersionReadyEvent => event.type === 'VERSION_READY'))
      .subscribe(() => this.updateReadySignal.set(true));
  }

  /** Reloads the page into the now-ready new version. */
  reload(): void {
    this.document.location.reload();
  }
}
