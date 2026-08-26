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
});
