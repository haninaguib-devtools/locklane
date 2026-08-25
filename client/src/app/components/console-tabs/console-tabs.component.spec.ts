import { ConsoleTabsComponent } from './console-tabs.component';

describe('ConsoleTabsComponent', () => {
  it('emits the clicked console id', () => {
    const c = new ConsoleTabsComponent();
    c.tabs = [
      { id: '7-main-a1b2c3d4', agent: 'shell', label: 'main · shell' },
      { id: '7-rename-toggle', agent: 'claude', label: 'wtree · claude' },
    ];
    let emitted: string | undefined;
    c.selectedChange.subscribe((id) => (emitted = id));

    c.select('7-rename-toggle');

    expect(emitted).toBe('7-rename-toggle');
  });

  it('emits the picked location and agent, and closes the picker', () => {
    const c = new ConsoleTabsComponent();
    c.pickerOpen = true;
    c.location = 'main';
    c.agent = 'codex';
    let emitted: { worktree: boolean; agent: string } | undefined;
    c.open.subscribe((request) => (emitted = request));

    c.confirmOpen();

    expect(emitted).toEqual({ worktree: false, agent: 'codex' });
    expect(c.pickerOpen).toBeFalse();
  });
});
