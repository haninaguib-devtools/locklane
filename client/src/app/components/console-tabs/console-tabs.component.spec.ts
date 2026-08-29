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

  it('the "+" starts a console with the default agent directly when there is no location to choose', () => {
    const c = new ConsoleTabsComponent();
    c.locationChoice = false;
    c.defaultAgent = 'codex';
    let emitted: { worktree: boolean; agent: string } | undefined;
    c.open.subscribe((request) => (emitted = request));

    c.plusClicked();

    expect(emitted).toEqual({ worktree: false, agent: 'codex' });
    expect(c.pickerOpen).toBeFalse();
  });

  it('the "+" opens the where picker instead of launching, when there is a location to choose', () => {
    const c = new ConsoleTabsComponent();
    c.locationChoice = true;
    let emitted: { worktree: boolean; agent: string } | undefined;
    c.open.subscribe((request) => (emitted = request));

    c.plusClicked();

    expect(emitted).toBeUndefined();
    expect(c.pickerOpen).toBeTrue();
  });

  it('launches immediately on the chosen location, using the default agent', () => {
    const c = new ConsoleTabsComponent();
    c.pickerOpen = true;
    c.defaultAgent = 'codex';
    let emitted: { worktree: boolean; agent: string } | undefined;
    c.open.subscribe((request) => (emitted = request));

    c.chooseLocation('main');

    expect(emitted).toEqual({ worktree: false, agent: 'codex' });
    expect(c.pickerOpen).toBeFalse();
  });

  it('the "+" launches a worktree console directly when directWorktree is set (#318)', () => {
    const c = new ConsoleTabsComponent();
    c.locationChoice = false;
    c.directWorktree = true;
    c.defaultAgent = 'claude';
    let emitted: { worktree: boolean; agent: string } | undefined;
    c.open.subscribe((request) => (emitted = request));

    c.plusClicked();

    expect(emitted).toEqual({ worktree: true, agent: 'claude' });
    expect(c.pickerOpen).toBeFalse();
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

  it('closes the picker on a click outside it', () => {
    const c = new ConsoleTabsComponent();
    c.pickerOpen = true;

    c.onDocumentClick({ target: document.createElement('div') } as unknown as MouseEvent);

    expect(c.pickerOpen).toBeFalse();
  });

  it('leaves the picker open on a click inside the component', () => {
    const c = new ConsoleTabsComponent();
    c.pickerOpen = true;
    const host = document.createElement('app-console-tabs');
    const insideButton = document.createElement('button');
    host.appendChild(insideButton);

    c.onDocumentClick({ target: insideButton } as unknown as MouseEvent);

    expect(c.pickerOpen).toBeTrue();
  });

  it('closes the picker on Escape', () => {
    const c = new ConsoleTabsComponent();
    c.pickerOpen = true;

    c.onEscape();

    expect(c.pickerOpen).toBeFalse();
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
});
