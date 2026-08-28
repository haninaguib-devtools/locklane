import { LastConsoleStore } from './last-console-store';

const STORAGE_KEY = 'locklane.lastConsole';

describe('LastConsoleStore', () => {
  beforeEach(() => localStorage.removeItem(STORAGE_KEY));
  afterEach(() => localStorage.removeItem(STORAGE_KEY));

  it('returns null for a project it has never seen', () => {
    expect(new LastConsoleStore().get(1)).toBeNull();
  });

  it('remembers a session across instances (i.e. across reloads)', () => {
    new LastConsoleStore().set(1, 'proj-1-console-abc');

    expect(new LastConsoleStore().get(1)).toBe('proj-1-console-abc');
  });

  it('keeps separate entries per project', () => {
    const store = new LastConsoleStore();
    store.set(1, 'proj-1-console-abc');
    store.set(2, 'proj-2-console-def');

    expect(store.get(1)).toBe('proj-1-console-abc');
    expect(store.get(2)).toBe('proj-2-console-def');
  });

  it('drops entries that are not a session id string', () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ 1: 'proj-1-console-abc', 2: 42 }));
    const store = new LastConsoleStore();

    expect(store.get(1)).toBe('proj-1-console-abc');
    expect(store.get(2)).toBeNull();
  });

  it('survives unparseable storage content', () => {
    localStorage.setItem(STORAGE_KEY, 'not json');

    expect(new LastConsoleStore().get(1)).toBeNull();
  });
});
