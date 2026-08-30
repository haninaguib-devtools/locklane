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

  it('the "+" starts a console with the default agent directly (#341: no location left to choose)', () => {
    const c = new ConsoleTabsComponent();
    c.defaultAgent = 'codex';
    let emitted: { agent: string } | undefined;
    c.open.subscribe((request) => (emitted = request));

    c.plusClicked();

    expect(emitted).toEqual({ agent: 'codex' });
  });

  it('shows the open button when there are no open tabs, even with hideOpenWhenActive set (#318)', () => {
    const c = new ConsoleTabsComponent();
    c.hideOpenWhenActive = true;
    c.tabs = [];

    expect(c.showOpenButton).toBeTrue();
  });

  it('hides the open button once a tab is open, when hideOpenWhenActive is set (#318)', () => {
    const c = new ConsoleTabsComponent();
    c.hideOpenWhenActive = true;
    c.tabs = [{ id: '7-rename-toggle', agent: 'claude', label: 'wtree · claude' }];

    expect(c.showOpenButton).toBeFalse();
  });

  it('keeps showing the open button with tabs open when hideOpenWhenActive is not set', () => {
    const c = new ConsoleTabsComponent();
    c.tabs = [{ id: '7-rename-toggle', agent: 'claude', label: 'wtree · claude' }];

    expect(c.showOpenButton).toBeTrue();
  });

  it('closeTab stops propagation and awaits confirmation before emitting', () => {
    const c = new ConsoleTabsComponent();
    let emitted: string | undefined;
    c.close.subscribe((id) => (emitted = id));
    const event = new Event('click');
    const stopSpy = spyOn(event, 'stopPropagation');

    c.closeTab('7-rename-toggle', event);

    expect(stopSpy).toHaveBeenCalled();
    expect(c.pendingCloseId).toBe('7-rename-toggle');
    expect(emitted).toBeUndefined();
  });

  it('emits close when the pending close is confirmed', () => {
    const c = new ConsoleTabsComponent();
    let emitted: string | undefined;
    c.close.subscribe((id) => (emitted = id));
    c.closeTab('7-rename-toggle', new Event('click'));

    c.confirmClose();

    expect(emitted).toBe('7-rename-toggle');
    expect(c.pendingCloseId).toBeNull();
  });

  it('emits nothing when the pending close is cancelled', () => {
    const c = new ConsoleTabsComponent();
    let emitted: string | undefined;
    c.close.subscribe((id) => (emitted = id));
    c.closeTab('7-rename-toggle', new Event('click'));

    c.cancelClose();

    expect(emitted).toBeUndefined();
    expect(c.pendingCloseId).toBeNull();
  });
  it('does not start a rename where renaming is not enabled (#393: the issue page)', () => {
    const c = new ConsoleTabsComponent();
    c.startRename({ id: '7-console-aaaaaaaa', agent: 'claude', label: 'console' }, new Event('dblclick'));

    expect(c.renamingId).toBeNull();
  });

  it('seeds the field with the name already given, never with the auto label (#393)', () => {
    const c = new ConsoleTabsComponent();
    c.renamable = true;

    c.startRename({ id: '7-console-aaaaaaaa', agent: 'claude', label: 'console · claude' }, new Event('dblclick'));
    expect(c.draftName).toBe('');

    c.startRename(
      { id: '7-console-bbbbbbbb', agent: 'claude', label: 'console 2', name: 'release notes' },
      new Event('dblclick'),
    );
    expect(c.draftName).toBe('release notes');
  });

  it('emits the trimmed name on commit, and an empty string to clear it (#393)', () => {
    const c = new ConsoleTabsComponent();
    c.renamable = true;
    const emitted: { id: string; name: string }[] = [];
    c.rename.subscribe((request) => emitted.push(request));

    c.startRename({ id: '7-console-aaaaaaaa', agent: 'claude', label: 'console' }, new Event('dblclick'));
    c.onRenameInput('  release notes  ');
    c.commitRename();

    c.startRename({ id: '7-console-aaaaaaaa', agent: 'claude', label: 'console' }, new Event('dblclick'));
    c.onRenameInput('   ');
    c.commitRename();

    expect(emitted).toEqual([
      { id: '7-console-aaaaaaaa', name: 'release notes' },
      { id: '7-console-aaaaaaaa', name: '' },
    ]);
    expect(c.renamingId).toBeNull();
  });

  it('cancelling emits nothing (#393)', () => {
    const c = new ConsoleTabsComponent();
    c.renamable = true;
    let emitted = 0;
    c.rename.subscribe(() => emitted++);

    c.startRename({ id: '7-console-aaaaaaaa', agent: 'claude', label: 'console' }, new Event('dblclick'));
    c.onRenameInput('never saved');
    c.cancelRename();
    // A stray commit after cancelling has no tab to name, so it stays silent.
    c.commitRename();

    expect(emitted).toBe(0);
    expect(c.renamingId).toBeNull();
  });
});
