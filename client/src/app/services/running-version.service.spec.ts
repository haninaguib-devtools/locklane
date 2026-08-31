import { TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';
import { AppEvent, EventsService } from './events.service';
import { RunningVersionService } from './running-version.service';

describe('RunningVersionService', () => {
  let events: Subject<AppEvent>;

  beforeEach(() => {
    events = new Subject<AppEvent>();

    TestBed.configureTestingModule({
      providers: [{ provide: EventsService, useValue: { events$: events.asObservable() } }],
    });
  });

  it('has no version until an engineVersion event carrying one arrives', () => {
    const service = TestBed.inject(RunningVersionService);

    expect(service.version()).toBeNull();
  });

  it('records the release from the engineVersion greeting', () => {
    const service = TestBed.inject(RunningVersionService);

    events.next({ type: 'engineVersion', version: 'stamp', release: '0.1.0-SNAPSHOT' });

    expect(service.version()).toBe('0.1.0-SNAPSHOT');
  });

  it('keeps waiting when the greeting carries no release (an older engine)', () => {
    const service = TestBed.inject(RunningVersionService);

    events.next({ type: 'engineVersion', version: 'stamp' });

    expect(service.version()).toBeNull();
  });

  it('ignores events of other types', () => {
    const service = TestBed.inject(RunningVersionService);

    events.next({ type: 'releaseAvailable', version: '0.2.0' });

    expect(service.version()).toBeNull();
  });
});
