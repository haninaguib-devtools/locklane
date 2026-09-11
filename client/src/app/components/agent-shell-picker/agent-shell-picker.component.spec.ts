import { TestBed } from '@angular/core/testing';
import { AgentShellPickerComponent } from './agent-shell-picker.component';

// This component's own picker logic (#757, #876), relocated here out of the tab
// strip's spec now that both the tab strip and the sidenav share it (#886).

describe('AgentShellPickerComponent', () => {
  it('with no installed agents known, the "+" offers the default agent and Shell in the dropdown', () => {
    const c = new AgentShellPickerComponent();
    c.defaultAgent = 'codex';
    const opened: string[] = [];
    c.pickAgent.subscribe((agent) => opened.push(agent));
    let shelled = 0;
    c.pickShell.subscribe(() => shelled++);

    c.toggle();

    expect(c.offersPicker).toBeTrue();
    expect(c.open).toBeTrue();
    expect(opened).toEqual([]);
    expect(shelled).toBe(0);
    expect(c.agentChoices).toEqual([{ id: 'codex', label: 'codex' }]);
  });

  it('with exactly one installed agent, the "+" offers that agent and Shell in the dropdown', () => {
    const c = new AgentShellPickerComponent();
    c.defaultAgent = 'codex';
    c.installedAgents = [{ id: 'claude', label: 'Claude' }];

    c.toggle();

    expect(c.offersPicker).toBeTrue();
    expect(c.open).toBeTrue();
  });

  it('with Shell the only choice, the "+" mints a shell directly, no dropdown', () => {
    const c = new AgentShellPickerComponent();
    c.offerAgent = false;
    c.installedAgents = [
      { id: 'claude', label: 'Claude' },
      { id: 'codex', label: 'Codex' },
    ];
    let shelled = 0;
    c.pickShell.subscribe(() => shelled++);
    let opened = 0;
    c.pickAgent.subscribe(() => opened++);

    c.toggle();

    expect(c.offersPicker).toBeFalse();
    expect(c.open).toBeFalse();
    expect(shelled).toBe(1);
    expect(opened).toBe(0);
  });

  it('with no agent choice at all, the "+" mints a shell directly', () => {
    const c = new AgentShellPickerComponent();
    c.defaultAgent = '';
    let shelled = 0;
    c.pickShell.subscribe(() => shelled++);

    c.toggle();

    expect(c.offersPicker).toBeFalse();
    expect(shelled).toBe(1);
  });

  it('a disabled picker starts nothing at all', () => {
    const c = new AgentShellPickerComponent();
    c.disabled = true;
    c.installedAgents = [{ id: 'claude', label: 'Claude' }];
    let shelled = 0;
    c.pickShell.subscribe(() => shelled++);

    c.toggle();

    expect(c.open).toBeFalse();
    expect(shelled).toBe(0);
  });

  it('with two or more installed agents, the "+" opens the dropdown and emits only once an entry is chosen', () => {
    const c = new AgentShellPickerComponent();
    c.defaultAgent = 'claude';
    c.installedAgents = [
      { id: 'claude', label: 'Claude' },
      { id: 'codex', label: 'Codex' },
    ];
    const emitted: string[] = [];
    c.pickAgent.subscribe((agent) => emitted.push(agent));
    const event = new Event('click');
    const stopSpy = spyOn(event, 'stopPropagation');

    c.toggle(event);

    expect(stopSpy).toHaveBeenCalled();
    expect(c.open).toBeTrue();
    expect(emitted).toEqual([]);

    c.choose('codex', new Event('click'));

    expect(c.open).toBeFalse();
    expect(emitted).toEqual(['codex']);
  });

  it('picking Shell from the dropdown emits pickShell and closes it', () => {
    const c = new AgentShellPickerComponent();
    c.installedAgents = [{ id: 'claude', label: 'Claude' }];
    let shelled = 0;
    c.pickShell.subscribe(() => shelled++);
    c.toggle();

    c.chooseShell(new Event('click'));

    expect(c.open).toBeFalse();
    expect(shelled).toBe(1);
  });

  it('dismissing the dropdown -- an outside click or Escape -- starts nothing', () => {
    const c = new AgentShellPickerComponent();
    c.installedAgents = [
      { id: 'claude', label: 'Claude' },
      { id: 'codex', label: 'Codex' },
    ];
    let emitted = 0;
    c.pickAgent.subscribe(() => emitted++);

    c.toggle(new Event('click'));
    expect(c.open).toBeTrue();
    c.onDocumentClick();
    expect(c.open).toBeFalse();

    c.toggle(new Event('click'));
    expect(c.open).toBeTrue();
    c.onEscape();
    expect(c.open).toBeFalse();

    // A second click on the button itself closes an open dropdown rather than stacking.
    c.toggle(new Event('click'));
    c.toggle(new Event('click'));
    expect(c.open).toBeFalse();

    expect(emitted).toBe(0);
  });

  it('announces opening and closing so a host can close its own other menu (#886)', () => {
    const c = new AgentShellPickerComponent();
    c.installedAgents = [{ id: 'claude', label: 'Claude' }];
    const changes: boolean[] = [];
    c.openedChange.subscribe((value) => changes.push(value));

    c.toggle();
    c.close();
    c.close(); // already closed -- no duplicate emission

    expect(changes).toEqual([true, false]);
  });

  it('renders one entry per installed agent plus Shell, labelled, and none until the "+" is clicked', () => {
    const fixture = TestBed.createComponent(AgentShellPickerComponent);
    fixture.componentInstance.installedAgents = [
      { id: 'claude', label: 'Claude' },
      { id: 'codex', label: 'Codex' },
    ];
    const emitted: string[] = [];
    fixture.componentInstance.pickAgent.subscribe((agent) => emitted.push(agent));
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;

    expect(root.querySelector('.agent-picker')).toBeNull();

    (root.querySelector('.plus') as HTMLButtonElement).click();
    fixture.detectChanges();

    const options = Array.from(root.querySelectorAll('.agent-option')) as HTMLButtonElement[];
    expect(options.map((option) => option.textContent!.trim())).toEqual(['Claude', 'Codex', 'Shell']);
    expect(emitted).toEqual([]);

    options[1].click();
    fixture.detectChanges();

    expect(emitted).toEqual(['codex']);
    expect(root.querySelector('.agent-picker')).toBeNull();
  });

  it('the dropdown is fixed to the viewport, not clipped in flow by a scrolling ancestor (#886)', () => {
    const fixture = TestBed.createComponent(AgentShellPickerComponent);
    fixture.componentInstance.installedAgents = [{ id: 'claude', label: 'Claude' }];
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;

    (root.querySelector('.plus') as HTMLButtonElement).click();
    fixture.detectChanges();

    const menu = root.querySelector('.agent-picker') as HTMLElement;
    expect(getComputedStyle(menu).position).toBe('fixed');
  });
});
