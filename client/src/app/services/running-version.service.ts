import { Injectable, computed, inject } from '@angular/core';
import { EventsService } from './events.service';

/**
 * The version the running engine reports about itself (#467) -- the `release` field
 * of the `engineVersion` greeting, i.e. `BuildProperties#getVersion()` on the engine
 * (e.g. `0.1.0-SNAPSHOT` on a dev build) -- for the About dialog (#575) to display.
 * Mirrors `ReleaseUpdateService` (#287), which tracks the *newer-available* version;
 * this one tracks what is actually running.
 *
 * Derived from `EventsService.engineVersion`, the connection owner's own record of the
 * latest greeting, rather than subscribed off `events$` (#595): the greeting is sent
 * once per connection and the stream does not replay, so a subscription made only
 * when this service is first injected -- lazily, behind the About menu item -- would
 * miss it and report "unknown" forever. Reading state instead is correct however late
 * the first reader arrives, and follows every reconnect's greeting, so an engine
 * upgraded while the app stays open shows its new version, even in a dialog already
 * open. `null` until the first greeting, and for an engine too old to send `release`.
 */
@Injectable({ providedIn: 'root' })
export class RunningVersionService {
  private readonly events = inject(EventsService);

  readonly version = computed(() => this.events.engineVersion()?.release ?? null);
}
