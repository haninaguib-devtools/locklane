import { TestBed } from '@angular/core/testing';
import { SettingsDialogComponent } from './settings-dialog.component';

describe('SettingsDialogComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [SettingsDialogComponent] }).compileComponents();
  });

  function create(): ReturnType<typeof TestBed.createComponent<SettingsDialogComponent>> {
    const fixture = TestBed.createComponent(SettingsDialogComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('renders a title bar over an empty body', () => {
    const fixture = create();
    const compiled = fixture.nativeElement as HTMLElement;

    expect(compiled.querySelector('.popup-header span')?.textContent?.trim()).toBe('settings');
    expect(compiled.querySelector('.popup-body')?.textContent?.trim()).toBe('');
  });

  it('closes on the close button, on the backdrop, and on Escape', () => {
    const fixture = create();
    const compiled = fixture.nativeElement as HTMLElement;
    let closed = 0;
    fixture.componentInstance.closed.subscribe(() => closed++);

    compiled.querySelector<HTMLButtonElement>('.close')!.click();
    expect(closed).toBe(1);

    compiled.querySelector<HTMLElement>('.backdrop')!.click();
    expect(closed).toBe(2);

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    expect(closed).toBe(3);
  });

  it('does not close when the panel itself is clicked', () => {
    const fixture = create();
    const compiled = fixture.nativeElement as HTMLElement;
    let closed = 0;
    fixture.componentInstance.closed.subscribe(() => closed++);

    compiled.querySelector<HTMLElement>('.popup-body')!.click();

    expect(closed).toBe(0);
  });
});
