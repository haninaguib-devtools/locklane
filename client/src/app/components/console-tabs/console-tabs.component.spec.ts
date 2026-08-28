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

  it('emits the picked location and agent, and closes the picker (locationChoice=false flow)', () => {
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

  it('emits close only after the user confirms', () => {
    const c = new ConsoleTabsComponent();
    spyOn(window, 'confirm').and.returnValue(true);
    let emitted: string | undefined;
    c.close.subscribe((id) => (emitted = id));
    const event = new Event('click');
    const stopSpy = spyOn(event, 'stopPropagation');

    c.closeTab('7-rename-toggle', event);

    expect(window.confirm).toHaveBeenCalled();
    expect(stopSpy).toHaveBeenCalled();
    expect(emitted).toBe('7-rename-toggle');
  });

  it('emits nothing when the user cancels the confirmation', () => {
    const c = new ConsoleTabsComponent();
    spyOn(window, 'confirm').and.returnValue(false);
    let emitted: string | undefined;
    c.close.subscribe((id) => (emitted = id));

    c.closeTab('7-rename-toggle', new Event('click'));

    expect(emitted).toBeUndefined();
  });
});
