import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { GithubAccountsService } from './github-accounts.service';

describe('GithubAccountsService', () => {
  let service: GithubAccountsService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(GithubAccountsService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lists accounts from GET /api/github/accounts', () => {
    let result: unknown;
    service.list().subscribe((accounts) => (result = accounts));

    const req = httpMock.expectOne('/api/github/accounts');
    expect(req.request.method).toBe('GET');
    req.flush({
      accounts: [{ id: 1, login: 'haninaguib', scopes: ['repo'], hasWorkflowScope: false, createdAt: '2026-08-01T00:00:00Z' }],
    });

    expect(result).toEqual([
      { id: 1, login: 'haninaguib', scopes: ['repo'], hasWorkflowScope: false, createdAt: '2026-08-01T00:00:00Z' },
    ]);
  });

  it('adds a token via POST /api/github/accounts/token', () => {
    service.addByToken('ghp_secret').subscribe();

    const req = httpMock.expectOne('/api/github/accounts/token');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ token: 'ghp_secret' });
    req.flush({ id: 1, login: 'haninaguib', scopes: ['repo'], hasWorkflowScope: false, createdAt: '' });
  });

  it('starts a device flow via POST /api/github/accounts/device/start', () => {
    let result: unknown;
    service.startDeviceFlow().subscribe((r) => (result = r));

    const req = httpMock.expectOne('/api/github/accounts/device/start');
    expect(req.request.method).toBe('POST');
    req.flush({ flowId: 'flow-1', userCode: 'ABCD-1234', verificationUri: 'https://github.com/login/device', expiresInSeconds: 900 });

    expect(result).toEqual({
      flowId: 'flow-1',
      userCode: 'ABCD-1234',
      verificationUri: 'https://github.com/login/device',
      expiresInSeconds: 900,
    });
  });

  it('polls device flow status via GET /api/github/accounts/device/{flowId}', () => {
    let result: unknown;
    service.deviceFlowStatus('flow-1').subscribe((r) => (result = r));

    const req = httpMock.expectOne('/api/github/accounts/device/flow-1');
    expect(req.request.method).toBe('GET');
    req.flush({ status: 'PENDING', account: null, errorMessage: null });

    expect(result).toEqual({ status: 'PENDING', account: null, errorMessage: null });
  });

  it('removes an account via DELETE /api/github/accounts/{id}', () => {
    service.remove(7).subscribe();

    const req = httpMock.expectOne('/api/github/accounts/7');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });
});
