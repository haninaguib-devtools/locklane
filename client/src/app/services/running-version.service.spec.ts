import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { EngineVersionEvent, EventsService } from './events.service';
import { RunningVersionService } from './running-version.service';

describe('RunningVersionService', () => {
  let engineVersion: ReturnType<typeof signal<EngineVersionEvent | null>>;

  beforeEach(() => {
    engineVersion = signal<EngineVersionEvent | null>(null);

    TestBed.configureTestingModule({
      providers: [{ provide: EventsService, useValue: { engineVersion: engineVersion.asReadonly() } }],
    });
  });

  it('has no version until an engineVersion greeting carrying one has arrived', () => {
    const service = TestBed.inject(RunningVersionService);

    expect(service.version()).toBeNull();
  });

  it('reports a greeting that arrived before the service was first injected (#595)', () => {
    // The About dialog is the only consumer and is created lazily, long after the
    // socket's greeting -- the regression #575 introduced.
    engineVersion.set({ type: 'engineVersion', version: 'stamp', release: '0.1.11' });

    const service = TestBed.inject(RunningVersionService);

    expect(service.version()).toBe('0.1.11');
  });

  it("follows a later greeting's release, as after an engine upgrade mid-session (#595)", () => {
    const service = TestBed.inject(RunningVersionService);
    engineVersion.set({ type: 'engineVersion', version: 'a', release: '0.1.11' });
    expect(service.version()).toBe('0.1.11');

    engineVersion.set({ type: 'engineVersion', version: 'b', release: '0.1.12' });

    expect(service.version()).toBe('0.1.12');
  });

  it('stays unknown when the greeting carries no release (an older engine)', () => {
    engineVersion.set({ type: 'engineVersion', version: 'stamp' });

    const service = TestBed.inject(RunningVersionService);

    expect(service.version()).toBeNull();
  });
});
