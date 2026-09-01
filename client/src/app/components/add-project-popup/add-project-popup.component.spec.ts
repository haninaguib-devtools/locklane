import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AddProjectPopupComponent } from './add-project-popup.component';
import { Project } from '../../models/issue.model';

describe('AddProjectPopupComponent', () => {
  let httpMock: HttpTestingController;

  const PROJECT: Project = {
    id: 1,
    name: 'bar',
    gitUrl: 'https://github.com/foo/bar.git',
    workareaPath: '/tmp/bar',
    defaultBranch: null,
    accentColor: null,
    status: 'CLONING',
    createdAt: '',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AddProjectPopupComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function create(): ReturnType<typeof TestBed.createComponent<AddProjectPopupComponent>> {
    return TestBed.createComponent(AddProjectPopupComponent);
  }

  it('prefills the name from the URL until the user edits it directly', () => {
    const fixture = create();
    fixture.componentInstance.gitUrl = 'https://github.com/foo/bar.git';
    fixture.componentInstance.onUrlChange();

    expect(fixture.componentInstance.name).toBe('bar');
  });

  it('does not overwrite a manually-edited name on further URL changes', () => {
    const fixture = create();
    fixture.componentInstance.gitUrl = 'https://github.com/foo/bar.git';
    fixture.componentInstance.onUrlChange();

    fixture.componentInstance.name = 'my custom name';
    fixture.componentInstance.onNameChange();

    fixture.componentInstance.gitUrl = 'https://github.com/foo/other.git';
    fixture.componentInstance.onUrlChange();

    expect(fixture.componentInstance.name).toBe('my custom name');
  });

  it('submits the URL and name, emitting the created project', () => {
    const fixture = create();
    fixture.componentInstance.gitUrl = ' https://github.com/foo/bar.git ';
    fixture.componentInstance.name = 'bar';

    let emitted: Project | undefined;
    fixture.componentInstance.created.subscribe((p) => (emitted = p));
    fixture.componentInstance.submit();

    expect(fixture.componentInstance.submitting).toBeTrue();
    const req = httpMock.expectOne('/api/projects');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ gitUrl: 'https://github.com/foo/bar.git', name: 'bar' });
    req.flush(PROJECT);

    expect(fixture.componentInstance.submitting).toBeFalse();
    expect(emitted).toEqual(PROJECT);
  });

  it('submitting a blank name still succeeds (the backend derives one)', () => {
    const fixture = create();
    fixture.componentInstance.gitUrl = 'https://github.com/foo/bar.git';
    fixture.componentInstance.name = '';

    fixture.componentInstance.submit();

    const req = httpMock.expectOne('/api/projects');
    expect(req.request.body).toEqual({ gitUrl: 'https://github.com/foo/bar.git', name: '' });
    req.flush(PROJECT);
  });

  it('does nothing when the URL is blank', () => {
    const fixture = create();
    fixture.componentInstance.gitUrl = '   ';

    fixture.componentInstance.submit();

    httpMock.expectNone('/api/projects');
  });

  it('shows the backend error message and clears submitting on failure', () => {
    const fixture = create();
    fixture.componentInstance.gitUrl = 'https://github.com/foo/bar.git';

    fixture.componentInstance.submit();
    httpMock.expectOne('/api/projects').flush({ error: 'gitUrl is required' }, { status: 400, statusText: 'Bad Request' });

    expect(fixture.componentInstance.submitting).toBeFalse();
    expect(fixture.componentInstance.error).toBe('gitUrl is required');
  });

  it('falls back to a generic error message when the backend gives none', () => {
    const fixture = create();
    fixture.componentInstance.gitUrl = 'https://github.com/foo/bar.git';

    fixture.componentInstance.submit();
    httpMock.expectOne('/api/projects').error(new ProgressEvent('network error'));

    expect(fixture.componentInstance.error).toBe('could not create project');
  });

  describe('GitHub account picker (#532)', () => {
    const TWO_ACCOUNTS = [
      { login: 'haninaguib', active: true },
      { login: 'hani-thyme', active: false },
    ];

    // Rendering runs ngOnInit, which asks the engine for the host's gh accounts.
    function render(): ReturnType<typeof create> {
      const fixture = create();
      fixture.detectChanges();
      return fixture;
    }

    function flushAccounts(accounts: { login: string; active: boolean }[]): void {
      const req = httpMock.expectOne('/api/github/accounts');
      expect(req.request.method).toBe('GET');
      req.flush({ accounts });
    }

    function optionLabels(fixture: ReturnType<typeof create>): string[] {
      return Array.from(fixture.nativeElement.querySelectorAll('select.github-login option') as NodeListOf<HTMLOptionElement>)
        .map((option) => option.textContent?.trim() ?? '');
    }

    function submitButton(fixture: ReturnType<typeof create>): HTMLButtonElement {
      return fixture.nativeElement.querySelector('button[type="submit"]') as HTMLButtonElement;
    }

    it('lists every account in both forms, preselecting the active login', () => {
      const fixture = render();
      flushAccounts(TWO_ACCOUNTS);
      fixture.detectChanges();

      expect(fixture.componentInstance.githubLogin).toBe('haninaguib');
      expect(optionLabels(fixture)).toEqual(['haninaguib (active)', 'hani-thyme']);
      expect(fixture.nativeElement.querySelector('.no-accounts')).toBeNull();

      fixture.componentInstance.setMode('create');
      fixture.detectChanges();

      expect(optionLabels(fixture)).toEqual(['haninaguib (active)', 'hani-thyme']);
      expect(fixture.nativeElement.querySelector('.no-accounts')).toBeNull();
    });

    it('still shows the select with exactly one login', () => {
      const fixture = render();
      flushAccounts([{ login: 'solo', active: true }]);
      fixture.detectChanges();

      expect(optionLabels(fixture)).toEqual(['solo (active)']);
      expect(fixture.componentInstance.githubLogin).toBe('solo');
    });

    it('with zero logins shows the gh auth login hint, disables create, and keeps import enabled without a login', () => {
      const fixture = render();
      flushAccounts([]);
      fixture.componentInstance.gitUrl = 'https://github.com/foo/bar.git';
      fixture.detectChanges();

      expect(fixture.nativeElement.querySelector('select.github-login')).toBeNull();
      expect(fixture.nativeElement.querySelector('.no-accounts')?.textContent).toContain('gh auth login');
      expect(submitButton(fixture).disabled).toBeFalse();

      fixture.componentInstance.submit();
      const importReq = httpMock.expectOne('/api/projects');
      expect(importReq.request.body).toEqual({ gitUrl: 'https://github.com/foo/bar.git', name: '' });
      importReq.flush(PROJECT);

      fixture.componentInstance.setMode('create');
      fixture.componentInstance.org = 'my-org';
      fixture.componentInstance.newRepoName = 'my-project';
      fixture.detectChanges();

      expect(fixture.nativeElement.querySelector('.no-accounts')?.textContent).toContain('gh auth login');
      expect(submitButton(fixture).disabled).toBeTrue();
    });

    it('holds the create button disabled until the accounts have loaded', () => {
      const fixture = render();
      fixture.componentInstance.setMode('create');
      fixture.componentInstance.org = 'my-org';
      fixture.componentInstance.newRepoName = 'my-project';
      fixture.detectChanges();

      expect(submitButton(fixture).disabled).toBeTrue();
      expect(fixture.nativeElement.querySelector('.no-accounts')).toBeNull();

      flushAccounts(TWO_ACCOUNTS);
      fixture.detectChanges();

      expect(submitButton(fixture).disabled).toBeFalse();
    });

    it('sends the chosen login with an import', () => {
      const fixture = render();
      flushAccounts(TWO_ACCOUNTS);
      fixture.componentInstance.gitUrl = 'git@thyme.github.com:hani-thyme/ideation_1.git';
      fixture.componentInstance.githubLogin = 'hani-thyme';

      fixture.componentInstance.submit();

      const req = httpMock.expectOne('/api/projects');
      expect(req.request.body).toEqual({
        gitUrl: 'git@thyme.github.com:hani-thyme/ideation_1.git',
        name: '',
        githubLogin: 'hani-thyme',
      });
      req.flush(PROJECT);
    });

    it('sends the chosen login with a create', () => {
      const fixture = render();
      flushAccounts(TWO_ACCOUNTS);
      fixture.componentInstance.setMode('create');
      fixture.componentInstance.org = 'my-org';
      fixture.componentInstance.newRepoName = 'my-project';

      fixture.componentInstance.submit();

      const req = httpMock.expectOne('/api/projects/new');
      expect(req.request.body).toEqual({
        org: 'my-org',
        name: 'my-project',
        bootstrapTWorkflow: false,
        githubLogin: 'haninaguib',
      });
      req.flush({ ...PROJECT, name: 'my-project' });
    });

    it('treats a failed accounts request as no accounts', () => {
      const fixture = render();
      httpMock.expectOne('/api/github/accounts').flush({ error: 'boom' }, { status: 500, statusText: 'Error' });
      fixture.componentInstance.setMode('create');
      fixture.componentInstance.org = 'my-org';
      fixture.componentInstance.newRepoName = 'my-project';
      fixture.detectChanges();

      expect(fixture.componentInstance.githubLogin).toBeNull();
      expect(fixture.nativeElement.querySelector('.no-accounts')?.textContent).toContain('gh auth login');
      expect(submitButton(fixture).disabled).toBeTrue();
    });
  });

  describe('create new mode (#491)', () => {
    it('submits org, name, and the bootstrap flag to /api/projects/new', () => {
      const fixture = create();
      fixture.componentInstance.setMode('create');
      fixture.componentInstance.org = ' my-org ';
      fixture.componentInstance.newRepoName = ' my-project ';
      fixture.componentInstance.bootstrapTWorkflow = true;

      let emitted: Project | undefined;
      fixture.componentInstance.created.subscribe((p) => (emitted = p));
      fixture.componentInstance.submit();

      expect(fixture.componentInstance.submitting).toBeTrue();
      const req = httpMock.expectOne('/api/projects/new');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ org: 'my-org', name: 'my-project', bootstrapTWorkflow: true });
      req.flush({ ...PROJECT, name: 'my-project' });

      expect(fixture.componentInstance.submitting).toBeFalse();
      expect(emitted).toEqual({ ...PROJECT, name: 'my-project' });
    });

    it('does nothing when the org or the name is blank', () => {
      const fixture = create();
      fixture.componentInstance.setMode('create');
      fixture.componentInstance.org = '  ';
      fixture.componentInstance.newRepoName = 'my-project';

      fixture.componentInstance.submit();

      httpMock.expectNone('/api/projects/new');
    });

    it('shows the backend error message and clears submitting on failure', () => {
      const fixture = create();
      fixture.componentInstance.setMode('create');
      fixture.componentInstance.org = 'my-org';
      fixture.componentInstance.newRepoName = 'my-project';

      fixture.componentInstance.submit();
      httpMock
        .expectOne('/api/projects/new')
        .flush({ error: 'org is required' }, { status: 400, statusText: 'Bad Request' });

      expect(fixture.componentInstance.submitting).toBeFalse();
      expect(fixture.componentInstance.error).toBe('org is required');
    });

    it('switching modes clears any error from the other mode', () => {
      const fixture = create();
      fixture.componentInstance.gitUrl = 'https://github.com/foo/bar.git';
      fixture.componentInstance.submit();
      httpMock.expectOne('/api/projects').flush({ error: 'gitUrl is required' }, { status: 400, statusText: 'Bad Request' });
      expect(fixture.componentInstance.error).toBe('gitUrl is required');

      fixture.componentInstance.setMode('create');

      expect(fixture.componentInstance.error).toBeNull();
    });
  });
});
