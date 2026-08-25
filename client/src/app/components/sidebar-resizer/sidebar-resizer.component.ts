import { Component, EventEmitter, Input, Output } from '@angular/core';
import { SIDEBAR_MAX_WIDTH, SIDEBAR_MIN_WIDTH, clampSidebarWidth } from './sidebar-width';

// A "dumb" presentational drag handle: it knows nothing about localStorage or
// where the width is applied, only how to turn pointer/keyboard input into a
// clamped width and emit it. The parent owns the actual width state.
@Component({
  selector: 'app-sidebar-resizer',
  standalone: true,
  templateUrl: './sidebar-resizer.component.html',
  styleUrl: './sidebar-resizer.component.css',
})
export class SidebarResizerComponent {
  @Input({ required: true }) width!: number;
  @Output() widthChange = new EventEmitter<number>();

  readonly min = SIDEBAR_MIN_WIDTH;
  readonly max = SIDEBAR_MAX_WIDTH;

  private dragging = false;
  private originX = 0;
  private originWidth = 0;

  onPointerDown(event: PointerEvent): void {
    this.dragging = true;
    this.originX = event.clientX;
    this.originWidth = this.width;
    (event.target as Element).setPointerCapture(event.pointerId);
  }

  onPointerMove(event: PointerEvent): void {
    if (!this.dragging) {
      return;
    }
    this.widthChange.emit(clampSidebarWidth(this.originWidth + (event.clientX - this.originX)));
  }

  onPointerUp(event: PointerEvent): void {
    this.dragging = false;
    (event.target as Element).releasePointerCapture(event.pointerId);
  }

  onKeyDown(event: KeyboardEvent): void {
    const step = 16;
    switch (event.key) {
      case 'ArrowLeft':
        this.widthChange.emit(clampSidebarWidth(this.width - step));
        event.preventDefault();
        break;
      case 'ArrowRight':
        this.widthChange.emit(clampSidebarWidth(this.width + step));
        event.preventDefault();
        break;
      case 'Home':
        this.widthChange.emit(this.min);
        event.preventDefault();
        break;
      case 'End':
        this.widthChange.emit(this.max);
        event.preventDefault();
        break;
    }
  }
}
