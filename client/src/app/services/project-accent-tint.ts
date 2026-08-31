// The same ~13% blend-with-white the global accent presets' own `accentSoft`
// companions use (see accent-theme-store.ts) -- so a project's derived tint
// reads consistently with those hand-picked soft pairs even though this one
// is computed rather than chosen.
const TINT_RATIO = 0.13;

const HEX_COLOR = /^#([0-9a-fA-F]{6})$/;

/**
 * A light, low-contrast page-background wash derived from a project's raw
 * accent color (#428) -- computed client-side, per #427/#428's Done-when, from
 * nothing but the hex string the backend stores. Returns `null` for a project
 * with no accent color set, or one whose stored value doesn't match the
 * 6-digit hex shape #427's backend validation guarantees for anything it
 * actually accepted (defensive only -- never expected in practice).
 */
export function deriveProjectBackgroundTint(accentColor: string | null): string | null {
  if (accentColor === null) {
    return null;
  }
  const match = HEX_COLOR.exec(accentColor);
  if (!match) {
    return null;
  }
  const hex = match[1];
  const blend = (channel: number) => Math.round(255 * (1 - TINT_RATIO) + channel * TINT_RATIO);
  const r = blend(parseInt(hex.slice(0, 2), 16));
  const g = blend(parseInt(hex.slice(2, 4), 16));
  const b = blend(parseInt(hex.slice(4, 6), 16));
  return `rgb(${r}, ${g}, ${b})`;
}
