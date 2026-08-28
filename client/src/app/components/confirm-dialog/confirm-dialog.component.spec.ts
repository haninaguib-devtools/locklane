import { TestBed } from '@angular/core/testing';
import { ConfirmDialogComponent } from './confirm-dialog.component';

describe('ConfirmDialogComponent', () => {
  function init(overrides: Partial<ConfirmDialogComponent> = {}) {
    const fixture = TestBed.createComponent(ConfirmDialogComponent);
    fixture.componentInstance.message = 'Delete this project? This cannot be undone.';
    Object.assign(fixture.componentInstance, overrides);
    fixture.detectChanges();
    return fixture;
  }

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [ConfirmDialogComponent] });
  });

  it('renders the title and message', () => {
    const fixture = init({ title: 'Delete project?' });
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Delete project?');
    expect(text).toContain('Delete this project? This cannot be undone.');
  });

  it('emits confirmed when the confirm button is clicked', () => {
    const fixture = init({ confirmLabel: 'Delete' });
    let confirmed = false;
    fixture.componentInstance.confirmed.subscribe(() => (confirmed = true));

    const buttons = (fixture.nativeElement as HTMLElement).querySelectorAll('.dialog-actions button');
    const confirmButton = Array.from(buttons).find((b) => b.textContent?.trim() === 'Delete') as HTMLButtonElement;
    confirmButton.click();

    expect(confirmed).toBeTrue();
  });

  it('emits cancelled when the cancel button is clicked', () => {
    const fixture = init();
    let cancelled = false;
    fixture.componentInstance.cancelled.subscribe(() => (cancelled = true));

    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.secondary')!.click();

    expect(cancelled).toBeTrue();
  });

  it('emits cancelled when the backdrop is clicked', () => {
    const fixture = init();
    let cancelled = false;
    fixture.componentInstance.cancelled.subscribe(() => (cancelled = true));

    (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>('.backdrop')!.click();

    expect(cancelled).toBeTrue();
  });

  it('does not emit cancelled when the dialog panel itself is clicked', () => {
    const fixture = init();
    let cancelled = false;
    fixture.componentInstance.cancelled.subscribe(() => (cancelled = true));

    (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>('.dialog')!.click();

    expect(cancelled).toBeFalse();
  });

  it('emits cancelled on Escape', () => {
    const fixture = init();
    let cancelled = false;
    fixture.componentInstance.cancelled.subscribe(() => (cancelled = true));

    fixture.componentInstance.onEscape();

    expect(cancelled).toBeTrue();
  });

  it('applies the danger style to the confirm button when danger is set', () => {
    const fixture = init({ danger: true, confirmLabel: 'Delete' });

    const buttons = (fixture.nativeElement as HTMLElement).querySelectorAll('.dialog-actions button');
    const confirmButton = Array.from(buttons).find((b) => b.textContent?.trim() === 'Delete') as HTMLButtonElement;

    expect(confirmButton.classList).toContain('danger');
  });
});
