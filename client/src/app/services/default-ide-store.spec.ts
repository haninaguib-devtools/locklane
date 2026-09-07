import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { DefaultIdeStore, InstalledIde } from './default-ide-store';

const STORAGE_KEY = 'locklane.defaultIde';

const CODE_SERVER: InstalledIde = { id: 'code-server', label: 'code-server', desktop: false };
const VSCODE: InstalledIde = { id: 'vscode', label: 'VS Code', desktop: true };
const INTELLIJ: InstalledIde = { id: 'intellij', label: 'IntelliJ IDEA', desktop: true };

describe('DefaultIdeStore', () => {
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

  /** A store on a page at `hostname` -- `localhost` unless a test says otherwise (#497's spy pattern). */
  function create(hostname = 'localhost'): DefaultIdeStore {
    const store = TestBed.inject(DefaultIdeStore);
    spyOn<any>(store, 'currentHostname').and.returnValue(hostname);
    return store;
  }

  function flushInstalled(installed: InstalledIde[]): void {
    httpMock.expectOne('/api/ides/installed').flush({ installed });
  }

  it('starts empty when nothing is stored, with code-server as the effective choice', () => {
    const store = create();

    expect(store.ide()).toBe('');
    expect(store.effective()).toEqual(CODE_SERVER);
  });

  it('remembers the choice across instances (i.e. across reloads)', () => {
    create().set('vscode');

    expect(localStorage.getItem(STORAGE_KEY)).toBe('vscode');
    expect(TestBed.inject(DefaultIdeStore).ide()).toBe('vscode');
  });

  it('updates the signal immediately on set', () => {
    const store = create();

    store.set('intellij');

    expect(store.ide()).toBe('intellij');
  });

  it('defaults installed to empty, and does not fetch until asked', () => {
    const store = create();

    expect(store.installed()).toEqual([]);
    expect(store.available()).toEqual([]);
    httpMock.expectNone('/api/ides/installed');
  });

  it('fetches the installed set once asked', () => {
    const store = create();

    store.refreshInstalled();
    flushInstalled([CODE_SERVER, VSCODE, INTELLIJ]);

    expect(store.installed()).toEqual([CODE_SERVER, VSCODE, INTELLIJ]);
  });

  it('does not fetch a second time once already asked', () => {
    const store = create();

    store.refreshInstalled();
    flushInstalled([CODE_SERVER]);
    store.refreshInstalled();

    httpMock.expectNone('/api/ides/installed');
  });

  it('keeps whatever was known, and allows a retry, when the fetch fails', () => {
    const store = create();

    store.refreshInstalled();
    httpMock.expectOne('/api/ides/installed').flush(null, { status: 500, statusText: 'Error' });

    expect(store.installed()).toEqual([]);

    store.refreshInstalled();
    flushInstalled([CODE_SERVER, VSCODE]);

    expect(store.installed()).toEqual([CODE_SERVER, VSCODE]);
  });

  it('offers every installed entry on localhost', () => {
    const store = create('localhost');

    store.refreshInstalled();
    flushInstalled([CODE_SERVER, VSCODE, INTELLIJ]);

    expect(store.available()).toEqual([CODE_SERVER, VSCODE, INTELLIJ]);
  });

  it('offers only non-desktop entries away from localhost', () => {
    const store = create('example.com');

    store.refreshInstalled();
    flushInstalled([CODE_SERVER, VSCODE, INTELLIJ]);

    expect(store.available()).toEqual([CODE_SERVER]);
  });

  it('honours a stored desktop IDE on localhost', () => {
    localStorage.setItem(STORAGE_KEY, 'intellij');
    const store = create('localhost');

    store.refreshInstalled();
    flushInstalled([CODE_SERVER, VSCODE, INTELLIJ]);

    expect(store.effective()).toEqual(INTELLIJ);
  });

  it('falls back to code-server when the stored id is not installed', () => {
    localStorage.setItem(STORAGE_KEY, 'vscode');
    const store = create('localhost');

    store.refreshInstalled();
    flushInstalled([CODE_SERVER, INTELLIJ]);

    expect(store.effective()).toEqual(CODE_SERVER);
  });

  it('falls back to code-server when the stored id is a desktop IDE on a non-localhost page', () => {
    localStorage.setItem(STORAGE_KEY, 'vscode');
    const store = create('example.com');

    store.refreshInstalled();
    flushInstalled([CODE_SERVER, VSCODE]);

    expect(store.effective()).toEqual(CODE_SERVER);
  });

  it('falls back to code-server, with its stock label, even when the engine did not list code-server', () => {
    localStorage.setItem(STORAGE_KEY, 'nope');
    const store = create('localhost');

    store.refreshInstalled();
    flushInstalled([VSCODE]);

    expect(store.effective()).toEqual(CODE_SERVER);
  });

  it('follows a new choice immediately once it is among the available entries', () => {
    const store = create('localhost');
    store.refreshInstalled();
    flushInstalled([CODE_SERVER, VSCODE]);

    store.set('vscode');

    expect(store.effective()).toEqual(VSCODE);
  });
});
