import { TestBed } from '@angular/core/testing';
import { ACCENT_PRESETS, AccentThemeStore } from './accent-theme-store';

const STORAGE_KEY = 'locklane.accentTheme';
const [terracotta, sage] = ACCENT_PRESETS;

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
    expect(create().preset()).toEqual(terracotta);
  });

  it('applies the default onto the document root as soon as it is constructed', () => {
    create();

    const style = getComputedStyle(document.documentElement);
    expect(style.getPropertyValue('--accent').trim()).toBe(terracotta.accent);
    expect(style.getPropertyValue('--accent-soft').trim()).toBe(terracotta.accentSoft);
  });

  it('remembers the choice across instances (i.e. across reloads)', () => {
    create().choose(sage);

    expect(create().preset()).toEqual(sage);
  });

  it('falls back to terracotta for unrecognized storage content', () => {
    localStorage.setItem(STORAGE_KEY, 'ultraviolet');

    expect(create().preset()).toEqual(terracotta);
  });

  it('updates the signal and the document root immediately on choose', () => {
    const store = create();

    store.choose(sage);

    expect(store.preset()).toEqual(sage);
    const style = getComputedStyle(document.documentElement);
    expect(style.getPropertyValue('--accent').trim()).toBe(sage.accent);
    expect(style.getPropertyValue('--accent-soft').trim()).toBe(sage.accentSoft);
  });

  it('persists the choice to localStorage', () => {
    create().choose(sage);

    expect(localStorage.getItem(STORAGE_KEY)).toBe(sage.id);
  });

  it('applies the stored preset onto the theme-color meta tag as soon as it is constructed', () => {
    localStorage.setItem(STORAGE_KEY, sage.id);

    create();

    expect(themeColorMeta.getAttribute('content')).toBe(sage.accentSoft);
  });

  it('updates the theme-color meta tag on choose', () => {
    const store = create();

    store.choose(sage);

    expect(themeColorMeta.getAttribute('content')).toBe(sage.accentSoft);
  });
});
