import { WritableSignal, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ReleaseBannerComponent } from './release-banner.component';
import { ReleaseUpdateService } from '../../services/release-update.service';

describe('ReleaseBannerComponent', () => {
  let version: WritableSignal<string | null>;

  beforeEach(() => {
    version = signal<string | null>(null);

    TestBed.configureTestingModule({
      imports: [ReleaseBannerComponent],
      providers: [{ provide: ReleaseUpdateService, useValue: { version: version.asReadonly() } }],
    });
  });

  it('shows nothing until a newer release is known', () => {
    const fixture = TestBed.createComponent(ReleaseBannerComponent);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelector('.release-banner')).toBeFalsy();
  });

  it('shows the newer version once known, with no action offered', () => {
    version.set('0.2.0');
    const fixture = TestBed.createComponent(ReleaseBannerComponent);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('0.2.0');
    expect(compiled.querySelector('button')).toBeFalsy();
  });
});
