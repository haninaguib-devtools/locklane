import { TestBed } from '@angular/core/testing';
import { AgentPickerComponent } from './agent-picker.component';

describe('AgentPickerComponent', () => {
  function init(): ReturnType<typeof TestBed.createComponent<AgentPickerComponent>> {
    TestBed.configureTestingModule({ imports: [AgentPickerComponent] });
    const fixture = TestBed.createComponent(AgentPickerComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('defaults to claude', () => {
    const fixture = init();
    expect(fixture.componentInstance.agent).toBe('claude');
  });

  it('marks the chosen agent and emits it on click', () => {
    const fixture = init();
    let emitted: string | undefined;
    fixture.componentInstance.agentChange.subscribe((agent) => (emitted = agent));

    const compiled = fixture.nativeElement as HTMLElement;
    const buttons = Array.from(compiled.querySelectorAll('button'));
    buttons.find((b) => b.textContent?.trim() === 'codex')!.click();
    fixture.detectChanges();

    expect(emitted).toBe('codex');
    expect(fixture.componentInstance.agent).toBe('codex');
    expect(compiled.querySelector('button.chosen')?.textContent?.trim()).toBe('codex');
  });

  it('offers omp alongside the other agents', () => {
    const fixture = init();
    let emitted: string | undefined;
    fixture.componentInstance.agentChange.subscribe((agent) => (emitted = agent));

    const compiled = fixture.nativeElement as HTMLElement;
    const buttons = Array.from(compiled.querySelectorAll('button'));
    buttons.find((b) => b.textContent?.trim() === 'omp')!.click();
    fixture.detectChanges();

    expect(emitted).toBe('omp');
    expect(compiled.querySelector('button.chosen')?.textContent?.trim()).toBe('omp');
  });
});
