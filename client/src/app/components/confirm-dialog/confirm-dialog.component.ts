import { Component, EventEmitter, HostListener, Input, Output } from '@angular/core';

/**
 * App-styled replacement for the browser's native `confirm()` (#231): a full-page
 * backdrop that dismisses on click, holding a bordered panel whose own clicks do not --
 * the same visual pattern `settings-dialog`/`add-project-popup` already use, rather than
 * a new one. Every "are you sure" prompt in the app renders this, parameterized by its
 * caller: project delete (`project-summary`, `sidenav`) and closing a console
 * (`console-tabs`).
 */
@Component({
  selector: 'app-confirm-dialog',
  standalone: true,
  imports: [],
  templateUrl: './confirm-dialog.component.html',
  styleUrl: './confirm-dialog.component.css',
})
export class ConfirmDialogComponent {
  @Input() title = 'Are you sure?';
  @Input({ required: true }) message!: string;
  @Input() confirmLabel = 'Confirm';
  @Input() cancelLabel = 'Cancel';
  // Styles the confirm button as a destructive action (delete, close) rather than a
  // neutral one.
  @Input() danger = false;
  @Output() confirmed = new EventEmitter<void>();
  @Output() cancelled = new EventEmitter<void>();

  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.cancelled.emit();
  }
}
