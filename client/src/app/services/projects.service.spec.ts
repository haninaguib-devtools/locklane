import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ProjectsService } from './projects.service';
import { Project } from '../models/issue.model';

describe('ProjectsService', () => {
  let service: ProjectsService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ProjectsService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lists projects from GET /api/projects', () => {
    const projects: Project[] = [
      {
        id: 1,
        name: 'proj',
        gitUrl: 'url',
        workareaPath: '/tmp/proj',
        defaultBranch: 'main',
        status: 'READY',
        createdAt: '',
      },
    ];
    service.list().subscribe((result) => expect(result).toEqual(projects));

    const req = httpMock.expectOne('/api/projects');
    expect(req.request.method).toBe('GET');
    req.flush(projects);
  });

  it('creates a project via POST /api/projects', () => {
    const project: Project = {
      id: 1,
      name: 'bar',
      gitUrl: 'url',
      workareaPath: '/tmp/bar',
      defaultBranch: null,
      status: 'CLONING',
      createdAt: '',
    };
    service.create('url', 'bar').subscribe((result) => expect(result).toEqual(project));

    const req = httpMock.expectOne('/api/projects');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ gitUrl: 'url', name: 'bar' });
    req.flush(project);
  });

  it('retries a failed project via POST /api/projects/{id}/retry', () => {
    service.retry(1).subscribe();

    const req = httpMock.expectOne('/api/projects/1/retry');
    expect(req.request.method).toBe('POST');
    req.flush({});
  });

  it('deletes a project via DELETE /api/projects/{id}', () => {
    service.delete(1).subscribe();

    const req = httpMock.expectOne('/api/projects/1');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });
});
