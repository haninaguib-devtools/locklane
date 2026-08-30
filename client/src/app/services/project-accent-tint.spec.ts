import { deriveProjectBackgroundTint } from './project-accent-tint';

describe('deriveProjectBackgroundTint', () => {
  it('returns null when no accent color is set', () => {
    expect(deriveProjectBackgroundTint(null)).toBeNull();
  });

  it('returns null for a value that is not a 6-digit hex color', () => {
    expect(deriveProjectBackgroundTint('terracotta')).toBeNull();
    expect(deriveProjectBackgroundTint('#fff')).toBeNull();
  });

  it('blends the accent color toward white at the same ratio the preset soft pairs use', () => {
    // terracotta: #c15f3c -> accentSoft #f7e9e2 (accent-theme-store.ts) at ~13%.
    expect(deriveProjectBackgroundTint('#c15f3c')).toBe('rgb(247, 234, 230)');
  });

  it('produces a visibly different tint for a different accent color', () => {
    const terracotta = deriveProjectBackgroundTint('#c15f3c');
    const slate = deriveProjectBackgroundTint('#4d6a8a');

    expect(slate).not.toEqual(terracotta);
  });

  it('is case-insensitive on the hex digits', () => {
    expect(deriveProjectBackgroundTint('#C15F3C')).toBe(deriveProjectBackgroundTint('#c15f3c'));
  });
});
