import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AdminService, AdminUser, CreatedUser } from './admin.service';

describe('AdminService', () => {
  let service: AdminService;
  let httpMock: HttpTestingController;

  const USER: AdminUser = {
    id: 2,
    username: 'newbie',
    role: 'USER',
    mustChangePassword: true,
    createdAt: '2026-08-29T00:00:00Z',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AdminService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lists accounts from /api/admin/users', () => {
    let result: AdminUser[] | undefined;
    service.list().subscribe((r) => (result = r));

    const req = httpMock.expectOne('/api/admin/users');
    expect(req.request.method).toBe('GET');
    req.flush([USER]);

    expect(result).toEqual([USER]);
  });

  it('creates a user, sending a null password when left blank', () => {
    let result: CreatedUser | undefined;
    service.create('newbie', '').subscribe((r) => (result = r));

    const req = httpMock.expectOne('/api/admin/users');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ username: 'newbie', password: null });
    req.flush({ user: USER, temporaryPassword: 'generated-secret' });

    expect(result?.temporaryPassword).toBe('generated-secret');
  });

  it('creates a user with an admin-supplied password', () => {
    service.create('newbie', 'chosen').subscribe();

    const req = httpMock.expectOne('/api/admin/users');
    expect(req.request.body).toEqual({ username: 'newbie', password: 'chosen' });
    req.flush({ user: USER });
  });

  it('deletes a user by id', () => {
    service.delete(2).subscribe();

    const req = httpMock.expectOne('/api/admin/users/2');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('maps a backend error body to a plain message', () => {
    let error: Error | undefined;
    service.create('taken', '').subscribe({ error: (e) => (error = e) });

    httpMock
      .expectOne('/api/admin/users')
      .flush({ error: 'that username is already taken' }, { status: 409, statusText: 'Conflict' });

    expect(error?.message).toBe('that username is already taken');
  });
});
