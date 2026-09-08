import { Directive, DestroyRef, NgZone, inject, signal } from '@angular/core';

/** #741's actual-visibility gate, shared by the main and minimal shells.
 * Native CSS environment values update the geometry without rebuilding content. */
@Directive({
  selector: '[appWindowChrome]',
  standalone: true,
  host: { '[class.wco-active]': 'visible()' },
})
export class WindowChromeDirective {
  readonly visible = signal(false);

  constructor() {
    const overlay = navigator.windowControlsOverlay;
    if (!overlay) return;
    const zone = inject(NgZone);
    const update = () => zone.run(() => this.visible.set(overlay.visible));
    update();
    overlay.addEventListener('geometrychange', update);
    inject(DestroyRef).onDestroy(() => overlay.removeEventListener('geometrychange', update));
  }
}
