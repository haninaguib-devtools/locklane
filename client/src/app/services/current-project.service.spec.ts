import { fakeAsync, TestBed, tick } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { CurrentProjectService } from './current-project.service';
import { Project } from '../models/issue.model';
import { routes } from '../app.routes';

describe('CurrentProjectService', () => {
  let httpMock: HttpTestingController;

  const PROJECT: Project = {
    id: 1,
    name: 'proj',
    gitUrl: 'url',
    workareaPath: '/tmp/proj',
    defaultBranch: 'main',
    accentColor: null,
    template: null,
    status: 'READY',
    createdAt: '',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter(routes)],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function navigateTo(projectId: number): void {
    TestBed.inject(Router).navigateByUrl(`/projects/${projectId}/issues`);
    tick();
  }

  it('exposes the current project, including its accent color, once the fetch resolves', fakeAsync(() => {
    navigateTo(1);
    const service = TestBed.inject(CurrentProjectService);

    httpMock.expectOne('/api/projects').flush([{ ...PROJECT, accentColor: '#c15f3c' }]);

    expect(service.current()).toEqual({ id: 1, name: 'proj', accentColor: '#c15f3c' });
  }));

  it('is null when no project is selected', fakeAsync(() => {
    TestBed.inject(Router).navigateByUrl('/');
    tick();
    const service = TestBed.inject(CurrentProjectService);

    httpMock.expectOne('/api/projects').flush([PROJECT]);

    expect(service.current()).toBeNull();
  }));

  it('refresh() re-fetches the project list and updates current() (#428)', fakeAsync(() => {
    navigateTo(1);
    const service = TestBed.inject(CurrentProjectService);
    httpMock.expectOne('/api/projects').flush([PROJECT]);
    expect(service.current()?.accentColor).toBeNull();

    service.refresh();

    httpMock.expectOne('/api/projects').flush([{ ...PROJECT, accentColor: '#5c8a4e' }]);
    expect(service.current()?.accentColor).toBe('#5c8a4e');
  }));

  it('focusedProjectId is null when the project page is open in the ordinary window, without focus=1 (#449)', fakeAsync(() => {
    navigateTo(1);
    const service = TestBed.inject(CurrentProjectService);
    httpMock.expectOne('/api/projects').flush([PROJECT]);

    expect(service.focusMode()).toBeFalse();
    expect(service.focusedProjectId()).toBeNull();
    // projectId/current -- what the header's own title reads -- stay narrowed
    // regardless of focus mode; only focusedProjectId differs (#449).
    expect(service.projectId()).toBe(1);
  }));

  it('focusedProjectId matches projectId inside a popped-out, focus=1 window (#286, #449)', fakeAsync(() => {
    TestBed.inject(Router).navigateByUrl('/projects/1/issues?focus=1');
    tick();
    const service = TestBed.inject(CurrentProjectService);
    httpMock.expectOne('/api/projects').flush([PROJECT]);

    expect(service.focusMode()).toBeTrue();
    expect(service.focusedProjectId()).toBe(1);
  }));

  it('focusedProjectId is null with no project open at all, focus=1 or not', fakeAsync(() => {
    TestBed.inject(Router).navigateByUrl('/?focus=1');
    tick();
    const service = TestBed.inject(CurrentProjectService);
    httpMock.expectOne('/api/projects').flush([PROJECT]);

    expect(service.focusedProjectId()).toBeNull();
  }));
});
