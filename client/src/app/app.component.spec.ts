import { fakeAsync, TestBed, tick } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { provideServiceWorker } from '@angular/service-worker';
import { AppComponent } from './app.component';
import { AuthService } from './services/auth.service';
import { SidenavComponent } from './components/sidenav/sidenav.component';
import { ProjectSummaryComponent } from './components/project-summary/project-summary.component';
import { Project } from './models/issue.model';
import { UsageSnapshot } from './models/usage.model';
import { OpenProjectConsole } from './services/project-console.service';
import { routes } from './app.routes';

describe('AppComponent', () => {

  const GITHUB_OK = { failing: false, failure: null, lastSuccessAt: null };
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

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter(routes),
        // The update banner (#273) injects SwUpdate, which -- unlike most Angular
        // services -- isn't providedIn: 'root'; it only exists once
        // provideServiceWorker registers it, the same way app.config.ts does for the
        // real app. Disabled here since there is no real service worker in tests.
        provideServiceWorker('ngsw-worker.js', { enabled: false }),
      ],
    }).compileComponents();
    httpMock = TestBed.inject(HttpTestingController);
  });

  // The usage widget (#137) keeps polling /api/usage on its own timer for as long as
  // it's mounted -- fakeAsync's tick() calls elsewhere in these tests can fast-forward
  // that timer, so a poll this test never asked about may still be outstanding. Drain
  // it here rather than asserting on it in every unrelated test.
  afterEach(() => {
    httpMock.match('/api/usage').forEach((request) => request.flush(EMPTY_USAGE));
    httpMock.verify();
  });

  /**
   * Logs the injected AuthService in synchronously, via a flushed fake response.
   * `role` (#240) drives {@link AuthService.isAdmin} -- omitted, the login response
   * carries no role at all, exactly like every pre-#240 test in this file.
   */
  function logIn(role?: string): void {
    TestBed.inject(AuthService).login('someone', 'password').subscribe();
    httpMock.expectOne('/api/auth/login').flush(role ? { role } : null);
  }

  /**
   * Navigates straight to the project's own summary route -- '/' no longer
   * redirects there (#197; #43's guard is gone), so tests that just want a
   * project selected with no issue reach that state directly, the same way
   * every other direct-URL-load test in this file already does.
   */
  function navigateToProjectSummary(): void {
    TestBed.inject(Router).navigateByUrl('/projects/1/issues');
    tick();
  }

  /**
   * The header's app-console-indicator fetches these per project on every init
   * (#32, #290) -- one call per id in `projectIds`, defaulting to just the
   * fixture PROJECT most tests use. The sidenav also fetches the same consoles
   * list, to drive its own open-console dot (#108), so there are two requests
   * for that endpoint once the sidenav is present.
   */
  function flushConsoleIndicator(projectIds: number[] = [1]): void {
    for (const projectId of projectIds) {
      httpMock.match(`/api/projects/${projectId}/consoles`).forEach((request) => request.flush([]));
      httpMock.expectOne(`/api/projects/${projectId}/issues`).flush([]);
      // #449: the widget also reads each project's open project-console
      // sessions directly, to source a project console's row from the same
      // place the tab strip gets its text.
      httpMock.match(`/api/projects/${projectId}/console/sessions`).forEach((request) => request.flush([]));
    }
  }

  const EMPTY_USAGE: UsageSnapshot = {
    providers: [],
    updatedAt: new Date().toISOString(),
  };

  /** The sidenav's usage widget (#137) fetches once on its own `ngOnInit`, whenever the sidenav mounts. */
  function flushUsageWidget(): void {
    httpMock.expectOne('/api/usage').flush(EMPTY_USAGE);
  }

  /**
   * The sidenav fetches its own project list and each project's tree (#44). Both
   * callers of this helper load an issue directly from the initial route, so
   * MainContentComponent is already mounted in the same change-detection pass and
   * fires its own project-list fetch (#96) alongside the sidenav's -- two requests
   * for the same URL, not one. It also fires the installed-agents fetch on its own
   * `ngOnInit` (#698), same as the project summary's, via `.match()` since a caller
   * further down its own test may already have consumed that one-shot fetch from an
   * earlier mount in the same test.
   */
  function flushSidenav(): void {
    // sidenav + main-content's own project-list fetch + the header's
    // app-console-indicator (#290), all mounted in the same pass.
    const lists = httpMock.match('/api/projects');
    expect(lists.length).toBe(3);
    lists.forEach((request) => request.flush([PROJECT]));
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: [], github: GITHUB_OK });
    flushUsageWidget();
    httpMock
      .match('/api/agents/installed')
      .forEach((request) => request.flush({ installed: [{ id: 'claude', label: 'Claude' }] }));
  }

  /**
   * The same two fetches, when more than one component is asking for them in the
   * same change-detection pass: with no issue selected the project summary (#85)
   * repeats the sidenav's project-list and tree calls to derive its own counts, so
   * `expectOne` would see two of each.
   */
  function flushSidenavAndSummary(): void {
    // sidenav + project-summary/overview's own project-list fetch + the header's
    // app-console-indicator (#290), all mounted in the same pass.
    const lists = httpMock.match('/api/projects');
    expect(lists.length).toBe(3);
    lists.forEach((request) => request.flush([PROJECT]));
    const trees = httpMock.match('/api/projects/1/issues/tree');
    expect(trees.length).toBe(2);
    trees.forEach((request) => request.flush({ nodes: [], github: GITHUB_OK }));
    flushUsageWidget();
    // The project summary's "Open console" button (#221, #695) fetches the installed
    // agents once, on its own `ngOnInit`, whenever the summary (not the overview page,
    // #197) is what mounted.
    httpMock
      .match('/api/agents/installed')
      .forEach((request) => request.flush({ installed: [{ id: 'claude', label: 'Claude' }] }));
  }

  /**
   * The project summary's own console button (#221) fetches the project's open
   * consoles once it learns the project is READY -- only once flushSidenavAndSummary
   * has resolved that project-list fetch. The header's own console indicator now
   * reads the same endpoint for its project-console rows (#449), so this flushes
   * every pending request to it with the same data, whichever of the two (or
   * both) are outstanding at call time.
   */
  function flushProjectConsoleSessions(sessions: OpenProjectConsole[] = []): void {
    httpMock.match('/api/projects/1/console/sessions').forEach((request) => request.flush(sessions));
  }

  /**
   * The project summary's worktree list (#320) fetches this project's worktrees once
   * it learns the project is READY -- the same gate as {@link flushProjectConsoleSessions},
   * so every caller of that flushes this immediately afterward too.
   */
  function flushProjectWorktrees(): void {
    httpMock.expectOne('/api/projects/1/worktrees').flush([]);
  }

  function flushIssue(number: number): void {
    httpMock.expectOne(`/api/projects/1/issues/${number}`).flush({
      number,
      title: 'T',
      state: 'OPEN',
      labels: [],
      body: '',
      createdAt: '',
      updatedAt: '',
    });
    httpMock.expectOne(`/api/projects/1/issues/${number}/detail`).flush({
      number,
      recordPath: null,
      checks: { passing: 0, failing: 0, pending: 0, runs: [] },
      branch: null,
      prNumber: null,
      prState: null,
      prDraft: false,
      flowSteps: [],
    });
    // MainContentComponent's own project-list fetch, to derive the overview tab's
    // GitHub links (#96). Usually a fresh request here, but when the issue was
    // already selected on the initial route, MainContentComponent mounted in the
    // same change-detection pass as the sidenav and flushSidenav() already
    // flushed it alongside the sidenav's own -- so 0 or 1, never asserted at 1.
    httpMock.match('/api/projects').forEach((request) => request.flush([PROJECT]));
    httpMock.expectOne(`/api/projects/1/issues/${number}/resume-sessions`).flush([]);
    httpMock.expectOne(`/api/projects/1/issues/${number}/worktrees`).flush([]);
  }

  it('should create the app', () => {
    const fixture = TestBed.createComponent(AppComponent);
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('shows only the login screen when not authenticated', fakeAsync(() => {
    // Not logged in -- AppComponent's login/shell split is independent of
    // routing, so nothing here ever calls the backend.
    TestBed.inject(Router).navigateByUrl('/');
    tick();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-login')).toBeTruthy();
    expect(compiled.querySelector('.shell')).toBeFalsy();
  }));

  it('renders the overview page at "/" instead of redirecting into the first project (#197)', fakeAsync(() => {
    logIn();
    TestBed.inject(Router).navigateByUrl('/');
    tick();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    // The sidenav and app-overview each fetch the project list and its tree
    // independently (#44, #197) -- the same shape flushSidenavAndSummary()
    // already handles for the sidenav/project-summary pair. The header's
    // app-console-indicator now mounts unconditionally too (#290), independent
    // of whether a project is selected, which '/' never has.
    flushSidenavAndSummary();
    flushConsoleIndicator();
    fixture.detectChanges();

    expect(TestBed.inject(Router).url).toBe('/');
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-overview')).toBeTruthy();
    expect(compiled.querySelector('app-project-summary')).toBeFalsy();
    // No project open in this window (#309) -- the header stays plain.
    expect(compiled.querySelector('.brand')?.textContent?.trim()).toBe('LockLane');
  }));

  it('shows the zero-project empty state at "/" when logged in with no projects (#197, #227)', fakeAsync(() => {
    logIn();
    TestBed.inject(Router).navigateByUrl('/');
    tick();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    // sidenav + app-overview + the header's app-console-indicator (#290), all
    // mounted in the same pass; zero projects means the indicator has nothing
    // further to fetch.
    const lists = httpMock.match('/api/projects');
    expect(lists.length).toBe(3);
    lists.forEach((request) => request.flush([]));
    flushUsageWidget();
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('No projects yet');
  }));

  it('the zero-state CTA opens the same add-project popup as the header, and creating refreshes both the sidenav and the overview (#227)', fakeAsync(() => {
    logIn();
    TestBed.inject(Router).navigateByUrl('/');
    tick();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    // sidenav + app-overview + the header's app-console-indicator (#290), all
    // mounted in the same pass.
    let lists = httpMock.match('/api/projects');
    expect(lists.length).toBe(3);
    lists.forEach((request) => request.flush([]));
    flushUsageWidget();
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-add-project-popup')).toBeFalsy();
    compiled.querySelector<HTMLButtonElement>('.zero-cta')!.click();
    fixture.detectChanges();
    expect(compiled.querySelector('app-add-project-popup')).toBeTruthy();
    // The popup asks for the host's gh accounts (#532) and project templates (#536) on mount.
    httpMock.expectOne('/api/github/accounts').flush({ accounts: [] });
    httpMock.expectOne('/api/templates').flush({ templates: [] });

    fixture.componentInstance.onProjectCreated();
    fixture.detectChanges();

    expect(compiled.querySelector('app-add-project-popup')).toBeFalsy();
    // onProjectCreated() only refreshes the sidenav and the overview (#227) --
    // the indicator's own project list, fetched once at mount, is not among them.
    lists = httpMock.match('/api/projects');
    expect(lists.length).toBe(2);
    lists.forEach((request) => request.flush([PROJECT]));
    // The sidenav's own refresh() now requests fresh=true (#545); the overview's
    // does not -- two requests to the same path, distinguished only by that param.
    const trees = httpMock.match((r) => r.url === '/api/projects/1/issues/tree');
    expect(trees.length).toBe(2);
    trees.forEach((request) => request.flush({ nodes: [], github: GITHUB_OK }));
    httpMock.match('/api/projects/1/consoles').forEach((request) => request.flush([]));
  }));

  it('shows the project summary until an issue is selected (#85)', fakeAsync(() => {
    logIn();
    navigateToProjectSummary();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    flushSidenavAndSummary();
    flushProjectConsoleSessions();
    flushConsoleIndicator();
    fixture.detectChanges();
    flushProjectWorktrees();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-project-summary')).toBeTruthy();
    expect(compiled.querySelector('.empty-state')).toBeFalsy();
    expect(compiled.textContent).toContain('proj');
  }));

  it('shows the project name in the header once a project is open in this window (#309)', fakeAsync(() => {
    logIn();
    navigateToProjectSummary();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    flushSidenavAndSummary();
    flushProjectConsoleSessions();
    flushConsoleIndicator();
    fixture.detectChanges();
    flushProjectWorktrees();

    const compiled = fixture.nativeElement as HTMLElement;
    // The brand link stays plain "LockLane" now -- the project name is its
    // own centered element instead of fused into it (#586).
    expect(compiled.querySelector('.brand')?.textContent?.trim()).toBe('LockLane');
    expect(compiled.querySelector('.project-name')?.textContent?.trim()).toBe('proj');
  }));

  it('picking an accent color from the project summary tints the topbar immediately, the first time in the session (#428, #555)', fakeAsync(() => {
    logIn();
    navigateToProjectSummary();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    flushSidenavAndSummary();
    flushProjectConsoleSessions();
    flushConsoleIndicator();
    fixture.detectChanges();
    flushProjectWorktrees();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector<HTMLElement>('.topbar')!.style.background).toBe('');

    // Sage is the second preset (accent-theme-store.ts).
    compiled.querySelectorAll<HTMLButtonElement>('.accent-swatch')[1].click();
    httpMock.expectOne('/api/projects/1/accent-color').flush(null);
    fixture.detectChanges();

    // CurrentProjectService was already constructed by the header's own
    // `projectName` read, well before this click -- this must still be an
    // explicit re-fetch, not a no-op left over from that earlier construction
    // (the bug this test guards against).
    httpMock.expectOne('/api/projects').flush([{ ...PROJECT, accentColor: '#5c8a4e' }]);
    // The console indicator re-derives from CurrentProjectService's own
    // `projects$` (console-indicator.component.ts), so this refresh also
    // re-fires its consoles/issues fetch -- a real, expected ripple, not
    // specific to this bug fix.
    flushConsoleIndicator();
    fixture.detectChanges();

    // sage (#5c8a4e) blended toward white at the same ~13% ratio.
    expect(compiled.querySelector<HTMLElement>('.topbar')!.style.background).toBe('rgb(234, 240, 232)');
    // The project's own pages never pick up this tint (#555) -- it lives on
    // the header alone.
    expect(compiled.querySelector<HTMLElement>('.project-pages')!.style.background).toBe('');
  }));

  it('leaves the topbar at its default background when the project has no accent color (#428, #555)', fakeAsync(() => {
    const fixture = openedApp();

    const el = (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>('.topbar')!;
    expect(el.style.background).toBe('');
  }));

  it('tints the topbar with a background derived from the accent color once one is set (#428, #555)', fakeAsync(() => {
    const TINTED_PROJECT: Project = { ...PROJECT, accentColor: '#c15f3c' };
    logIn();
    navigateToProjectSummary();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const lists = httpMock.match('/api/projects');
    expect(lists.length).toBe(3);
    lists.forEach((request) => request.flush([TINTED_PROJECT]));
    const trees = httpMock.match('/api/projects/1/issues/tree');
    expect(trees.length).toBe(2);
    trees.forEach((request) => request.flush({ nodes: [], github: GITHUB_OK }));
    flushUsageWidget();
    httpMock
      .match('/api/agents/installed')
      .forEach((request) => request.flush({ installed: [{ id: 'claude', label: 'Claude' }] }));
    flushProjectConsoleSessions();
    flushConsoleIndicator();
    fixture.detectChanges();
    flushProjectWorktrees();

    const compiled = fixture.nativeElement as HTMLElement;
    // terracotta (#c15f3c) blended toward white at the same ~13% ratio
    // AccentThemeStore's own presets use for their `accentSoft` companion.
    expect(compiled.querySelector<HTMLElement>('.topbar')!.style.background).toBe('rgb(247, 234, 230)');
    expect(compiled.querySelector<HTMLElement>('.project-pages')!.style.background).toBe('');
  }));

  it('extends the topbar tint to the issue page, leaving .project-pages at its plain default background (#555)', fakeAsync(() => {
    const TINTED_PROJECT: Project = { ...PROJECT, accentColor: '#c15f3c' };
    logIn();
    TestBed.inject(Router).navigateByUrl('/projects/1/issues/42');
    tick();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();

    const lists = httpMock.match('/api/projects');
    expect(lists.length).toBe(3);
    lists.forEach((request) => request.flush([TINTED_PROJECT]));
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: [], github: GITHUB_OK });
    flushUsageWidget();
    // MainContentComponent's own ngOnInit fetch (#698).
    httpMock.expectOne('/api/agents/installed').flush({ installed: [{ id: 'claude', label: 'Claude' }] });
    flushConsoleIndicator();
    flushIssue(42);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    // Same terracotta blend the project summary page shows for the same accent color.
    expect(compiled.querySelector<HTMLElement>('.topbar')!.style.background).toBe('rgb(247, 234, 230)');
    expect(compiled.querySelector<HTMLElement>('.project-pages')!.style.background).toBe('');
  }));

  it('tints the topbar on the project console page too, now that the full-page carve-out is gone (#555)', fakeAsync(() => {
    const TINTED_PROJECT: Project = { ...PROJECT, accentColor: '#c15f3c' };
    logIn();
    TestBed.inject(Router).navigateByUrl('/projects/1/console');
    tick();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();

    // sidenav + the header's app-console-indicator + the console page's own project
    // read (#537), which decides whether the project is READY before asking for a console.
    const lists = httpMock.match('/api/projects');
    expect(lists.length).toBe(3);
    lists.forEach((request) => request.flush([TINTED_PROJECT]));
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: [], github: GITHUB_OK });
    flushUsageWidget();
    // ProjectConsoleComponent's own ngOnInit fetch (#698).
    httpMock.expectOne('/api/agents/installed').flush({ installed: [{ id: 'claude', label: 'Claude' }] });
    flushConsoleIndicator();
    fixture.detectChanges();
    httpMock.expectOne('/api/projects/1/console').flush({ sessionId: '1-console-a1b2c3d4', workingDirectory: '/tmp/proj' });
    flushConsoleIndicator();
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector<HTMLElement>('.topbar')!.style.background).toBe('rgb(247, 234, 230)');
    expect(compiled.querySelector<HTMLElement>('.project-pages')!.style.background).toBe('');
  }));

  it('deleting a project from its summary page refreshes the sidenav in place (#249)', fakeAsync(() => {
    logIn();
    navigateToProjectSummary();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    flushSidenavAndSummary();
    flushProjectConsoleSessions();
    flushConsoleIndicator();
    fixture.detectChanges();
    flushProjectWorktrees();

    const summary = fixture.debugElement.query(By.directive(ProjectSummaryComponent));
    summary.componentInstance.projectDeleted.emit();

    // The sidenav's own refresh() (already exercised by the project-creation
    // wiring) re-fetches the project list and every project's tree.
    const lists = httpMock.match('/api/projects');
    expect(lists.length).toBe(1);
    lists.forEach((request) => request.flush([]));
  }));

  it('selecting a project navigates to /projects/:projectId/issues and shows its summary (#85)', fakeAsync(() => {
    logIn();
    TestBed.inject(Router).navigateByUrl('/projects/1/issues/7');
    tick();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    flushSidenav();
    flushConsoleIndicator();
    flushIssue(7);

    fixture.componentInstance.selectProject(1);
    tick();
    fixture.detectChanges();

    expect(TestBed.inject(Router).url).toBe('/projects/1/issues');
    httpMock.expectOne('/api/projects').flush([PROJECT]);
    // The installed-agents list was already fetched once, by MainContentComponent's
    // own `ngOnInit` on the initial route (#698, via flushSidenav() above) --
    // ProjectSummaryComponent's own refreshInstalled() call here is a no-op.
    flushProjectConsoleSessions();
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: [], github: GITHUB_OK });
    fixture.detectChanges();
    flushProjectWorktrees();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-project-summary')).toBeTruthy();
    expect(compiled.querySelector('app-main-content')).toBeFalsy();
  }));

  it('tells the sidenav which project is selected only while no issue is (#85)', fakeAsync(() => {
    logIn();
    navigateToProjectSummary();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    flushSidenavAndSummary();
    flushProjectConsoleSessions();
    flushConsoleIndicator();

    const sidenav = fixture.debugElement.query(By.directive(SidenavComponent));
    expect(sidenav.componentInstance.selectedProject).toBe(1);

    // What a sidenav row's routerLink (#170) does on a left-click.
    TestBed.inject(Router).navigateByUrl('/projects/1/issues/42');
    tick();
    fixture.detectChanges();
    flushIssue(42);

    expect(sidenav.componentInstance.selectedProject).toBeNull();
  }));

  it('selecting an issue navigates to /projects/:projectId/issues/:id and shows the main content area', fakeAsync(() => {
    logIn();
    navigateToProjectSummary();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    flushSidenavAndSummary();
    flushProjectConsoleSessions();
    flushConsoleIndicator();

    // What a sidenav row's routerLink (#170) does on a left-click.
    TestBed.inject(Router).navigateByUrl('/projects/1/issues/42');
    tick();
    fixture.detectChanges();

    expect(TestBed.inject(Router).url).toBe('/projects/1/issues/42');
    flushIssue(42);
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-main-content')).toBeTruthy();
    expect(compiled.querySelector('app-project-summary')).toBeFalsy();
  }));

  it('loading /projects/:projectId/console directly shows the project console (#140)', fakeAsync(() => {
    logIn();
    TestBed.inject(Router).navigateByUrl('/projects/1/console');
    tick();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.onProjectConsole()).toBeTrue();
    // sidenav + the header's app-console-indicator (#290) + ProjectConsoleComponent's
    // own project read (#537): it looks the project up before asking for a console.
    const lists = httpMock.match('/api/projects');
    expect(lists.length).toBe(3);
    lists.forEach((request) => request.flush([PROJECT]));
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: [], github: GITHUB_OK });
    flushUsageWidget();
    // ProjectConsoleComponent's own ngOnInit fetch (#698), ahead of the auto-start below.
    httpMock.expectOne('/api/agents/installed').flush({ installed: [{ id: 'claude', label: 'Claude' }] });
    flushConsoleIndicator();
    fixture.detectChanges();
    // #256: no open console auto-starts one with the default agent -- which the
    // header's console indicator and the sidenav both learn about in turn.
    httpMock.expectOne('/api/projects/1/console').flush({ sessionId: '1-console-a1b2c3d4', workingDirectory: '/tmp/proj' });
    flushConsoleIndicator();
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-project-console')).toBeTruthy();
    expect(compiled.querySelector('app-main-content')).toBeFalsy();
    expect(compiled.querySelector('app-project-summary')).toBeFalsy();
  }));

  it('a focus=1 query param hides "+ add project" and restricts the sidenav to just that one project (#286)', fakeAsync(() => {
    const PROJECT2: Project = { ...PROJECT, id: 2, name: 'proj-2' };
    logIn();
    TestBed.inject(Router).navigateByUrl('/projects/1/console?focus=1');
    tick();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();

    // sidenav (restricted to project 1 by focus mode) + the header's
    // app-console-indicator, now narrowed to project 1 too (#309) since the
    // route carries a projectId -- a focus window is always a single-project
    // window, so it only ever fans consoles+issues calls out to project 1. Plus the
    // console page's own project read (#537).
    const lists = httpMock.match('/api/projects');
    expect(lists.length).toBe(3);
    lists.forEach((request) => request.flush([PROJECT, PROJECT2]));
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: [], github: GITHUB_OK });
    flushUsageWidget();
    // ProjectConsoleComponent's own ngOnInit fetch (#698).
    httpMock.expectOne('/api/agents/installed').flush({ installed: [{ id: 'claude', label: 'Claude' }] });
    flushConsoleIndicator();
    fixture.detectChanges();
    httpMock
      .expectOne('/api/projects/1/console')
      .flush({ sessionId: '1-console-a1b2c3d4', workingDirectory: '/tmp/proj' });
    flushConsoleIndicator();
    fixture.detectChanges();

    httpMock.expectNone('/api/projects/2/issues/tree');
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.add-project')).toBeFalsy();
    expect(compiled.textContent).not.toContain('proj-2');
    const sidenav = fixture.debugElement.query(By.directive(SidenavComponent));
    expect(sidenav.componentInstance.focusedProjectId).toBe(1);
  }));

  it('the project summary\'s console button (#221) jumps into an already-open console, and the sidenav still shows the project selected', fakeAsync(() => {
    logIn();
    navigateToProjectSummary();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    flushSidenavAndSummary();
    flushProjectConsoleSessions([
      {
        sessionId: 'proj-1-console-abc',
        workingDirectory: '/tmp/proj',
        createdAt: '2026-08-27T09:00:00Z',
        lastAttachedAt: '2026-08-27T09:00:00Z',
      },
    ]);
    flushConsoleIndicator();
    fixture.detectChanges();
    flushProjectWorktrees();

    const button = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.console-button')!;
    expect(button.textContent?.trim()).toBe('Open consoles');
    button.click();
    tick();
    fixture.detectChanges();
    // The console page reads the project first (#537: it waits for READY and decides
    // whether a seeded console is owed), then re-fetches the same already-open session
    // (#256: an empty list here would auto-start a redundant one).
    httpMock.expectOne('/api/projects').flush([PROJECT]);
    httpMock.expectOne('/api/projects/1/console/sessions').flush([
      {
        sessionId: 'proj-1-console-abc',
        workingDirectory: '/tmp/proj',
        createdAt: '2026-08-27T09:00:00Z',
        lastAttachedAt: '2026-08-27T09:00:00Z',
      },
    ]);
    fixture.detectChanges();

    expect(TestBed.inject(Router).url).toBe('/projects/1/console?session=proj-1-console-abc');
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-project-console')).toBeTruthy();
    const sidenav = fixture.debugElement.query(By.directive(SidenavComponent));
    expect(sidenav.componentInstance.selectedProject).toBe(1);
  }));

  it('loading /projects/:projectId/issues/:id directly selects that project and issue', fakeAsync(() => {
    logIn();
    TestBed.inject(Router).navigateByUrl('/projects/1/issues/7');
    tick();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.selectedProjectId()).toBe(1);
    expect(fixture.componentInstance.selectedIssue()).toBe(7);
    flushSidenav();
    flushConsoleIndicator();
    flushIssue(7);
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-main-content')).toBeTruthy();
  }));

  it('passes the selected issue to the sidenav for highlighting', fakeAsync(() => {
    logIn();
    navigateToProjectSummary();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    flushSidenavAndSummary();
    flushProjectConsoleSessions();
    flushConsoleIndicator();

    // What a sidenav row's routerLink (#170) does on a left-click.
    TestBed.inject(Router).navigateByUrl('/projects/1/issues/42');
    tick();
    fixture.detectChanges();
    flushIssue(42);

    const sidenav = fixture.debugElement.query(By.directive(SidenavComponent));
    expect(sidenav.componentInstance.selected).toEqual({ projectId: 1, issueNumber: 42 });
  }));

  it('returns to the login screen after logging out', fakeAsync(() => {
    logIn();
    navigateToProjectSummary();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    flushSidenavAndSummary();
    flushProjectConsoleSessions();
    flushConsoleIndicator();

    fixture.componentInstance.logout();
    httpMock.expectOne('/api/auth/logout').flush(null);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-login')).toBeTruthy();
    expect(compiled.querySelector('.shell')).toBeFalsy();
  }));

  /**
   * The account menu (#90). `logIn()` signs in as 'someone', so that is the name
   * the menu header shows and 's' the avatar's initial.
   */
  function openedApp(role?: string): ReturnType<typeof TestBed.createComponent<AppComponent>> {
    logIn(role);
    navigateToProjectSummary();
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    flushSidenavAndSummary();
    flushProjectConsoleSessions();
    flushConsoleIndicator();
    fixture.detectChanges();
    flushProjectWorktrees();
    return fixture;
  }

  it('shows the avatar button instead of a flat logout button', fakeAsync(() => {
    const fixture = openedApp();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.logout')).toBeFalsy();
    expect(compiled.querySelector('.avatar')?.textContent?.trim()).toBe('s');
    expect(compiled.querySelector('.account-menu')).toBeFalsy();
  }));

  it('opens the add-project popup from the header button, next to the account menu (#227)', fakeAsync(() => {
    const fixture = openedApp();
    const compiled = fixture.nativeElement as HTMLElement;

    expect(compiled.querySelector('app-add-project-popup')).toBeFalsy();
    compiled.querySelector<HTMLButtonElement>('.add-project')!.click();
    fixture.detectChanges();

    expect(compiled.querySelector('app-add-project-popup')).toBeTruthy();
    // The popup asks for the host's gh accounts (#532) and project templates (#536) on mount.
    httpMock.expectOne('/api/github/accounts').flush({ accounts: [] });
    httpMock.expectOne('/api/templates').flush({ templates: [] });
  }));

  it('creating a project from the header popup closes it and refreshes the sidenav (#227)', fakeAsync(() => {
    const fixture = openedApp();
    fixture.componentInstance.openAddProject();
    fixture.detectChanges();
    // The popup asks for the host's gh accounts (#532) and project templates (#536) on mount.
    httpMock.expectOne('/api/github/accounts').flush({ accounts: [] });
    httpMock.expectOne('/api/templates').flush({ templates: [] });

    fixture.componentInstance.onProjectCreated();
    fixture.detectChanges();

    expect(fixture.componentInstance.showAddProject).toBeFalse();
    httpMock.expectOne('/api/projects').flush([PROJECT]);
    httpMock.expectOne('/api/projects/1/issues/tree?fresh=true').flush({ nodes: [], github: GITHUB_OK });
    httpMock.match('/api/projects/1/consoles').forEach((request) => request.flush([]));
  }));

  it('toggles the account menu from the avatar, showing the username, a separator, Settings, About and Sign out', fakeAsync(() => {
    const fixture = openedApp();
    const compiled = fixture.nativeElement as HTMLElement;

    compiled.querySelector<HTMLButtonElement>('.avatar')!.click();
    fixture.detectChanges();

    expect(compiled.querySelector('.account-identity')?.textContent?.trim()).toBe('someone');
    expect(compiled.querySelector('.account-separator')).toBeTruthy();
    const items = Array.from(compiled.querySelectorAll('.account-item')).map((el) =>
      el.textContent?.trim(),
    );
    expect(items).toEqual(['Settings', 'GitHub accounts', 'About', 'Sign out']);

    compiled.querySelector<HTMLButtonElement>('.avatar')!.click();
    fixture.detectChanges();
    expect(compiled.querySelector('.account-menu')).toBeFalsy();
  }));

  it('opens the About dialog from the account menu and closes the menu (#575)', fakeAsync(() => {
    const fixture = openedApp();
    const compiled = fixture.nativeElement as HTMLElement;

    compiled.querySelector<HTMLButtonElement>('.avatar')!.click();
    fixture.detectChanges();
    const about = Array.from(compiled.querySelectorAll<HTMLButtonElement>('.account-item')).find(
      (el) => el.textContent?.trim() === 'About',
    );
    about!.click();
    fixture.detectChanges();

    expect(compiled.querySelector('app-about-dialog')).toBeTruthy();
    expect(compiled.querySelector('.account-menu')).toBeFalsy();
    expect(compiled.querySelector('app-about-dialog .name')?.textContent?.trim()).toBe('LockLane');
  }));

  it('shows "Manage users" in the account menu only for an admin account (#240)', fakeAsync(() => {
    const fixture = openedApp('ADMIN');
    const compiled = fixture.nativeElement as HTMLElement;

    compiled.querySelector<HTMLButtonElement>('.avatar')!.click();
    fixture.detectChanges();

    const items = Array.from(compiled.querySelectorAll('.account-item')).map((el) =>
      el.textContent?.trim(),
    );
    expect(items).toEqual(['Settings', 'GitHub accounts', 'Manage users', 'About', 'Sign out']);
  }));

  it('opens the admin-users panel from the menu and closes it again (#240)', fakeAsync(() => {
    const fixture = openedApp('ADMIN');
    const compiled = fixture.nativeElement as HTMLElement;

    compiled.querySelector<HTMLButtonElement>('.avatar')!.click();
    fixture.detectChanges();
    compiled.querySelectorAll<HTMLButtonElement>('.account-item')[2].click();
    fixture.detectChanges();

    expect(compiled.querySelector('app-admin-users')).toBeTruthy();
    // Opening the panel closes the menu behind it, same as Settings.
    expect(compiled.querySelector('.account-menu')).toBeFalsy();

    httpMock.expectOne('/api/admin/users').flush([]);
    fixture.detectChanges();

    fixture.componentInstance.closeAdminUsers();
    fixture.detectChanges();
    expect(compiled.querySelector('app-admin-users')).toBeFalsy();
  }));

  it('opens the GitHub accounts panel from the menu and closes it again (#550)', fakeAsync(() => {
    const fixture = openedApp();
    const compiled = fixture.nativeElement as HTMLElement;

    compiled.querySelector<HTMLButtonElement>('.avatar')!.click();
    fixture.detectChanges();
    compiled.querySelectorAll<HTMLButtonElement>('.account-item')[1].click();
    fixture.detectChanges();

    expect(compiled.querySelector('app-github-accounts')).toBeTruthy();
    // Opening the panel closes the menu behind it, same as Settings.
    expect(compiled.querySelector('.account-menu')).toBeFalsy();

    httpMock.expectOne('/api/github/accounts').flush({ accounts: [] });
    fixture.detectChanges();

    fixture.componentInstance.closeGithubAccounts();
    fixture.detectChanges();
    expect(compiled.querySelector('app-github-accounts')).toBeFalsy();
  }));

  it('dismisses the account menu on an outside click', fakeAsync(() => {
    const fixture = openedApp();
    const compiled = fixture.nativeElement as HTMLElement;

    compiled.querySelector<HTMLButtonElement>('.avatar')!.click();
    fixture.detectChanges();
    expect(compiled.querySelector('.account-menu')).toBeTruthy();

    document.body.click();
    fixture.detectChanges();
    expect(compiled.querySelector('.account-menu')).toBeFalsy();
  }));

  it('opens the settings dialog from the menu and closes it again', fakeAsync(() => {
    const fixture = openedApp();
    const compiled = fixture.nativeElement as HTMLElement;

    compiled.querySelector<HTMLButtonElement>('.avatar')!.click();
    fixture.detectChanges();
    compiled.querySelectorAll<HTMLButtonElement>('.account-item')[0].click();
    fixture.detectChanges();

    expect(compiled.querySelector('app-settings-dialog')).toBeTruthy();
    // Opening the dialog closes the menu behind it.
    expect(compiled.querySelector('.account-menu')).toBeFalsy();

    // The installed-agents list was already fetched once, by the project summary
    // that openedApp() mounted first (#695) -- refreshInstalled() here is a no-op.
    httpMock.expectOne('/api/account/2fa/status').flush({ enabled: false });
    fixture.detectChanges();

    compiled.querySelector<HTMLButtonElement>('app-settings-dialog .close')!.click();
    fixture.detectChanges();
    expect(compiled.querySelector('app-settings-dialog')).toBeFalsy();
  }));

  it('signs out from the menu through the existing logout call', fakeAsync(() => {
    const fixture = openedApp();
    const compiled = fixture.nativeElement as HTMLElement;

    compiled.querySelector<HTMLButtonElement>('.avatar')!.click();
    fixture.detectChanges();
    Array.from(compiled.querySelectorAll<HTMLButtonElement>('.account-item'))
      .find((el) => el.textContent?.trim() === 'Sign out')!
      .click();

    httpMock.expectOne('/api/auth/logout').flush(null);
    fixture.detectChanges();

    expect(compiled.querySelector('app-login')).toBeTruthy();
  }));
});
