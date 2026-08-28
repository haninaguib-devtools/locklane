import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from './auth.service';
import { unauthorizedInterceptor } from './unauthorized.interceptor';

describe('unauthorizedInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let auth: AuthService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([unauthorizedInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);

    auth.checkSession().subscribe();
    httpMock.expectOne('/api/auth/me').flush({ username: 'hani' });
    expect(auth.isLoggedIn()).toBe(true);
  });

  afterEach(() => httpMock.verify());

  it('clears the logged-in state on a 401 from any endpoint', () => {
    http.get('/api/projects').subscribe({ error: () => {} });

    httpMock.expectOne('/api/projects').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(auth.isLoggedIn()).toBe(false);
    expect(auth.username()).toBeNull();
  });

  it('still propagates the error to the caller', () => {
    let error: unknown;
    http.get('/api/projects').subscribe({ error: (e) => (error = e) });

    httpMock.expectOne('/api/projects').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(error).toBeTruthy();
  });

  it('leaves the logged-in state untouched for a non-401 error', () => {
    http.get('/api/projects').subscribe({ error: () => {} });

    httpMock.expectOne('/api/projects').flush(null, { status: 500, statusText: 'Server Error' });

    expect(auth.isLoggedIn()).toBe(true);
  });
});
