import { WritableSignal, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';
import { ReleaseBannerComponent } from './release-banner.component';
import { ReleaseUpdateService } from '../../services/release-update.service';
import { AppEvent, EventsService } from '../../services/events.service';

describe('ReleaseBannerComponent', () => {
  let version: WritableSignal<string | null>;
  let url: WritableSignal<string | null>;

  beforeEach(() => {
    version = signal<string | null>(null);
    url = signal<string | null>(null);

    TestBed.configureTestingModule({
      imports: [ReleaseBannerComponent],
      providers: [
        {
          provide: ReleaseUpdateService,
          useValue: { version: version.asReadonly(), url: url.asReadonly() },
        },
      ],
    });
  });

  it('shows nothing until a newer release is known', () => {
    const fixture = TestBed.createComponent(ReleaseBannerComponent);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelector('.release-banner')).toBeFalsy();
  });

  it('shows the newer version as plain text when no release url is known', () => {
    version.set('0.2.0');
    const fixture = TestBed.createComponent(ReleaseBannerComponent);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('0.2.0');
    expect(compiled.querySelector('button')).toBeFalsy();
    expect(compiled.querySelector('a')).toBeFalsy();
  });

  it('links the banner text to the release notes, opening in a new tab', () => {
    version.set('0.2.0');
    url.set('https://github.com/o/r/releases/tag/v0.2.0');
    const fixture = TestBed.createComponent(ReleaseBannerComponent);
    fixture.detectChanges();

    const link = (fixture.nativeElement as HTMLElement).querySelector('a');
    expect(link).toBeTruthy();
    expect(link!.getAttribute('href')).toBe('https://github.com/o/r/releases/tag/v0.2.0');
    expect(link!.getAttribute('target')).toBe('_blank');
    expect(link!.getAttribute('rel')).toBe('noopener noreferrer');
    expect(link!.textContent).toContain('0.2.0');
  });
});

describe('ReleaseBannerComponent with the real ReleaseUpdateService', () => {
  it('renders the href carried by a releaseAvailable event', () => {
    // The done-when end to end on the client side (#466): one releaseAvailable event
    // off the events stream, exactly the payload the engine sends on connect (the
    // late-joiner replay) and on broadcast, becomes the banner link's href.
    const events = new Subject<AppEvent>();
    TestBed.configureTestingModule({
      imports: [ReleaseBannerComponent],
      providers: [{ provide: EventsService, useValue: { events$: events.asObservable() } }],
    });
    const fixture = TestBed.createComponent(ReleaseBannerComponent);
    fixture.detectChanges();

    events.next({
      type: 'releaseAvailable',
      version: '0.2.0',
      url: 'https://github.com/o/r/releases/tag/v0.2.0',
    });
    fixture.detectChanges();

    const link = (fixture.nativeElement as HTMLElement).querySelector('a');
    expect(link).toBeTruthy();
    expect(link!.getAttribute('href')).toBe('https://github.com/o/r/releases/tag/v0.2.0');
    expect(link!.getAttribute('target')).toBe('_blank');
  });
});
