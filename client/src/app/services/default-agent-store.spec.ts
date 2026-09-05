import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { DefaultAgentStore } from './default-agent-store';

const STORAGE_KEY = 'locklane.defaultAgent';

describe('DefaultAgentStore', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    localStorage.removeItem(STORAGE_KEY);
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.removeItem(STORAGE_KEY);
  });

  function create(): DefaultAgentStore {
    return TestBed.inject(DefaultAgentStore);
  }

  it('starts empty when nothing is stored', () => {
    expect(create().agent()).toBe('');
  });

  it('remembers the choice across instances (i.e. across reloads)', () => {
    create().set('codex');

    expect(create().agent()).toBe('codex');
  });

  it('updates the signal immediately on set', () => {
    const store = create();

    store.set('codex');

    expect(store.agent()).toBe('codex');
  });

  it('defaults installed to empty, and does not fetch until asked', () => {
    const store = create();

    expect(store.installed()).toEqual([]);
    httpMock.expectNone('/api/agents/installed');
  });

  it('fetches the installed set once asked', () => {
    const store = create();

    store.refreshInstalled();
    httpMock
      .expectOne('/api/agents/installed')
      .flush({ installed: [{ id: 'claude', label: 'Claude' }, { id: 'opencode', label: 'OpenCode' }] });

    expect(store.installed()).toEqual([{ id: 'claude', label: 'Claude' }, { id: 'opencode', label: 'OpenCode' }]);
  });

  it('does not fetch a second time once already asked', () => {
    const store = create();

    store.refreshInstalled();
    httpMock.expectOne('/api/agents/installed').flush({ installed: [{ id: 'claude', label: 'Claude' }] });
    store.refreshInstalled();

    httpMock.expectNone('/api/agents/installed');
  });

  it('keeps whatever was known, and allows a retry, when the fetch fails', () => {
    const store = create();

    store.refreshInstalled();
    httpMock.expectOne('/api/agents/installed').flush(null, { status: 500, statusText: 'Error' });

    expect(store.installed()).toEqual([]);

    store.refreshInstalled();
    httpMock.expectOne('/api/agents/installed').flush({ installed: [{ id: 'codex', label: 'Codex' }] });

    expect(store.installed()).toEqual([{ id: 'codex', label: 'Codex' }]);
  });

  it('falls back to the first installed agent when nothing was stored', () => {
    const store = create();

    store.refreshInstalled();
    httpMock
      .expectOne('/api/agents/installed')
      .flush({ installed: [{ id: 'codex', label: 'Codex' }, { id: 'claude', label: 'Claude' }] });

    expect(store.agent()).toBe('codex');
  });

  it('falls back to the first installed agent when the stored one is no longer installed', () => {
    localStorage.setItem(STORAGE_KEY, 'gpt-5');
    const store = create();

    store.refreshInstalled();
    httpMock.expectOne('/api/agents/installed').flush({ installed: [{ id: 'claude', label: 'Claude' }] });

    expect(store.agent()).toBe('claude');
  });

  it('keeps the stored choice when it is among the installed agents', () => {
    localStorage.setItem(STORAGE_KEY, 'codex');
    const store = create();

    store.refreshInstalled();
    httpMock
      .expectOne('/api/agents/installed')
      .flush({ installed: [{ id: 'claude', label: 'Claude' }, { id: 'codex', label: 'Codex' }] });

    expect(store.agent()).toBe('codex');
  });
});
