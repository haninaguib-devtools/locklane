import { fakeAsync, TestBed, tick } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { CurrentProjectService, FocusPreservingRouter } from './current-project.service';
import { EventsService } from './events.service';
import { Project } from '../models/issue.model';
import { routes } from '../app.routes';

// Session ids ("<projectId>-console[-<hex>]"), "<repo>-console-<hex>" worktree directories, the
// /console and /consoles REST paths and the 'console' route segment below keep their persisted and
// on-the-wire shape: compatibility surfaces kept under ADR-112 (#766 renamed only the identifiers).

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
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter(routes),
        // The app's own Router (#803), so every test here runs under it: the ones
        // above it prove an ordinary window is unchanged by it.
        { provide: Router, useClass: FocusPreservingRouter },
      ],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function navigateTo(projectId: number): void {
    TestBed.inject(Router).navigateByUrl(`/projects/${projectId}/issues`);
    tick();
  }

  /** Reaches past EventsService's public API -- there is no other way to fake an incoming socket message. */
  function emitAppEvent(event: unknown): void {
    (TestBed.inject(EventsService) as unknown as { eventsSubject: { next: (e: unknown) => void } }).eventsSubject.next(
      event,
    );
  }

  /** Fires EventsService.reconnected$ the way a reconnect does -- it has no public emitter. */
  function emitReconnect(): void {
    (
      TestBed.inject(EventsService) as unknown as { reconnectedSubject: { next: () => void } }
    ).reconnectedSubject.next();
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

  it('a projectDeleted event drops that project without a reload (#885)', fakeAsync(() => {
    navigateTo(1);
    const service = TestBed.inject(CurrentProjectService);
    const other: Project = { ...PROJECT, id: 2, name: 'other', gitUrl: 'url-2', workareaPath: '/tmp/other' };
    httpMock.expectOne('/api/projects').flush([PROJECT, other]);
    expect(service.projects().map((p) => p.id)).toEqual([1, 2]);

    emitAppEvent({ type: 'projectDeleted', projectId: 2 });

    httpMock.expectOne('/api/projects').flush([PROJECT]);
    expect(service.projects().map((p) => p.id)).toEqual([1]);
  }));

  it('a projectCreated event re-fetches so the new project appears without a reload (#885)', fakeAsync(() => {
    navigateTo(1);
    const service = TestBed.inject(CurrentProjectService);
    httpMock.expectOne('/api/projects').flush([PROJECT]);
    expect(service.projects().map((p) => p.id)).toEqual([1]);

    emitAppEvent({ type: 'projectCreated', projectId: 2 });

    const other: Project = { ...PROJECT, id: 2, name: 'other', gitUrl: 'url-2', workareaPath: '/tmp/other' };
    httpMock.expectOne('/api/projects').flush([PROJECT, other]);
    expect(service.projects().map((p) => p.id)).toEqual([1, 2]);
  }));

  it('a reconnect re-fetches the project list (#885)', fakeAsync(() => {
    navigateTo(1);
    const service = TestBed.inject(CurrentProjectService);
    httpMock.expectOne('/api/projects').flush([PROJECT]);

    emitReconnect();

    httpMock.expectOne('/api/projects').flush([PROJECT]);
    expect(service.projects().map((p) => p.id)).toEqual([1]);
  }));

  it('a failed project list refresh keeps the last good value (#885)', fakeAsync(() => {
    navigateTo(1);
    const service = TestBed.inject(CurrentProjectService);
    httpMock.expectOne('/api/projects').flush([PROJECT]);
    expect(service.projects().map((p) => p.id)).toEqual([1]);

    emitAppEvent({ type: 'projectDeleted', projectId: 2 });

    httpMock.expectOne('/api/projects').flush('gone', { status: 500, statusText: 'Server Error' });
    expect(service.projects().map((p) => p.id)).toEqual([1]);
  }));

  describe('FocusPreservingRouter (#803)', () => {
    it('carries focus=1 onto every in-app navigation started from a focused URL', fakeAsync(() => {
      const router = TestBed.inject(Router);
      router.navigateByUrl('/projects/1/issues?focus=1');
      tick();

      // An issue row's routerLink -- the href it renders and the click it makes.
      router.navigate(['/projects', 1, 'issues', 7]);
      tick();
      expect(router.url).toBe('/projects/1/issues/7?focus=1');
      expect(router.serializeUrl(router.createUrlTree(['/projects', 1, 'issues', 8]))).toBe(
        '/projects/1/issues/8?focus=1',
      );

      // The sidenav's "+" (#370): its own param rides alongside, never instead.
      router.navigate(['/projects', 1, 'console'], { queryParams: { new: 1 } });
      tick();
      expect(router.url).toBe('/projects/1/console?new=1&focus=1');

      // Back to the project page from the agent session (#265), and the window is still focused.
      router.navigate(['/projects', 1, 'issues']);
      tick();
      expect(router.url).toBe('/projects/1/issues?focus=1');

      const service = TestBed.inject(CurrentProjectService);
      httpMock.expectOne('/api/projects').flush([PROJECT]);
      expect(service.focusMode()).toBeTrue();
      expect(service.focusedProjectId()).toBe(1);
    }));

    it('never adds focus to an ordinary window, and lets a caller drop it deliberately', fakeAsync(() => {
      const router = TestBed.inject(Router);
      router.navigateByUrl('/projects/1/issues');
      tick();

      router.navigate(['/projects', 1, 'issues', 7]);
      tick();
      expect(router.url).toBe('/projects/1/issues/7');
      router.navigate(['/projects', 1, 'console'], { queryParams: { new: 1 } });
      tick();
      expect(router.url).toBe('/projects/1/console?new=1');

      // A caller that names `focus` itself wins -- null drops it, the way any
      // query param is dropped.
      router.navigateByUrl('/projects/1/issues?focus=1');
      tick();
      router.navigate(['/projects', 1, 'issues'], { queryParams: { focus: null } });
      tick();
      expect(router.url).toBe('/projects/1/issues');
    }));
  });
});
