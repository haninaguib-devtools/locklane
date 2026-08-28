import { TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';
import { AppEvent, EventsService } from './events.service';
import { ReleaseUpdateService } from './release-update.service';

describe('ReleaseUpdateService', () => {
  let events: Subject<AppEvent>;

  beforeEach(() => {
    events = new Subject<AppEvent>();

    TestBed.configureTestingModule({
      providers: [{ provide: EventsService, useValue: { events$: events.asObservable() } }],
    });
  });

  it('has no version until a releaseAvailable event arrives', () => {
    const service = TestBed.inject(ReleaseUpdateService);

    expect(service.version()).toBeNull();
  });

  it('records the version from a releaseAvailable event', () => {
    const service = TestBed.inject(ReleaseUpdateService);

    events.next({ type: 'releaseAvailable', version: '0.2.0' });

    expect(service.version()).toBe('0.2.0');
  });

  it('ignores events of other types', () => {
    const service = TestBed.inject(ReleaseUpdateService);

    events.next({ type: 'engineVersion', version: 'stamp' });

    expect(service.version()).toBeNull();
  });

  it('keeps the newest version seen when a later event reports a further release', () => {
    const service = TestBed.inject(ReleaseUpdateService);
    events.next({ type: 'releaseAvailable', version: '0.2.0' });

    events.next({ type: 'releaseAvailable', version: '0.3.0' });

    expect(service.version()).toBe('0.3.0');
  });
});
