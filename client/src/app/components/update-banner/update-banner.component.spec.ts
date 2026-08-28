import { WritableSignal, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { UpdateBannerComponent } from './update-banner.component';
import { AppUpdateService } from '../../services/app-update.service';

describe('UpdateBannerComponent', () => {
  let updateReady: WritableSignal<boolean>;
  let reload: jasmine.Spy;

  beforeEach(() => {
    updateReady = signal(false);
    reload = jasmine.createSpy('reload');

    TestBed.configureTestingModule({
      imports: [UpdateBannerComponent],
      providers: [{ provide: AppUpdateService, useValue: { updateReady: updateReady.asReadonly(), reload } }],
    });
  });

  it('shows nothing until an update is ready', () => {
    const fixture = TestBed.createComponent(UpdateBannerComponent);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelector('.update-banner')).toBeFalsy();
  });

  it('shows a reload prompt once an update is ready, and reloading calls the service', () => {
    updateReady.set(true);
    const fixture = TestBed.createComponent(UpdateBannerComponent);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('A new version is available');
    compiled.querySelector<HTMLButtonElement>('.reload')!.click();

    expect(reload).toHaveBeenCalled();
  });
});
