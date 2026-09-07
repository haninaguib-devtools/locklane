import { Component, EventEmitter, HostListener, Output, inject } from '@angular/core';
import { RunningVersionService } from '../../services/running-version.service';

/**
 * The "About" dialog behind the account menu's About item (#575): the app name and
 * the version the running engine reported about itself (#467), which used to sit as
 * a footer line under the sidebar's usage widget. Same backdrop-and-panel shape as
 * `confirm-dialog`; Escape, the backdrop, and the Close button all dismiss it.
 */
@Component({
  selector: 'app-about-dialog',
  standalone: true,
  imports: [],
  templateUrl: './about-dialog.component.html',
  styleUrl: './about-dialog.component.css',
})
export class AboutDialogComponent {
  private readonly runningVersion = inject(RunningVersionService);

  // Null until the events channel's first greeting delivers it.
  readonly version = this.runningVersion.version;
  // Null for a -SNAPSHOT build, or an engine too old to send it (#799).
  readonly releaseUrl = this.runningVersion.releaseUrl;
  @Output() closed = new EventEmitter<void>();

  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.closed.emit();
  }
}
