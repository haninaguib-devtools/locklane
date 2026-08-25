import { ActiveConsoleStore } from './active-console-store';

const STORAGE_KEY = 'locklane.activeConsoleByIssue';

describe('ActiveConsoleStore', () => {
  beforeEach(() => localStorage.removeItem(STORAGE_KEY));
  afterEach(() => localStorage.removeItem(STORAGE_KEY));

  it('returns null for an issue it has never seen', () => {
    expect(new ActiveConsoleStore().get(7)).toBeNull();
  });

  it('remembers the active console across instances (i.e. across reloads)', () => {
    new ActiveConsoleStore().set(7, '7-main-a1b2c3d4');

    expect(new ActiveConsoleStore().get(7)).toBe('7-main-a1b2c3d4');
  });

  it('keeps separate issues independent', () => {
    const store = new ActiveConsoleStore();
    store.set(7, '7-main-a1b2c3d4');
    store.set(8, '8-slug');

    expect(store.get(7)).toBe('7-main-a1b2c3d4');
    expect(store.get(8)).toBe('8-slug');
  });

  it('drops entries with a non-numeric issue key or non-string value', () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ '7': '7-main-a1b2c3d4', notanumber: 'x', '8': 42 }));
    const store = new ActiveConsoleStore();

    expect(store.get(7)).toBe('7-main-a1b2c3d4');
    expect(store.get(8)).toBeNull();
  });

  it('survives unparseable storage content', () => {
    localStorage.setItem(STORAGE_KEY, 'not json');

    expect(new ActiveConsoleStore().get(7)).toBeNull();
  });
});
