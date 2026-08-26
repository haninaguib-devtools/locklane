import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router, UrlTree } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { defaultProjectRedirect, routes } from './app.routes';
import { Project } from './models/issue.model';

describe('defaultProjectRedirect', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter(routes)],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function run(): Promise<boolean | UrlTree> {
    return firstValueFrom(TestBed.runInInjectionContext(() => defaultProjectRedirect()));
  }

  it('redirects to the first project when one exists', async () => {
    const projects: Project[] = [
      {
        id: 7,
        name: 'proj',
        gitUrl: 'url',
        workareaPath: '/tmp/proj',
        defaultBranch: 'main',
        status: 'READY',
        createdAt: '',
      },
    ];
    const resultPromise = run();
    httpMock.expectOne('/api/projects').flush(projects);
    const result = await resultPromise;

    expect(result instanceof UrlTree).toBeTrue();
    expect(TestBed.inject(Router).serializeUrl(result as UrlTree)).toBe('/projects/7/issues');
  });

  it('lets the empty route activate when no project exists yet', async () => {
    const resultPromise = run();
    httpMock.expectOne('/api/projects').flush([]);

    expect(await resultPromise).toBeTrue();
  });

  it('lets the empty route activate when the request fails (not logged in yet)', async () => {
    const resultPromise = run();
    httpMock.expectOne('/api/projects').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(await resultPromise).toBeTrue();
  });
});
