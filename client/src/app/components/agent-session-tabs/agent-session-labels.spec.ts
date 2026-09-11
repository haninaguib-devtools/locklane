import { labelAgentSessions, labelProjectAgentSessions, labelShellTabs, tabText } from './agent-session-labels';

// Session ids ("<projectId>-console[-<hex>]"), "<repo>-console-<hex>" worktree directories, the
// /console and /consoles REST paths and the 'console' route segment below keep their persisted and
// on-the-wire shape: compatibility surfaces kept under ADR-112 (#766 renamed only the identifiers).

describe('labelAgentSessions', () => {
  it('labels a lone agent session of each location without an index', () => {
    const tabs = labelAgentSessions([
      { id: '1-7-main-a1b2c3d4', agent: 'shell' },
      { id: '1-7-rename-toggle', agent: 'claude' },
    ]);

    expect(tabs.map((t) => t.label)).toEqual(['main · shell', 'wtree · claude']);
  });

  it('indexes from the second agent session of a location on', () => {
    const tabs = labelAgentSessions([
      { id: '1-7-main-a1b2c3d4', agent: 'claude' },
      { id: '1-7-main-e5f6a7b8', agent: 'shell' },
      { id: '1-7-rename-toggle', agent: 'codex' },
    ]);

    expect(tabs.map((t) => t.label)).toEqual(['main · claude', 'main 2 · shell', 'wtree · codex']);
  });

  it('omits the agent when it is unknown', () => {
    const tabs = labelAgentSessions([{ id: '1-7-rename-toggle', agent: null }]);

    expect(tabs[0].label).toBe('wtree');
  });

  it('tags a tab with omp the same way as any other known agent (#681)', () => {
    const tabs = labelAgentSessions([{ id: '1-7-rename-toggle', agent: 'omp' }]);

    expect(tabs[0].label).toBe('wtree · omp');
  });
});

describe('labelProjectAgentSessions (#449)', () => {
  it('labels a lone agent session with no index, and never an agent suffix (#456)', () => {
    const tabs = labelProjectAgentSessions([{ id: '1-console-a1b2c3d4', agent: 'codex' }]);

    expect(tabs.map((t) => t.label)).toEqual(['agent']);
  });

  it('indexes from the second agent session on, in the order given, with no agent suffix known or not (#456)', () => {
    const tabs = labelProjectAgentSessions([
      { id: '1-console-a1b2c3d4', agent: 'claude' },
      { id: '1-console-e5f6a7b8', agent: null },
      { id: '1-console-c9d0e1f2', agent: 'shell' },
    ]);

    expect(tabs.map((t) => t.label)).toEqual(['agent', 'agent 2', 'agent 3']);
  });

  it('carries the name through unchanged, for tabText() to read', () => {
    const tabs = labelProjectAgentSessions([{ id: '1-console-a1b2c3d4', agent: 'claude', name: 'release notes' }]);

    expect(tabs[0].name).toBe('release notes');
  });
});

describe('tabText (#393)', () => {
  it('shows the name the user gave, when there is one', () => {
    expect(tabText({ id: '7-console-a', agent: 'claude', label: 'agent session · claude', name: 'release notes' }))
      .toBe('release notes');
  });

  it('falls back to the auto label when the name is absent, null, or blank', () => {
    expect(tabText({ id: '7-console-a', agent: 'claude', label: 'agent' })).toBe('agent');
    expect(tabText({ id: '7-console-a', agent: 'claude', label: 'agent', name: null })).toBe('agent');
    expect(tabText({ id: '7-console-a', agent: 'claude', label: 'agent', name: '   ' })).toBe('agent');
  });
});

describe('labelShellTabs (#876)', () => {
  it('numbers shells on their own, independently of the agent tabs beside them', () => {
    const tabs = labelShellTabs([
      { id: '1-shell-main-aaaa0001', dir: '/work/main' },
      { id: '1-shell-main-bbbb0002', dir: '/work/main' },
    ]);

    expect(tabs.map((t) => t.label)).toEqual(['shell', 'shell 2']);
    expect(tabs.map((t) => t.kind)).toEqual(['shell', 'shell']);
    expect(tabs.map((t) => tabText(t))).toEqual(['shell', 'shell 2']);
  });

  it('carries the directory for the first attach, and the name when the shell has one', () => {
    const tabs = labelShellTabs([
      { id: '1-shell-main-aaaa0001', dir: '/work/main', name: 'tailing logs' },
    ]);

    expect(tabs[0].dir).toBe('/work/main');
    expect(tabText(tabs[0])).toBe('tailing logs');
  });
});
