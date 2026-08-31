import { Injectable, inject, signal } from '@angular/core';
import { EventsService, isReleaseAvailableEvent } from './events.service';

/**
 * Tracks whether the engine has told us about a newer permanent GitHub release than the
 * one currently running (#287), for `app-release-banner` to show. Unlike
 * `AppUpdateService`, there is nothing to trigger here -- once a version is known, it is
 * shown for the rest of the session; the engine only ever moves this state forward
 * (absent to present, or to a higher version), never back.
 *
 * `url` (#466) is that release's GitHub Releases page, from the same event -- null when
 * an older engine sent a version-only payload, in which case the banner shows plain
 * text instead of a link.
 */
@Injectable({ providedIn: 'root' })
export class ReleaseUpdateService {
  private readonly events = inject(EventsService);

  private readonly versionSignal = signal<string | null>(null);
  readonly version = this.versionSignal.asReadonly();

  private readonly urlSignal = signal<string | null>(null);
  readonly url = this.urlSignal.asReadonly();

  constructor() {
    this.events.events$.subscribe((event) => {
      if (isReleaseAvailableEvent(event)) {
        this.versionSignal.set(event.version);
        this.urlSignal.set(event.url ?? null);
      }
    });
  }
}
