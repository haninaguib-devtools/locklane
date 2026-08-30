import { TestBed } from '@angular/core/testing';
import { ACCENT_PRESETS, AccentThemeStore } from './accent-theme-store';

const STORAGE_KEY = 'locklane.accentTheme';
const [terracotta, sage] = ACCENT_PRESETS;

describe('AccentThemeStore', () => {
  beforeEach(() => {
    localStorage.removeItem(STORAGE_KEY);
    TestBed.configureTestingModule({});
    document.documentElement.style.removeProperty('--accent');
    document.documentElement.style.removeProperty('--accent-soft');
  });

  afterEach(() => {
    localStorage.removeItem(STORAGE_KEY);
    document.documentElement.style.removeProperty('--accent');
    document.documentElement.style.removeProperty('--accent-soft');
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
});
