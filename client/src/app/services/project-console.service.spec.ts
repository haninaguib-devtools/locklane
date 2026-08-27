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

  it('finds an existing session via GET /api/projects/{projectId}/console', () => {
    service
      .find(1)
      .subscribe((result) => expect(result).toEqual({ sessionId: '1-console', workingDirectory: '/repo' }));

    const req = httpMock.expectOne('/api/projects/1/console');
    expect(req.request.method).toBe('GET');
    req.flush({ sessionId: '1-console', workingDirectory: '/repo' });
  });

  it('mints a session via POST /api/projects/{projectId}/console', () => {
    service
      .start(1)
      .subscribe((result) => expect(result).toEqual({ sessionId: '1-console', workingDirectory: '/repo' }));

    const req = httpMock.expectOne('/api/projects/1/console');
    expect(req.request.method).toBe('POST');
    req.flush({ sessionId: '1-console', workingDirectory: '/repo' });
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
});
