/**
 * The Window Controls Overlay API (Chromium-only) — not yet in TypeScript's
 * built-in DOM lib, so this augments `Navigator` with just what #741 reads:
 * whether the overlay is visible, and its `geometrychange` event.
 *
 * Previously housed under the Shells window (#446); moved here when #876
 * removed that window, since `window-chrome.directive.ts` still reads it.
 */
interface WindowControlsOverlay extends EventTarget {
  readonly visible: boolean;
  addEventListener(type: 'geometrychange', listener: () => void): void;
  removeEventListener(type: 'geometrychange', listener: () => void): void;
}

interface Navigator {
  readonly windowControlsOverlay?: WindowControlsOverlay;
}
