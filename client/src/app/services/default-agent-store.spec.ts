import { DefaultAgentStore } from './default-agent-store';

const STORAGE_KEY = 'locklane.defaultAgent';

describe('DefaultAgentStore', () => {
  beforeEach(() => localStorage.removeItem(STORAGE_KEY));
  afterEach(() => localStorage.removeItem(STORAGE_KEY));

  it('defaults to claude when nothing is stored', () => {
    expect(new DefaultAgentStore().agent()).toBe('claude');
  });

  it('remembers the choice across instances (i.e. across reloads)', () => {
    new DefaultAgentStore().set('codex');

    expect(new DefaultAgentStore().agent()).toBe('codex');
  });

  it('falls back to claude for unrecognized storage content', () => {
    localStorage.setItem(STORAGE_KEY, 'gpt-5');

    expect(new DefaultAgentStore().agent()).toBe('claude');
  });

  it('updates the signal immediately on set', () => {
    const store = new DefaultAgentStore();

    store.set('codex');

    expect(store.agent()).toBe('codex');
  });
});
