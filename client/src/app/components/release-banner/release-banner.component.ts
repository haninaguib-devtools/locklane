import { Component, inject } from '@angular/core';
import { ReleaseUpdateService } from '../../services/release-update.service';

/**
 * Informational header banner shown once the engine has told us (over `/ws/events`'
 * `releaseAvailable` message, #287) that a newer permanent GitHub release exists than
 * the one currently running -- unlike `app-update-banner`'s reload prompt, there is
 * nothing to click here: this only informs, since there is no in-app way to trigger an
 * engine update.
 */
@Component({
  selector: 'app-release-banner',
  standalone: true,
  templateUrl: './release-banner.component.html',
  styleUrl: './release-banner.component.css',
})
export class ReleaseBannerComponent {
  private readonly releaseUpdate = inject(ReleaseUpdateService);

  readonly version = this.releaseUpdate.version;
}
