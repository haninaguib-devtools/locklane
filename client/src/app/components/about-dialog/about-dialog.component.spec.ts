import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AboutDialogComponent } from './about-dialog.component';
import { RunningVersionService } from '../../services/running-version.service';

describe('AboutDialogComponent (#575)', () => {
  let runningVersion: string | null;

  beforeEach(() => {
    runningVersion = null;
    TestBed.configureTestingModule({
      imports: [AboutDialogComponent],
      providers: [{ provide: RunningVersionService, useValue: { version: () => runningVersion } }],
    });
  });

  function open(): ComponentFixture<AboutDialogComponent> {
    const fixture = TestBed.createComponent(AboutDialogComponent);
    fixture.detectChanges();
    return fixture;
  }

  function text(fixture: ComponentFixture<AboutDialogComponent>, selector: string): string {
    return (fixture.nativeElement as HTMLElement).querySelector(selector)?.textContent?.trim() ?? '';
  }

  it('shows the app name and the running engine version once known', () => {
    runningVersion = '0.1.11-SNAPSHOT';
    const fixture = open();

    expect(text(fixture, '.name')).toBe('LockLane');
    expect(text(fixture, '.version')).toBe('version 0.1.11-SNAPSHOT');
  });

  it('says the version is unknown before the engine has reported one', () => {
    const fixture = open();

    expect(text(fixture, '.version')).toBe('version unknown');
  });

  it('updates in place once the version becomes known while it is open (#595)', () => {
    const fixture = open();
    expect(text(fixture, '.version')).toBe('version unknown');

    runningVersion = '0.1.12';
    fixture.detectChanges();

    expect(text(fixture, '.version')).toBe('version 0.1.12');
  });

  it('closes from the Close button, the backdrop, and Escape', () => {
    const fixture = open();
    const closed = jasmine.createSpy('closed');
    fixture.componentInstance.closed.subscribe(closed);
    const el = fixture.nativeElement as HTMLElement;

    el.querySelector<HTMLButtonElement>('.dialog-actions button')!.click();
    el.querySelector<HTMLElement>('.backdrop')!.click();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));

    expect(closed).toHaveBeenCalledTimes(3);
  });

  it('does not close when the panel itself is clicked', () => {
    const fixture = open();
    const closed = jasmine.createSpy('closed');
    fixture.componentInstance.closed.subscribe(closed);

    (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>('.dialog')!.click();

    expect(closed).not.toHaveBeenCalled();
  });
});
