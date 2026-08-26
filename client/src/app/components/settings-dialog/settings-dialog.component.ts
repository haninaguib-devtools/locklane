import { Component, EventEmitter, HostListener, Output } from '@angular/core';

/**
 * The settings dialog (#90): for now a shell -- a title bar and an empty body --
 * opened from the header's account menu. Its first real section, two-factor
 * authentication, arrives with #91. Visually it follows `add-project-popup`:
 * a full-page backdrop that dismisses on click, holding a bordered panel whose
 * own clicks do not.
 */
@Component({
  selector: 'app-settings-dialog',
  standalone: true,
  templateUrl: './settings-dialog.component.html',
  styleUrl: './settings-dialog.component.css',
})
export class SettingsDialogComponent {
  @Output() closed = new EventEmitter<void>();

  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.closed.emit();
  }
}
