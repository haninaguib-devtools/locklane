import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AppComponent } from './app.component';
import { AuthService } from './services/auth.service';

describe('AppComponent', () => {
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  /** Logs the injected AuthService in synchronously, via a flushed fake response. */
  function logIn(): void {
    TestBed.inject(AuthService).login('someone', 'password').subscribe();
    httpMock.expectOne('/api/auth/login').flush(null);
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

  it('shows the main content area once an issue is selected', () => {
    logIn();
    const fixture = TestBed.createComponent(AppComponent);
    fixture.componentInstance.select(42);
    fixture.detectChanges();
    httpMock.expectOne('/api/issues/tree').flush([]);
    httpMock.expectOne('/api/issues/42').flush({
      number: 42,
      title: 'T',
      state: 'OPEN',
      labels: [],
      body: '',
      createdAt: '',
      updatedAt: '',
    });
    httpMock.expectOne('/api/issues/42/detail').flush({
      number: 42,
      recordPath: null,
      checks: { passing: 0, failing: 0, pending: 0 },
      branch: null,
      prNumber: null,
      prState: null,
      prDraft: false,
      flowSteps: [],
    });
    httpMock.expectOne('/api/issues/42/worktrees').flush([]);
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-main-content')).toBeTruthy();
    expect(compiled.querySelector('.empty-state')).toBeFalsy();
  });

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
