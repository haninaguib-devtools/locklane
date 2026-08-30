import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ProjectConsoleService } from './project-console.service';

describe('ProjectConsoleService', () => {
  let service: ProjectConsoleService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ProjectConsoleService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('mints a session via POST /api/projects/{projectId}/console', () => {
    service
      .start(1)
      .subscribe((result) =>
        expect(result).toEqual({ sessionId: '1-console-a1b2c3d4', workingDirectory: '/repo' }),
      );

    const req = httpMock.expectOne('/api/projects/1/console');
    expect(req.request.method).toBe('POST');
    req.flush({ sessionId: '1-console-a1b2c3d4', workingDirectory: '/repo' });
  });

  it('closes one console via DELETE /api/projects/{projectId}/console/{sessionId}', () => {
    let completed = false;
    service.close(1, '1-console-a1b2c3d4').subscribe({ complete: () => (completed = true) });

    const req = httpMock.expectOne('/api/projects/1/console/1-console-a1b2c3d4');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
    expect(completed).toBeTrue();
  });

  it('lists the open consoles via GET /api/projects/{projectId}/console/sessions', () => {
    const consoles = [
      {
        sessionId: '1-console-aaaa1111',
        workingDirectory: '/repo',
        createdAt: '2026-08-27T10:00:00Z',
        lastAttachedAt: '2026-08-27T10:05:00Z',
      },
    ];
    service.listOpen(1).subscribe((result) => expect(result).toEqual(consoles));

    const req = httpMock.expectOne('/api/projects/1/console/sessions');
    expect(req.request.method).toBe('GET');
    req.flush(consoles);
  });
  it('lists past conversations via GET /api/projects/{projectId}/console/resume-sessions', () => {
    const sessions = [
      {
        worktreeId: '1-console-aaaa1111',
        tool: 'claude' as const,
        resumeId: '11111111-1111-1111-1111-111111111111',
        capturedAt: '2026-08-27T10:00:00Z',
        title: null,
      },
    ];
    service.resumeSessions(1).subscribe((result) => expect(result).toEqual(sessions));

    const req = httpMock.expectOne('/api/projects/1/console/resume-sessions');
    expect(req.request.method).toBe('GET');
    req.flush(sessions);
  });

  it('reopens a past conversation via POST .../console/resume-sessions/reopen?from=', () => {
    const reopened = { sessionId: '1-console-aaaa1111-resume-99887766', workingDirectory: '/repo-console-aaaa1111' };
    service.reopenSession(1, '1-console-aaaa1111').subscribe((result) => expect(result).toEqual(reopened));

    const req = httpMock.expectOne(
      (request) =>
        request.url === '/api/projects/1/console/resume-sessions/reopen' &&
        request.params.get('from') === '1-console-aaaa1111',
    );
    expect(req.request.method).toBe('POST');
    req.flush(reopened);
  });
  it('names a console tab via PUT .../console/<id>/name, and clears it with an empty name (#393)', () => {
    service.rename(1, '1-console-aaaa1111', 'release notes').subscribe();
    const named = httpMock.expectOne('/api/projects/1/console/1-console-aaaa1111/name');
    expect(named.request.method).toBe('PUT');
    expect(named.request.body).toEqual({ name: 'release notes' });
    named.flush(null);

    service.rename(1, '1-console-aaaa1111', '').subscribe();
    const cleared = httpMock.expectOne('/api/projects/1/console/1-console-aaaa1111/name');
    expect(cleared.request.body).toEqual({ name: '' });
    cleared.flush(null);
  });
});
