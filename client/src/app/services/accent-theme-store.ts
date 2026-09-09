import { Injectable, signal } from '@angular/core';
import { deriveProjectBackgroundTint } from './project-accent-tint';

const STORAGE_KEY = 'locklane.accentTheme';
const HEX_COLOR = /^#[0-9a-fA-F]{6}$/;

/** The built-in terracotta from `styles.css`'s own `--accent` default (#843). */
export const DEFAULT_ACCENT = '#c15f3c';

export interface AccentTheme {
  readonly accent: string;
  readonly accentSoft: string;
}

/**
 * The chosen accent color (#387, replaced from four fixed presets to a free picker by
 * #843), client-only and persisted in localStorage -- consistent with
 * {@link DefaultAgentStore}, there is nothing server-side to keep this in sync with.
 * `accentSoft` is derived from the raw hex at the same ~13% blend-with-white
 * {@link deriveProjectBackgroundTint} already computes for a project's own accent, so
 * the two stay visually consistent without a second, hand-picked color to keep in sync.
 * Applies the theme's `--accent` / `--accent-soft` onto the document root itself,
 * rather than through a template binding, so every component's existing
 * `var(--accent)` usage picks it up unchanged and it takes effect on the very first
 * paint -- {@link AppComponent} injects this store eagerly (it is otherwise unused
 * there) purely to trigger that constructor-time apply before the settings dialog is
 * ever opened. Also updates the `theme-color` meta tag to the derived soft tint (#825)
 * so an installed PWA's window-controls-overlay strip stays in sync with a live
 * `choose()` -- the meta tag's own first-paint-correct value on reload is
 * `index.html`'s inline script's job (#836), since Chrome does not reliably honour a
 * mutation landing before the page's first real paint, and this constructor-time apply
 * runs only once Angular has bootstrapped, after that first paint.
 */
@Injectable({ providedIn: 'root' })
export class AccentThemeStore {
  private readonly themeSignal = signal<AccentTheme>(load());
  readonly theme = this.themeSignal.asReadonly();

  constructor() {
    apply(this.themeSignal());
  }

  choose(accent: string): void {
    const theme = buildTheme(accent);
    this.themeSignal.set(theme);
    apply(theme);
    save(accent);
  }

  /** Restores the built-in terracotta default and removes the stored choice entirely. */
  reset(): void {
    const theme = buildTheme(DEFAULT_ACCENT);
    this.themeSignal.set(theme);
    apply(theme);
    clear();
  }
}

function buildTheme(accent: string): AccentTheme {
  // Never null: `accent` is always a 6-digit hex here (a native color input's value,
  // the default, or a stored value already checked against HEX_COLOR by load()).
  return { accent, accentSoft: deriveProjectBackgroundTint(accent)! };
}

function load(): AccentTheme {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return buildTheme(stored && HEX_COLOR.test(stored) ? stored : DEFAULT_ACCENT);
  } catch {
    return buildTheme(DEFAULT_ACCENT);
  }
}

function save(accent: string): void {
  try {
    localStorage.setItem(STORAGE_KEY, accent);
  } catch {
    // Storage unavailable (private browsing, quota) -- the choice still works for this
    // session, it just won't survive a reload.
  }
}

function clear(): void {
  try {
    localStorage.removeItem(STORAGE_KEY);
  } catch {
    // Storage unavailable (private browsing, quota) -- nothing was persisted anyway.
  }
}

function apply(theme: AccentTheme): void {
  document.documentElement.style.setProperty('--accent', theme.accent);
  document.documentElement.style.setProperty('--accent-soft', theme.accentSoft);
  document
    .querySelector('meta[name="theme-color"]')
    ?.setAttribute('content', theme.accentSoft);
}
