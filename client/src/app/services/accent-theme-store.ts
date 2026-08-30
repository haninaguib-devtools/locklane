import { Injectable, signal } from '@angular/core';

const STORAGE_KEY = 'locklane.accentTheme';

export interface AccentPreset {
  readonly id: string;
  readonly label: string;
  readonly accent: string;
  readonly accentSoft: string;
}

/**
 * The four presets #387 approves. `accentSoft` for terracotta and sage are the
 * existing `--accent-soft` / `--green-soft` tokens in `styles.css` (sage's accent is
 * the same color as the existing `--green`); slate and plum are new tints computed at
 * the same ~13% blend-with-white the existing pairs use, since no preset besides
 * terracotta and sage had an existing soft counterpart to reuse.
 */
export const ACCENT_PRESETS: readonly AccentPreset[] = [
  { id: 'terracotta', label: 'Terracotta', accent: '#c15f3c', accentSoft: '#f7e9e2' },
  { id: 'sage', label: 'Sage', accent: '#5c8a4e', accentSoft: '#ecf3e8' },
  { id: 'slate', label: 'Slate', accent: '#4d6a8a', accentSoft: '#e8ecf0' },
  { id: 'plum', label: 'Plum', accent: '#8a5568', accentSoft: '#f0e9eb' },
];

const DEFAULT_PRESET = ACCENT_PRESETS[0];

/**
 * The chosen accent color (#387), client-only and persisted in localStorage --
 * consistent with {@link DefaultAgentStore}, there is nothing server-side to keep this
 * in sync with. Applies the preset's `--accent` / `--accent-soft` onto the document
 * root itself, rather than through a template binding, so every component's existing
 * `var(--accent)` usage picks it up unchanged and it takes effect on the very first
 * paint -- {@link AppComponent} injects this store eagerly (it is otherwise unused
 * there) purely to trigger that constructor-time apply before the settings dialog is
 * ever opened.
 */
@Injectable({ providedIn: 'root' })
export class AccentThemeStore {
  private readonly presetSignal = signal<AccentPreset>(load());
  readonly preset = this.presetSignal.asReadonly();

  constructor() {
    apply(this.presetSignal());
  }

  choose(preset: AccentPreset): void {
    this.presetSignal.set(preset);
    apply(preset);
    save(preset.id);
  }
}

function load(): AccentPreset {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return ACCENT_PRESETS.find((preset) => preset.id === stored) ?? DEFAULT_PRESET;
  } catch {
    return DEFAULT_PRESET;
  }
}

function save(id: string): void {
  try {
    localStorage.setItem(STORAGE_KEY, id);
  } catch {
    // Storage unavailable (private browsing, quota) -- the choice still works for this
    // session, it just won't survive a reload.
  }
}

function apply(preset: AccentPreset): void {
  document.documentElement.style.setProperty('--accent', preset.accent);
  document.documentElement.style.setProperty('--accent-soft', preset.accentSoft);
}
