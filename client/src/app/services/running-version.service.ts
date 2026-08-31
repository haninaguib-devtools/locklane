import { Injectable, inject, signal } from '@angular/core';
import { EventsService, isEngineVersionEvent } from './events.service';

/**
 * Holds the version the running engine reports about itself (#467) -- the `release`
 * field of the `engineVersion` greeting, i.e. `BuildProperties#getVersion()` on the
 * engine (e.g. `0.1.0-SNAPSHOT` on a dev build) -- for the sidenav footer to display.
 * Mirrors `ReleaseUpdateService` (#287), which tracks the *newer-available* version;
 * this one tracks what is actually running. Kept as its own service so
 * `EventsService` stays plain socket plumbing.
 */
@Injectable({ providedIn: 'root' })
export class RunningVersionService {
  private readonly events = inject(EventsService);

  private readonly versionSignal = signal<string | null>(null);
  readonly version = this.versionSignal.asReadonly();

  constructor() {
    this.events.events$.subscribe((event) => {
      if (isEngineVersionEvent(event) && typeof event.release === 'string') {
        this.versionSignal.set(event.release);
      }
    });
  }
}
