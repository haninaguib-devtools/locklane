import { AgentStore } from './agent-store';

const STORAGE_KEY = 'locklane.sessionAgents';

describe('AgentStore', () => {
  beforeEach(() => localStorage.removeItem(STORAGE_KEY));
  afterEach(() => localStorage.removeItem(STORAGE_KEY));

  it('returns null for a session it has never seen', () => {
    expect(new AgentStore().get('7-main-a1b2c3d4')).toBeNull();
  });

  it('remembers an agent across instances (i.e. across reloads)', () => {
    new AgentStore().set('7-main-a1b2c3d4', 'claude');

    expect(new AgentStore().get('7-main-a1b2c3d4')).toBe('claude');
  });

  it('drops entries that are not a string', () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ a: 'claude', b: 42, c: '' }));
    const store = new AgentStore();

    expect(store.get('a')).toBe('claude');
    expect(store.get('b')).toBeNull();
    expect(store.get('c')).toBeNull();
  });

  it('remembers any agent id the server ever sent, not just a fixed set', () => {
    new AgentStore().set('7-main-a1b2c3d4', 'omp');

    expect(new AgentStore().get('7-main-a1b2c3d4')).toBe('omp');
  });

  it('survives unparseable storage content', () => {
    localStorage.setItem(STORAGE_KEY, 'not json');

    expect(new AgentStore().get('anything')).toBeNull();
  });
});
