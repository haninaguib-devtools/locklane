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

  it('defaults to claude when nothing is stored', () => {
    expect(create().agent()).toBe('claude');
  });

  it('remembers the choice across instances (i.e. across reloads)', () => {
    create().set('codex');

    expect(create().agent()).toBe('codex');
  });

  it('falls back to claude for unrecognized storage content', () => {
    localStorage.setItem(STORAGE_KEY, 'gpt-5');

    expect(create().agent()).toBe('claude');
  });

  it('updates the signal immediately on set', () => {
    const store = create();

    store.set('codex');

    expect(store.agent()).toBe('codex');
  });

  it('defaults installed to all three, and does not fetch until asked', () => {
    const store = create();

    expect(store.installed()).toEqual(['claude', 'codex', 'opencode']);
    httpMock.expectNone('/api/agents/installed');
  });

  it('fetches the installed set once asked, filtering out anything unrecognized', () => {
    const store = create();

    store.refreshInstalled();
    httpMock.expectOne('/api/agents/installed').flush({ installed: ['claude', 'gpt-5', 'opencode'] });

    expect(store.installed()).toEqual(['claude', 'opencode']);
  });

  it('does not fetch a second time once already asked', () => {
    const store = create();

    store.refreshInstalled();
    httpMock.expectOne('/api/agents/installed').flush({ installed: ['claude'] });
    store.refreshInstalled();

    httpMock.expectNone('/api/agents/installed');
  });

  it('keeps the all-agents fallback, and allows a retry, when the fetch fails', () => {
    const store = create();

    store.refreshInstalled();
    httpMock.expectOne('/api/agents/installed').flush(null, { status: 500, statusText: 'Error' });

    expect(store.installed()).toEqual(['claude', 'codex', 'opencode']);

    store.refreshInstalled();
    httpMock.expectOne('/api/agents/installed').flush({ installed: ['codex'] });

    expect(store.installed()).toEqual(['codex']);
  });
});
