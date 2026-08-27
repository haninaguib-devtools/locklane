import { fakeAsync, TestBed, tick } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { AppComponent } from './app.component';
import { AuthService } from './services/auth.service';
import { SidenavComponent } from './components/sidenav/sidenav.component';
import { Project } from './models/issue.model';
import { UsageSnapshot } from './models/usage.model';
import { routes } from './app.routes';

describe('AppComponent', () => {
  let httpMock: HttpTestingController;

  const PROJECT: Project = {
    id: 1,
    name: 'proj',
    gitUrl: 'url',
    workareaPath: '/tmp/proj',
    defaultBranch: 'main',
    status: 'READY',
    createdAt: '',
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter(routes)],
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

  /** Logs the injected AuthService in synchronously, via a flushed fake response. */
  function logIn(): void {
    TestBed.inject(AuthService).login('someone', 'password').subscribe();
    httpMock.expectOne('/api/auth/login').flush(null);
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
   * The header's app-console-indicator fetches these on every init (#32). The
   * sidenav also fetches the same consoles list, to drive its own open-console
   * dot (#108), so there are two requests for it once the sidenav is present.
   */
  function flushConsoleIndicator(): void {
    httpMock.match('/api/projects/1/consoles').forEach((request) => request.flush([]));
    httpMock.expectOne('/api/projects/1/issues').flush([]);
  }

  const EMPTY_USAGE: UsageSnapshot = {
    claude: { available: false, fiveHour: null, weekly: null },
    codex: { available: false, fiveHour: null, weekly: null },
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
   * for the same URL, not one.
   */
  function flushSidenav(): void {
    const lists = httpMock.match('/api/projects');
    expect(lists.length).toBe(2);
    lists.forEach((request) => request.flush([PROJECT]));
    httpMock.expectOne('/api/projects/1/issues/tree').flush([]);
    flushUsageWidget();
  }

  /**
   * The same two fetches, when more than one component is asking for them in the
   * same change-detection pass: with no issue selected the project summary (#85)
   * repeats the sidenav's project-list and tree calls to derive its own counts, so
   * `expectOne` would see two of each.
   */
  function flushSidenavAndSummary(): void {
    const lists = httpMock.match('/api/projects');
    expect(lists.length).toBe(2);
    lists.forEach((request) => request.flush([PROJECT]));
    const trees = httpMock.match('/api/projects/1/issues/tree');
    expect(trees.length).toBe(2);
    trees.forEach((request) => request.flush([]));
    flushUsageWidget();
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
      checks: { passing: 0, failing: 0, pending: 0 },
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
    // already handles for the sidenav/project-summary pair. No console
    // indicator here: it only mounts once a project is selected, which '/'
    // never is.
    flushSidenavAndSummary();
    httpMock.match('/api/projects/1/consoles').forEach((request) => request.flush([]));
    fixture.detectChanges();

    expect(TestBed.inject(Router).url).toBe('/');
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-overview')).toBeTruthy();
    expect(compiled.querySelector('app-project-summary')).toBeFalsy();
  }));

  it('shows the "select a project" empty state at "/" when logged in with no projects (#197)', fakeAsync(() => {
    logIn();
    TestBed.inject(Router).navigateByUrl('/');
    tick();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const lists = httpMock.match('/api/projects');
    expect(lists.length).toBe(2);
    lists.forEach((request) => request.flush([]));
    flushUsageWidget();
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('select a project to begin');
  }));

  it('shows the project summary until an issue is selected (#85)', fakeAsync(() => {
    logIn();
    navigateToProjectSummary();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    flushSidenavAndSummary();
    flushConsoleIndicator();
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-project-summary')).toBeTruthy();
    expect(compiled.querySelector('.empty-state')).toBeFalsy();
    expect(compiled.textContent).toContain('proj');
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
    httpMock.expectOne('/api/projects/1/issues/tree').flush([]);
    fixture.detectChanges();

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
    httpMock.expectOne('/api/projects').flush([PROJECT]);
    httpMock.expectOne('/api/projects/1/issues/tree').flush([]);
    flushUsageWidget();
    flushConsoleIndicator();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([]);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-project-console')).toBeTruthy();
    expect(compiled.querySelector('app-main-content')).toBeFalsy();
    expect(compiled.querySelector('app-project-summary')).toBeFalsy();
  }));

  it('loading /projects/:projectId/consoles directly shows the consoles page (#179)', fakeAsync(() => {
    logIn();
    TestBed.inject(Router).navigateByUrl('/projects/1/consoles');
    tick();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.onConsolesPage()).toBeTrue();
    expect(fixture.componentInstance.onProjectConsole()).toBeFalse();
    httpMock.expectOne('/api/projects').flush([PROJECT]);
    httpMock.expectOne('/api/projects/1/issues/tree').flush([]);
    flushUsageWidget();
    flushConsoleIndicator();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([]);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-consoles-page')).toBeTruthy();
    expect(compiled.querySelector('app-project-console')).toBeFalsy();
    expect(compiled.querySelector('app-main-content')).toBeFalsy();
    expect(compiled.querySelector('app-project-summary')).toBeFalsy();
  }));

  it('the project summary\'s consoles link (#180) navigates to the consoles page, which the sidenav shows as still on that project', fakeAsync(() => {
    logIn();
    navigateToProjectSummary();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    flushSidenavAndSummary();
    flushConsoleIndicator();
    fixture.detectChanges();

    (fixture.nativeElement as HTMLElement).querySelector<HTMLAnchorElement>('.consoles-link')!.click();
    tick();
    fixture.detectChanges();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([]);
    fixture.detectChanges();

    expect(TestBed.inject(Router).url).toBe('/projects/1/consoles');
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-consoles-page')).toBeTruthy();
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
  function openedApp(): ReturnType<typeof TestBed.createComponent<AppComponent>> {
    logIn();
    navigateToProjectSummary();
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    flushSidenavAndSummary();
    flushConsoleIndicator();
    return fixture;
  }

  it('shows the avatar button instead of a flat logout button', fakeAsync(() => {
    const fixture = openedApp();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.logout')).toBeFalsy();
    expect(compiled.querySelector('.avatar')?.textContent?.trim()).toBe('s');
    expect(compiled.querySelector('.account-menu')).toBeFalsy();
  }));

  it('toggles the account menu from the avatar, showing the username, a separator, Settings and Sign out', fakeAsync(() => {
    const fixture = openedApp();
    const compiled = fixture.nativeElement as HTMLElement;

    compiled.querySelector<HTMLButtonElement>('.avatar')!.click();
    fixture.detectChanges();

    expect(compiled.querySelector('.account-identity')?.textContent?.trim()).toBe('someone');
    expect(compiled.querySelector('.account-separator')).toBeTruthy();
    const items = Array.from(compiled.querySelectorAll('.account-item')).map((el) =>
      el.textContent?.trim(),
    );
    expect(items).toEqual(['Settings', 'Sign out']);

    compiled.querySelector<HTMLButtonElement>('.avatar')!.click();
    fixture.detectChanges();
    expect(compiled.querySelector('.account-menu')).toBeFalsy();
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
    compiled.querySelectorAll<HTMLButtonElement>('.account-item')[1].click();

    httpMock.expectOne('/api/auth/logout').flush(null);
    fixture.detectChanges();

    expect(compiled.querySelector('app-login')).toBeTruthy();
  }));
});
