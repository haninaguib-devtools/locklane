import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ConsolesService } from './consoles.service';
import { AppEvent, EventsService } from './events.service';

describe('ConsolesService', () => {
  let service: ConsolesService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ConsolesService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  /** Reaches past EventsService's public API (#129) -- there is no other way to fake an incoming socket message. */
  function emitAppEvent(event: unknown): void {
    (TestBed.inject(EventsService) as unknown as { eventsSubject: { next: (e: unknown) => void } }).eventsSubject.next(
      event,
    );
  }

  function emitReconnect(): void {
    (TestBed.inject(EventsService) as unknown as { reconnectedSubject: { next: () => void } }).reconnectedSubject.next();
  }

  it('lists open consoles from GET /api/projects/{projectId}/consoles', () => {
    service.list(1).subscribe((result) => expect(result).toEqual(['1-7-main-a1b2c3d4', '1-8-slug']));

    const req = httpMock.expectOne('/api/projects/1/consoles');
    expect(req.request.method).toBe('GET');
    req.flush(['1-7-main-a1b2c3d4', '1-8-slug']);
  });

  it('notifies onClosed subscribers when a console is closed', () => {
    let notified = false;
    service.onClosed.subscribe(() => (notified = true));

    service.notifyClosed();

    expect(notified).toBeTrue();
  });

  it('notifies onOpened and onClosed subscribers when a consolesChanged event arrives remotely (#195)', () => {
    let openedCount = 0;
    let closedCount = 0;
    service.onOpened.subscribe(() => openedCount++);
    service.onClosed.subscribe(() => closedCount++);

    emitAppEvent({ type: 'consolesChanged', projectId: 1 } satisfies AppEvent);

    expect(openedCount).toBe(1);
    expect(closedCount).toBe(1);
  });

  it('ignores an unrelated event type off the events channel', () => {
    let notified = false;
    service.onOpened.subscribe(() => (notified = true));

    emitAppEvent({ type: 'consoleAttention', sessionId: '1-7-slug', state: 'waiting' } satisfies AppEvent);

    expect(notified).toBeFalse();
  });

  it('notifies subscribers on a reconnect, to catch up on anything missed while the socket was down', () => {
    let openedCount = 0;
    service.onOpened.subscribe(() => openedCount++);

    emitReconnect();

    expect(openedCount).toBe(1);
  });
});
