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

  it('lists open consoles via GET /api/projects/{projectId}/console/sessions', () => {
    const rows = [
      {
        sessionId: '1-console-a1b2c3d4',
        workingDirectory: '/repo',
        createdAt: '2026-08-27T09:00:00Z',
        lastAttachedAt: '2026-08-27T10:00:00Z',
      },
    ];
    service.sessions(1).subscribe((result) => expect(result).toEqual(rows));

    const req = httpMock.expectOne('/api/projects/1/console/sessions');
    expect(req.request.method).toBe('GET');
    req.flush(rows);
  });

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
});
