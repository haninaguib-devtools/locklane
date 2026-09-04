import { labelConsoles, labelProjectConsoles, tabText } from './console-labels';

describe('labelConsoles', () => {
  it('labels a lone console of each location without an index', () => {
    const tabs = labelConsoles([
      { id: '1-7-main-a1b2c3d4', agent: 'shell' },
      { id: '1-7-rename-toggle', agent: 'claude' },
    ]);

    expect(tabs.map((t) => t.label)).toEqual(['main · shell', 'wtree · claude']);
  });

  it('indexes from the second console of a location on', () => {
    const tabs = labelConsoles([
      { id: '1-7-main-a1b2c3d4', agent: 'claude' },
      { id: '1-7-main-e5f6a7b8', agent: 'shell' },
      { id: '1-7-rename-toggle', agent: 'codex' },
    ]);

    expect(tabs.map((t) => t.label)).toEqual(['main · claude', 'main 2 · shell', 'wtree · codex']);
  });

  it('omits the agent when it is unknown', () => {
    const tabs = labelConsoles([{ id: '1-7-rename-toggle', agent: null }]);

    expect(tabs[0].label).toBe('wtree');
  });

  it('tags a tab with omp the same way as any other known agent (#681)', () => {
    const tabs = labelConsoles([{ id: '1-7-rename-toggle', agent: 'omp' }]);

    expect(tabs[0].label).toBe('wtree · omp');
  });
});

describe('labelProjectConsoles (#449)', () => {
  it('labels a lone console with no index, and never an agent suffix (#456)', () => {
    const tabs = labelProjectConsoles([{ id: '1-console-a1b2c3d4', agent: 'codex' }]);

    expect(tabs.map((t) => t.label)).toEqual(['console']);
  });

  it('indexes from the second console on, in the order given, with no agent suffix known or not (#456)', () => {
    const tabs = labelProjectConsoles([
      { id: '1-console-a1b2c3d4', agent: 'claude' },
      { id: '1-console-e5f6a7b8', agent: null },
      { id: '1-console-c9d0e1f2', agent: 'shell' },
    ]);

    expect(tabs.map((t) => t.label)).toEqual(['console', 'console 2', 'console 3']);
  });

  it('carries the name through unchanged, for tabText() to read', () => {
    const tabs = labelProjectConsoles([{ id: '1-console-a1b2c3d4', agent: 'claude', name: 'release notes' }]);

    expect(tabs[0].name).toBe('release notes');
  });
});

describe('tabText (#393)', () => {
  it('shows the name the user gave, when there is one', () => {
    expect(tabText({ id: '7-console-a', agent: 'claude', label: 'console · claude', name: 'release notes' }))
      .toBe('release notes');
  });

  it('falls back to the auto label when the name is absent, null, or blank', () => {
    expect(tabText({ id: '7-console-a', agent: 'claude', label: 'console' })).toBe('console');
    expect(tabText({ id: '7-console-a', agent: 'claude', label: 'console', name: null })).toBe('console');
    expect(tabText({ id: '7-console-a', agent: 'claude', label: 'console', name: '   ' })).toBe('console');
  });
});
