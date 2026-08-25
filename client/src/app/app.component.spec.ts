import { fakeAsync, TestBed, tick } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { AppComponent } from './app.component';
import { AuthService } from './services/auth.service';
import { SidenavComponent } from './components/sidenav/sidenav.component';
import { routes } from './app.routes';

describe('AppComponent', () => {
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter(routes)],
    }).compileComponents();
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  /** Logs the injected AuthService in synchronously, via a flushed fake response. */
  function logIn(): void {
    TestBed.inject(AuthService).login('someone', 'password').subscribe();
    httpMock.expectOne('/api/auth/login').flush(null);
  }

  function flushIssue(number: number): void {
    httpMock.expectOne(`/api/issues/${number}`).flush({
      number,
      title: 'T',
      state: 'OPEN',
      labels: [],
      body: '',
      createdAt: '',
      updatedAt: '',
    });
    httpMock.expectOne(`/api/issues/${number}/detail`).flush({
      number,
      recordPath: null,
      checks: { passing: 0, failing: 0, pending: 0 },
      branch: null,
      prNumber: null,
      prState: null,
      prDraft: false,
      flowSteps: [],
    });
    httpMock.expectOne(`/api/issues/${number}/worktrees`).flush([]);
  }

  it('should create the app', () => {
    const fixture = TestBed.createComponent(AppComponent);
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('shows only the login screen when not authenticated', () => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-login')).toBeTruthy();
    expect(compiled.querySelector('.shell')).toBeFalsy();
  });

  it('shows an empty state until an issue is selected', () => {
    logIn();
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    httpMock.expectOne('/api/issues/tree').flush([]);
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.empty-state')?.textContent).toContain('select an issue');
  });

  it('selecting an issue navigates to /issues/:id and shows the main content area', fakeAsync(() => {
    logIn();
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    httpMock.expectOne('/api/issues/tree').flush([]);

    fixture.componentInstance.select(42);
    tick();
    fixture.detectChanges();

    expect(TestBed.inject(Router).url).toBe('/issues/42');
    flushIssue(42);
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-main-content')).toBeTruthy();
    expect(compiled.querySelector('.empty-state')).toBeFalsy();
  }));

  it('loading /issues/:id directly selects that issue', fakeAsync(() => {
    logIn();
    TestBed.inject(Router).navigateByUrl('/issues/7');
    tick();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.selectedIssue()).toBe(7);
    httpMock.expectOne('/api/issues/tree').flush([]);
    flushIssue(7);
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-main-content')).toBeTruthy();
  }));

  it('passes the selected issue to the sidenav for highlighting', fakeAsync(() => {
    logIn();
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    httpMock.expectOne('/api/issues/tree').flush([]);

    fixture.componentInstance.select(42);
    tick();
    fixture.detectChanges();
    flushIssue(42);

    const sidenav = fixture.debugElement.query(By.directive(SidenavComponent));
    expect(sidenav.componentInstance.selected).toBe(42);
  }));

  it('returns to the login screen after logging out', () => {
    logIn();
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    httpMock.expectOne('/api/issues/tree').flush([]);

    fixture.componentInstance.logout();
    httpMock.expectOne('/api/auth/logout').flush(null);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-login')).toBeTruthy();
    expect(compiled.querySelector('.shell')).toBeFalsy();
  });
});
