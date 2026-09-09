import { TestBed } from '@angular/core/testing';
import { AccentThemeStore, DEFAULT_ACCENT } from './accent-theme-store';

const STORAGE_KEY = 'locklane.accentTheme';
// terracotta (#c15f3c) -> ~13% blend-with-white, the same math project-accent-tint.ts's
// deriveProjectBackgroundTint uses (see its own spec for the arithmetic).
const DEFAULT_SOFT = 'rgb(247, 234, 230)';
const SAGE = '#5c8a4e';
const SAGE_SOFT = 'rgb(234, 240, 232)';

describe('AccentThemeStore', () => {
  // The Karma test host has no <meta name="theme-color"> of its own (unlike
  // index.html in the real app) -- stand one up so apply()'s lookup has something
  // to find.
  let themeColorMeta: HTMLMetaElement;

  beforeEach(() => {
    localStorage.removeItem(STORAGE_KEY);
    TestBed.configureTestingModule({});
    document.documentElement.style.removeProperty('--accent');
    document.documentElement.style.removeProperty('--accent-soft');
    themeColorMeta = document.createElement('meta');
    themeColorMeta.setAttribute('name', 'theme-color');
    document.head.appendChild(themeColorMeta);
  });

  afterEach(() => {
    localStorage.removeItem(STORAGE_KEY);
    document.documentElement.style.removeProperty('--accent');
    document.documentElement.style.removeProperty('--accent-soft');
    themeColorMeta.remove();
  });

  function create(): AccentThemeStore {
    return TestBed.inject(AccentThemeStore);
  }

  it('defaults to terracotta when nothing is stored', () => {
    expect(create().theme()).toEqual({ accent: DEFAULT_ACCENT, accentSoft: DEFAULT_SOFT });
  });

  it('applies the default onto the document root as soon as it is constructed', () => {
    create();

    const style = getComputedStyle(document.documentElement);
    expect(style.getPropertyValue('--accent').trim()).toBe(DEFAULT_ACCENT);
    expect(style.getPropertyValue('--accent-soft').trim()).toBe(DEFAULT_SOFT);
  });

  it('remembers the choice across instances (i.e. across reloads)', () => {
    create().choose(SAGE);

    expect(create().theme()).toEqual({ accent: SAGE, accentSoft: SAGE_SOFT });
  });

  it('falls back to terracotta for unrecognized storage content', () => {
    localStorage.setItem(STORAGE_KEY, 'ultraviolet');

    expect(create().theme()).toEqual({ accent: DEFAULT_ACCENT, accentSoft: DEFAULT_SOFT });
  });

  it('updates the signal and the document root immediately on choose', () => {
    const store = create();

    store.choose(SAGE);

    expect(store.theme()).toEqual({ accent: SAGE, accentSoft: SAGE_SOFT });
    const style = getComputedStyle(document.documentElement);
    expect(style.getPropertyValue('--accent').trim()).toBe(SAGE);
    expect(style.getPropertyValue('--accent-soft').trim()).toBe(SAGE_SOFT);
  });

  it('persists the choice to localStorage', () => {
    create().choose(SAGE);

    expect(localStorage.getItem(STORAGE_KEY)).toBe(SAGE);
  });

  it('applies the stored color onto the theme-color meta tag as soon as it is constructed', () => {
    localStorage.setItem(STORAGE_KEY, SAGE);

    create();

    expect(themeColorMeta.getAttribute('content')).toBe(SAGE_SOFT);
  });

  it('updates the theme-color meta tag on choose', () => {
    const store = create();

    store.choose(SAGE);

    expect(themeColorMeta.getAttribute('content')).toBe(SAGE_SOFT);
  });

  it('restores the default and removes the stored choice on reset', () => {
    const store = create();
    store.choose(SAGE);

    store.reset();

    expect(store.theme()).toEqual({ accent: DEFAULT_ACCENT, accentSoft: DEFAULT_SOFT });
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
    const style = getComputedStyle(document.documentElement);
    expect(style.getPropertyValue('--accent').trim()).toBe(DEFAULT_ACCENT);
    expect(style.getPropertyValue('--accent-soft').trim()).toBe(DEFAULT_SOFT);
    expect(themeColorMeta.getAttribute('content')).toBe(DEFAULT_SOFT);
  });
});
