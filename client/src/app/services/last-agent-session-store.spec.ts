import { LastAgentSessionStore } from './last-agent-session-store';

// Session ids ("<projectId>-console[-<hex>]"), "<repo>-console-<hex>" worktree directories, the
// /console and /consoles REST paths and the 'console' route segment below keep their persisted and
// on-the-wire shape: compatibility surfaces kept under ADR-112 (#766 renamed only the identifiers).

const STORAGE_KEY = 'locklane.lastConsole';

describe('LastAgentSessionStore', () => {
  beforeEach(() => localStorage.removeItem(STORAGE_KEY));
  afterEach(() => localStorage.removeItem(STORAGE_KEY));

  it('returns null for a project it has never seen', () => {
    expect(new LastAgentSessionStore().get(1)).toBeNull();
  });

  it('remembers a session across instances (i.e. across reloads)', () => {
    new LastAgentSessionStore().set(1, 'proj-1-console-abc');

    expect(new LastAgentSessionStore().get(1)).toBe('proj-1-console-abc');
  });

  it('keeps separate entries per project', () => {
    const store = new LastAgentSessionStore();
    store.set(1, 'proj-1-console-abc');
    store.set(2, 'proj-2-console-def');

    expect(store.get(1)).toBe('proj-1-console-abc');
    expect(store.get(2)).toBe('proj-2-console-def');
  });

  it('drops entries that are not a session id string', () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ 1: 'proj-1-console-abc', 2: 42 }));
    const store = new LastAgentSessionStore();

    expect(store.get(1)).toBe('proj-1-console-abc');
    expect(store.get(2)).toBeNull();
  });

  it('survives unparseable storage content', () => {
    localStorage.setItem(STORAGE_KEY, 'not json');

    expect(new LastAgentSessionStore().get(1)).toBeNull();
  });
});
