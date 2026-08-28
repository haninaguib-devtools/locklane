import { DOCUMENT } from '@angular/common';
import { TestBed } from '@angular/core/testing';
import { SwUpdate, VersionEvent, VersionReadyEvent } from '@angular/service-worker';
import { Subject } from 'rxjs';
import { AppUpdateService } from './app-update.service';
import { EventsService } from './events.service';

describe('AppUpdateService', () => {
  let versionChanged: Subject<void>;
  let versionUpdates: Subject<VersionEvent>;
  let checkForUpdate: jasmine.Spy;
  let swUpdateStub: { isEnabled: boolean; checkForUpdate: jasmine.Spy; versionUpdates: Subject<VersionEvent> };
  let reload: jasmine.Spy;

  beforeEach(() => {
    versionChanged = new Subject<void>();
    versionUpdates = new Subject<VersionEvent>();
    checkForUpdate = jasmine.createSpy('checkForUpdate').and.returnValue(Promise.resolve(false));
    swUpdateStub = { isEnabled: true, checkForUpdate, versionUpdates };
    reload = jasmine.createSpy('reload');

    TestBed.configureTestingModule({
      providers: [
        { provide: EventsService, useValue: { versionChanged$: versionChanged.asObservable() } },
        { provide: SwUpdate, useValue: swUpdateStub },
        { provide: DOCUMENT, useValue: { location: { reload } } },
      ],
    });
  });

  it('checks for an update when the events channel reports a version change', () => {
    TestBed.inject(AppUpdateService);

    versionChanged.next();

    expect(checkForUpdate).toHaveBeenCalled();
  });

  it('does not check for an update when the service worker is disabled', () => {
    swUpdateStub.isEnabled = false;
    TestBed.inject(AppUpdateService);

    versionChanged.next();

    expect(checkForUpdate).not.toHaveBeenCalled();
  });

  it('shows the update as ready once the service worker reports VERSION_READY', () => {
    const service = TestBed.inject(AppUpdateService);
    expect(service.updateReady()).toBeFalse();

    versionUpdates.next({ type: 'VERSION_READY' } as VersionReadyEvent);

    expect(service.updateReady()).toBeTrue();
  });

  it('ignores version-update events that are not VERSION_READY', () => {
    const service = TestBed.inject(AppUpdateService);

    versionUpdates.next({ type: 'VERSION_DETECTED' } as VersionEvent);

    expect(service.updateReady()).toBeFalse();
  });

  it('reload() reloads the page', () => {
    const service = TestBed.inject(AppUpdateService);

    service.reload();

    expect(reload).toHaveBeenCalled();
  });
});
