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
        accentColor: null,
        template: null,
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
      accentColor: null,
      template: null,
      status: 'CLONING',
      createdAt: '',
    };
    service.create('url', 'bar').subscribe((result) => expect(result).toEqual(project));

    const req = httpMock.expectOne('/api/projects');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ gitUrl: 'url', name: 'bar' });
    req.flush(project);
  });

  it('creates a project as a chosen gh account by adding githubAccountId to the body (#550)', () => {
    service.create('url', 'bar', 7).subscribe();

    const req = httpMock.expectOne('/api/projects');
    expect(req.request.body).toEqual({ gitUrl: 'url', name: 'bar', githubAccountId: 7 });
    req.flush({});
  });

  it('creates a new repository via POST /api/projects/new, with githubAccountId only when one was chosen (#550)', () => {
    service.createNew('my-org', 'my-project', true).subscribe();
    const plain = httpMock.expectOne('/api/projects/new');
    expect(plain.request.body).toEqual({ org: 'my-org', name: 'my-project', bootstrapTWorkflow: true });
    plain.flush({});

    service.createNew('my-org', 'my-project', false, 7).subscribe();
    const asAccount = httpMock.expectOne('/api/projects/new');
    expect(asAccount.request.body).toEqual({
      org: 'my-org',
      name: 'my-project',
      bootstrapTWorkflow: false,
      githubAccountId: 7,
    });
    asAccount.flush({});
  });

  it('creates a new repository from a template by adding template to the body (#536)', () => {
    service.createNew('my-org', 'my-project', false, undefined, 'springboot-angular').subscribe();
    const templated = httpMock.expectOne('/api/projects/new');
    expect(templated.request.body).toEqual({
      org: 'my-org',
      name: 'my-project',
      bootstrapTWorkflow: false,
      template: 'springboot-angular',
    });
    templated.flush({});

    service.createNew('my-org', 'my-project', true, 7, 'springboot-angular').subscribe();
    const both = httpMock.expectOne('/api/projects/new');
    expect(both.request.body).toEqual({
      org: 'my-org',
      name: 'my-project',
      bootstrapTWorkflow: true,
      githubAccountId: 7,
      template: 'springboot-angular',
    });
    both.flush({});
  });

  it('lists the host project templates from GET /api/templates (#536)', () => {
    let result: unknown;
    service.templates().subscribe((templates) => (result = templates));

    const req = httpMock.expectOne('/api/templates');
    expect(req.request.method).toBe('GET');
    req.flush({
      templates: [{ name: 'springboot-angular', title: 'Spring Boot + Angular', description: 'One jar' }],
    });

    expect(result).toEqual([{ name: 'springboot-angular', title: 'Spring Boot + Angular', description: 'One jar' }]);
  });

  it('lists the callers GitHub accounts from GET /api/github/accounts (#550)', () => {
    let result: unknown;
    service.githubAccounts().subscribe((accounts) => (result = accounts));

    const req = httpMock.expectOne('/api/github/accounts');
    expect(req.request.method).toBe('GET');
    req.flush({
      accounts: [
        { id: 1, login: 'haninaguib', scopes: ['repo', 'workflow'], hasWorkflowScope: true, createdAt: '2026-08-01T00:00:00Z' },
        { id: 2, login: 'hani-thyme', scopes: ['repo'], hasWorkflowScope: false, createdAt: '2026-08-02T00:00:00Z' },
      ],
    });

    expect(result).toEqual([
      { id: 1, login: 'haninaguib', scopes: ['repo', 'workflow'], hasWorkflowScope: true, createdAt: '2026-08-01T00:00:00Z' },
      { id: 2, login: 'hani-thyme', scopes: ['repo'], hasWorkflowScope: false, createdAt: '2026-08-02T00:00:00Z' },
    ]);
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

  it('sets a project accent color via PUT /api/projects/{id}/accent-color', () => {
    service.setAccentColor(1, '#c15f3c').subscribe();

    const req = httpMock.expectOne('/api/projects/1/accent-color');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ accentColor: '#c15f3c' });
    req.flush(null);
  });

  it('persists a new project order via PUT /api/projects/order (#541)', () => {
    service.setOrder([2, 1, 3]).subscribe();

    const req = httpMock.expectOne('/api/projects/order');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ orderedIds: [2, 1, 3] });
    req.flush(null);
  });
});
