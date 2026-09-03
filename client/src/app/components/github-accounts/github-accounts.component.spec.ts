import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { GithubAccountsComponent } from './github-accounts.component';
import { GithubAccount } from '../../services/projects.service';

describe('GithubAccountsComponent', () => {
  let httpMock: HttpTestingController;

  const HANINAGUIB: GithubAccount = {
    id: 1,
    login: 'haninaguib',
    scopes: ['repo', 'workflow'],
    hasWorkflowScope: true, needsReconnect: false, tokenExpiresAt: null,
    createdAt: '2026-08-01T00:00:00Z',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [GithubAccountsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function create(): ReturnType<typeof TestBed.createComponent<GithubAccountsComponent>> {
    const fixture = TestBed.createComponent(GithubAccountsComponent);
    fixture.detectChanges(); // triggers ngOnInit -> refresh()
    httpMock.expectOne('/api/github/accounts').flush({ accounts: [HANINAGUIB] });
    return fixture;
  }

  it('flags an account the engine could no longer renew as needing reconnection', () => {
    // #656: a device-flow token whose renewal failed for good.
    const fixture = TestBed.createComponent(GithubAccountsComponent);
    fixture.detectChanges();
    httpMock.expectOne('/api/github/accounts').flush({
      accounts: [HANINAGUIB, { ...HANINAGUIB, id: 2, login: 'dead', needsReconnect: true }],
    });
    fixture.detectChanges();

    const badges = (fixture.nativeElement as HTMLElement).querySelectorAll('.needs-reconnect');
    expect(badges.length).toBe(1);
    expect(badges[0].textContent?.trim()).toBe('needs reconnection');
    expect(badges[0].closest('li')?.textContent).toContain('dead');
  });

  it('loads the account list on init', () => {
    const fixture = create();

    expect(fixture.componentInstance.accounts()).toEqual([HANINAGUIB]);
    expect(fixture.componentInstance.loading()).toBeFalse();
  });

  it('shows a plain error message when the list fails to load', () => {
    const fixture = TestBed.createComponent(GithubAccountsComponent);
    fixture.detectChanges();

    httpMock
      .expectOne('/api/github/accounts')
      .flush({ error: 'something went wrong' }, { status: 500, statusText: 'Server Error' });

    expect(fixture.componentInstance.loadError()).toBe('something went wrong');
  });

  it('adds a token and refreshes the list', () => {
    const fixture = create();
    fixture.componentInstance.pastedToken = 'ghp_pasted';

    fixture.componentInstance.addToken();

    const req = httpMock.expectOne('/api/github/accounts/token');
    expect(req.request.body).toEqual({ token: 'ghp_pasted' });
    req.flush({ id: 2, login: 'pasted', scopes: ['repo'], hasWorkflowScope: false, needsReconnect: false, tokenExpiresAt: null, createdAt: '' });

    expect(fixture.componentInstance.pastedToken).toBe('');
    httpMock.expectOne('/api/github/accounts').flush({ accounts: [HANINAGUIB] });
  });

  it('shows an error and keeps the token when adding fails', () => {
    const fixture = create();
    fixture.componentInstance.pastedToken = 'bad-token';

    fixture.componentInstance.addToken();

    httpMock
      .expectOne('/api/github/accounts/token')
      .flush({ error: 'could not verify this token with GitHub' }, { status: 400, statusText: 'Bad Request' });

    expect(fixture.componentInstance.addTokenError).toBe('could not verify this token with GitHub');
    expect(fixture.componentInstance.pastedToken).toBe('bad-token');
  });

  it('does nothing when the pasted token is blank', () => {
    const fixture = create();
    fixture.componentInstance.pastedToken = '   ';

    fixture.componentInstance.addToken();

    httpMock.expectNone('/api/github/accounts/token');
  });

  it('starts a device flow and shows the code', () => {
    const fixture = create();

    fixture.componentInstance.startDeviceFlow();

    httpMock.expectOne('/api/github/accounts/device/start').flush({
      flowId: 'flow-1',
      userCode: 'ABCD-1234',
      verificationUri: 'https://github.com/login/device',
      expiresInSeconds: 900,
    });

    expect(fixture.componentInstance.deviceFlow()?.userCode).toBe('ABCD-1234');
    httpMock.expectOne('/api/github/accounts/device/flow-1').flush({ status: 'PENDING', account: null, errorMessage: null });
  });

  it('shows a friendly message when device flow is not configured on this host', () => {
    const fixture = create();

    fixture.componentInstance.startDeviceFlow();

    httpMock
      .expectOne('/api/github/accounts/device/start')
      .flush({ error: 'no GitHub OAuth App is configured' }, { status: 501, statusText: 'Not Implemented' });

    expect(fixture.componentInstance.deviceFlowNotConfigured()).toBeTrue();
    expect(fixture.componentInstance.deviceFlowError()).toBeNull();
  });

  it('polls until the device flow completes, then refreshes the list', fakeAsync(() => {
    const fixture = create();
    fixture.componentInstance.startDeviceFlow();
    httpMock.expectOne('/api/github/accounts/device/start').flush({
      flowId: 'flow-1',
      userCode: 'ABCD-1234',
      verificationUri: 'https://github.com/login/device',
      expiresInSeconds: 900,
    });
    httpMock.expectOne('/api/github/accounts/device/flow-1').flush({ status: 'PENDING', account: null, errorMessage: null });
    expect(fixture.componentInstance.deviceFlow()).not.toBeNull();

    tick(2000);
    httpMock.expectOne('/api/github/accounts/device/flow-1').flush({ status: 'COMPLETE', account: HANINAGUIB, errorMessage: null });
    httpMock.expectOne('/api/github/accounts').flush({ accounts: [HANINAGUIB] });

    expect(fixture.componentInstance.deviceFlow()).toBeNull();
    fixture.destroy();
  }));

  it('surfaces a failed device flow and clears it', fakeAsync(() => {
    const fixture = create();
    fixture.componentInstance.startDeviceFlow();
    httpMock.expectOne('/api/github/accounts/device/start').flush({
      flowId: 'flow-1',
      userCode: 'ABCD-1234',
      verificationUri: 'https://github.com/login/device',
      expiresInSeconds: 900,
    });
    httpMock
      .expectOne('/api/github/accounts/device/flow-1')
      .flush({ status: 'FAILED', account: null, errorMessage: 'the code expired before it was approved' });

    expect(fixture.componentInstance.deviceFlow()).toBeNull();
    expect(fixture.componentInstance.deviceFlowError()).toBe('the code expired before it was approved');
    fixture.destroy();
  }));

  it('cancelling a device flow stops polling', fakeAsync(() => {
    const fixture = create();
    fixture.componentInstance.startDeviceFlow();
    httpMock.expectOne('/api/github/accounts/device/start').flush({
      flowId: 'flow-1',
      userCode: 'ABCD-1234',
      verificationUri: 'https://github.com/login/device',
      expiresInSeconds: 900,
    });
    httpMock.expectOne('/api/github/accounts/device/flow-1').flush({ status: 'PENDING', account: null, errorMessage: null });

    fixture.componentInstance.cancelDeviceFlow();
    tick(5000);

    httpMock.expectNone('/api/github/accounts/device/flow-1');
    expect(fixture.componentInstance.deviceFlow()).toBeNull();
    fixture.destroy();
  }));

  it('removes an account on confirmation and refreshes the list', () => {
    const fixture = create();
    fixture.componentInstance.requestRemove(HANINAGUIB);
    expect(fixture.componentInstance.removeTarget).toEqual(HANINAGUIB);

    fixture.componentInstance.confirmRemove();

    const req = httpMock.expectOne('/api/github/accounts/1');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);

    expect(fixture.componentInstance.removeTarget).toBeNull();
    httpMock.expectOne('/api/github/accounts').flush({ accounts: [] });
  });

  it('shows a conflict message when removal is refused because a project still uses it', () => {
    const fixture = create();
    fixture.componentInstance.requestRemove(HANINAGUIB);

    fixture.componentInstance.confirmRemove();

    httpMock
      .expectOne('/api/github/accounts/1')
      .flush({ error: 'still used by my-project' }, { status: 409, statusText: 'Conflict' });

    expect(fixture.componentInstance.removeError).toBe('still used by my-project');
    expect(fixture.componentInstance.removeTarget).toBeNull();
  });

  it('cancelling a remove request leaves the account untouched', () => {
    const fixture = create();
    fixture.componentInstance.requestRemove(HANINAGUIB);

    fixture.componentInstance.cancelRemove();

    expect(fixture.componentInstance.removeTarget).toBeNull();
    httpMock.expectNone((req) => req.method === 'DELETE');
  });

  it('emits closed when dismissed', () => {
    const fixture = create();
    let closed = false;
    fixture.componentInstance.closed.subscribe(() => (closed = true));

    fixture.componentInstance.onEscape();

    expect(closed).toBeTrue();
  });
});
