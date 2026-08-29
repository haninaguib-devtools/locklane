import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AdminUsersComponent } from './admin-users.component';
import { AdminUser } from '../../services/admin.service';

describe('AdminUsersComponent', () => {
  let httpMock: HttpTestingController;

  const ALICE: AdminUser = {
    id: 2,
    username: 'alice',
    role: 'USER',
    mustChangePassword: true,
    createdAt: '2026-08-29T00:00:00Z',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AdminUsersComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function create(): ReturnType<typeof TestBed.createComponent<AdminUsersComponent>> {
    const fixture = TestBed.createComponent(AdminUsersComponent);
    fixture.detectChanges(); // triggers ngOnInit -> refresh()
    httpMock.expectOne('/api/admin/users').flush([ALICE]);
    return fixture;
  }

  it('loads the account list on init', () => {
    const fixture = create();

    expect(fixture.componentInstance.users()).toEqual([ALICE]);
    expect(fixture.componentInstance.loading()).toBeFalse();
  });

  it('shows a plain error message when the list fails to load', () => {
    const fixture = TestBed.createComponent(AdminUsersComponent);
    fixture.detectChanges();

    httpMock
      .expectOne('/api/admin/users')
      .flush({ error: 'something went wrong' }, { status: 500, statusText: 'Server Error' });

    expect(fixture.componentInstance.loadError()).toBe('something went wrong');
  });

  it('creates a user and shows the generated temporary password exactly once', () => {
    const fixture = create();
    fixture.componentInstance.newUsername = 'bob';
    fixture.componentInstance.newPassword = '';

    fixture.componentInstance.createUser();

    const createReq = httpMock.expectOne('/api/admin/users');
    expect(createReq.request.body).toEqual({ username: 'bob', password: null });
    createReq.flush({
      user: { id: 3, username: 'bob', role: 'USER', mustChangePassword: true, createdAt: '2026-08-29T00:00:00Z' },
      temporaryPassword: 'a-generated-secret',
    });

    expect(fixture.componentInstance.lastCreated).toEqual({
      username: 'bob',
      temporaryPassword: 'a-generated-secret',
    });
    expect(fixture.componentInstance.newUsername).toBe('');

    // createUser() also refreshes the list.
    httpMock.expectOne('/api/admin/users').flush([ALICE]);
  });

  it('does not show a temporary password when the admin chose one', () => {
    const fixture = create();
    fixture.componentInstance.newUsername = 'bob';
    fixture.componentInstance.newPassword = 'chosen-by-admin';

    fixture.componentInstance.createUser();

    httpMock.expectOne('/api/admin/users').flush({
      user: { id: 3, username: 'bob', role: 'USER', mustChangePassword: true, createdAt: '2026-08-29T00:00:00Z' },
    });

    expect(fixture.componentInstance.lastCreated).toBeNull();
    httpMock.expectOne('/api/admin/users').flush([ALICE]);
  });

  it('shows an error and does not clear the form when creation fails', () => {
    const fixture = create();
    fixture.componentInstance.newUsername = 'alice';

    fixture.componentInstance.createUser();

    httpMock
      .expectOne('/api/admin/users')
      .flush({ error: 'that username is already taken' }, { status: 409, statusText: 'Conflict' });

    expect(fixture.componentInstance.createError).toBe('that username is already taken');
    expect(fixture.componentInstance.newUsername).toBe('alice');
  });

  it('does nothing when the username is blank', () => {
    const fixture = create();
    fixture.componentInstance.newUsername = '   ';

    fixture.componentInstance.createUser();

    httpMock.expectNone((req) => req.method === 'POST');
  });

  it('deletes the account on confirmation and refreshes the list', () => {
    const fixture = create();
    fixture.componentInstance.requestDelete(ALICE);
    expect(fixture.componentInstance.deleteTarget).toEqual(ALICE);

    fixture.componentInstance.confirmDelete();

    const deleteReq = httpMock.expectOne('/api/admin/users/2');
    expect(deleteReq.request.method).toBe('DELETE');
    deleteReq.flush(null);

    expect(fixture.componentInstance.deleteTarget).toBeNull();
    httpMock.expectOne('/api/admin/users').flush([]);
  });

  it('cancelling a delete request leaves the account untouched', () => {
    const fixture = create();
    fixture.componentInstance.requestDelete(ALICE);

    fixture.componentInstance.cancelDelete();

    expect(fixture.componentInstance.deleteTarget).toBeNull();
    httpMock.expectNone((req) => req.method === 'DELETE');
  });

  it('shows an error when the delete request fails', () => {
    const fixture = create();
    fixture.componentInstance.requestDelete(ALICE);

    fixture.componentInstance.confirmDelete();

    httpMock
      .expectOne('/api/admin/users/2')
      .flush({ error: 'you cannot delete your own account' }, { status: 409, statusText: 'Conflict' });

    expect(fixture.componentInstance.deleteError).toBe('you cannot delete your own account');
    expect(fixture.componentInstance.deleteTarget).toBeNull();
  });

  it('emits closed when dismissed', () => {
    const fixture = create();
    let closed = false;
    fixture.componentInstance.closed.subscribe(() => (closed = true));

    fixture.componentInstance.onEscape();

    expect(closed).toBeTrue();
  });
});
