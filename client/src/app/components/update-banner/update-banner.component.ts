import { Component, inject } from '@angular/core';
import { AppUpdateService } from '../../services/app-update.service';

/**
 * The reload prompt that appears once the service worker has fetched a new client
 * bundle after an engine redeploy (#273) -- reload is a deliberate click, never
 * automatic, so nothing here discards work the user has in progress.
 */
@Component({
  selector: 'app-update-banner',
  standalone: true,
  templateUrl: './update-banner.component.html',
  styleUrl: './update-banner.component.css',
})
export class UpdateBannerComponent {
  private readonly updateService = inject(AppUpdateService);

  readonly visible = this.updateService.updateReady;

  reload(): void {
    this.updateService.reload();
  }
}
