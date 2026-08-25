import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from './auth.service';

describe('AuthService', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('starts logged out', () => {
    expect(service.isLoggedIn()).toBe(false);
  });

  it('logs in by POSTing form-encoded credentials to /api/auth/login', () => {
    service.login('hani', 's3cret').subscribe();

    const req = httpMock.expectOne('/api/auth/login');
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('Content-Type')).toBe('application/x-www-form-urlencoded');
    expect(req.request.body).toBe('username=hani&password=s3cret');
    req.flush(null);

    expect(service.isLoggedIn()).toBe(true);
  });

  it('does not flip isLoggedIn on a failed login', () => {
    service.login('hani', 'wrong').subscribe({ error: () => {} });

    httpMock.expectOne('/api/auth/login').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(service.isLoggedIn()).toBe(false);
  });

  it('logs out by POSTing to /api/auth/logout', () => {
    service.login('hani', 's3cret').subscribe();
    httpMock.expectOne('/api/auth/login').flush(null);

    service.logout().subscribe();
    const req = httpMock.expectOne('/api/auth/logout');
    expect(req.request.method).toBe('POST');
    req.flush(null);

    expect(service.isLoggedIn()).toBe(false);
  });
});
