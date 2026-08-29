import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { WorktreesService } from './worktrees.service';

describe('WorktreesService', () => {
  let service: WorktreesService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(WorktreesService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lists the project worktrees via GET /api/projects/{projectId}/worktrees', () => {
    const rows = [
      { worktreeId: '1-42-add-widget', issueNumber: 42, workingDirectory: '/work/1-42-add-widget', clean: true, sessionAttached: false },
    ];
    service.list(1).subscribe((result) => expect(result).toEqual(rows));

    const req = httpMock.expectOne('/api/projects/1/worktrees');
    expect(req.request.method).toBe('GET');
    req.flush(rows);
  });

  it('removes a worktree via DELETE /api/projects/{projectId}/worktrees/{worktreeId}', () => {
    let completed = false;
    service.remove(1, '1-42-add-widget').subscribe({ complete: () => (completed = true) });

    const req = httpMock.expectOne('/api/projects/1/worktrees/1-42-add-widget');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
    expect(completed).toBeTrue();
  });

  it('triggers the sweep via POST /api/projects/{projectId}/worktrees/cleanup', () => {
    service.runCleanupNow(1).subscribe((result) => expect(result).toEqual({ removed: ['1-42-add-widget'] }));

    const req = httpMock.expectOne('/api/projects/1/worktrees/cleanup');
    expect(req.request.method).toBe('POST');
    req.flush({ removed: ['1-42-add-widget'] });
  });
});
