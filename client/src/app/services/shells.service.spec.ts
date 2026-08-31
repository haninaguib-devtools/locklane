import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { OpenShell, ShellsService } from './shells.service';

describe('ShellsService', () => {
  let service: ShellsService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ShellsService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lists every open shell via GET /api/shells', () => {
    const shells: OpenShell[] = [
      {
        sessionId: '1-shell-main-a1b2c3d4',
        projectId: 1,
        issueNumber: null,
        mainCheckout: true,
        workingDirectory: '/repo',
        createdAt: '2026-08-31T10:00:00Z',
        lastAttachedAt: '2026-08-31T10:05:00Z',
        displayName: null,
      },
    ];
    service.list().subscribe((result) => expect(result).toEqual(shells));

    const req = httpMock.expectOne('/api/shells');
    expect(req.request.method).toBe('GET');
    req.flush(shells);
  });

  it('closes one shell via DELETE /api/projects/{projectId}/shells/{sessionId}', () => {
    let completed = false;
    service.close(1, '1-shell-main-a1b2c3d4').subscribe({ complete: () => (completed = true) });

    const req = httpMock.expectOne('/api/projects/1/shells/1-shell-main-a1b2c3d4');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
    expect(completed).toBeTrue();
  });
});
