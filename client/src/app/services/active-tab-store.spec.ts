import { ActiveTabStore } from './active-tab-store';

const STORAGE_KEY = 'locklane.activeTabByIssue';

describe('ActiveTabStore', () => {
  beforeEach(() => localStorage.removeItem(STORAGE_KEY));
  afterEach(() => localStorage.removeItem(STORAGE_KEY));

  it('returns null for an issue it has never seen', () => {
    expect(new ActiveTabStore().get(7)).toBeNull();
  });

  it('remembers the active tab across instances (i.e. across reloads)', () => {
    new ActiveTabStore().set(7, '7-main-a1b2c3d4');

    expect(new ActiveTabStore().get(7)).toBe('7-main-a1b2c3d4');
  });

  it('remembers the overview sentinel like any other tab id', () => {
    new ActiveTabStore().set(7, 'overview');

    expect(new ActiveTabStore().get(7)).toBe('overview');
  });

  it('keeps separate issues independent', () => {
    const store = new ActiveTabStore();
    store.set(7, '7-main-a1b2c3d4');
    store.set(8, 'overview');

    expect(store.get(7)).toBe('7-main-a1b2c3d4');
    expect(store.get(8)).toBe('overview');
  });

  it('drops entries with a non-numeric issue key or non-string value', () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ '7': '7-main-a1b2c3d4', notanumber: 'x', '8': 42 }));
    const store = new ActiveTabStore();

    expect(store.get(7)).toBe('7-main-a1b2c3d4');
    expect(store.get(8)).toBeNull();
  });

  it('survives unparseable storage content', () => {
    localStorage.setItem(STORAGE_KEY, 'not json');

    expect(new ActiveTabStore().get(7)).toBeNull();
  });
});
