import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { AddProjectPopupComponent } from './add-project-popup.component';
import { Project } from '../../models/issue.model';

describe('AddProjectPopupComponent', () => {
  let httpMock: HttpTestingController;
  let navigate: jasmine.Spy;

  const PROJECT: Project = {
    id: 1,
    name: 'bar',
    gitUrl: 'https://github.com/foo/bar.git',
    workareaPath: '/tmp/bar',
    defaultBranch: null,
    accentColor: null,
    template: null,
    status: 'CLONING',
    createdAt: '',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AddProjectPopupComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    httpMock = TestBed.inject(HttpTestingController);
    // A successful create navigates to the new project's console page (#537); the
    // route table is the app's business, so the navigation itself is stubbed here.
    navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
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

  it('importing an existing repository never navigates -- the dialog closes and the sidenav refreshes as before (#537)', () => {
    const fixture = create();
    fixture.componentInstance.gitUrl = 'https://github.com/foo/bar.git';
    fixture.componentInstance.submit();
    httpMock.expectOne('/api/projects').flush(PROJECT);

    expect(navigate).not.toHaveBeenCalled();
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

  describe('GitHub account picker (#532, #550)', () => {
    const TWO_ACCOUNTS = [
      { id: 1, login: 'haninaguib', scopes: ['repo'], hasWorkflowScope: false, needsReconnect: false, tokenExpiresAt: null, createdAt: '2026-08-01T00:00:00Z' },
      { id: 2, login: 'hani-thyme', scopes: ['repo'], hasWorkflowScope: false, needsReconnect: false, tokenExpiresAt: null, createdAt: '2026-08-02T00:00:00Z' },
    ];

    // Rendering runs ngOnInit, which asks the engine for the caller's GitHub accounts.
    function render(): ReturnType<typeof create> {
      const fixture = create();
      fixture.detectChanges();
      return fixture;
    }

    function flushAccounts(accounts: typeof TWO_ACCOUNTS): void {
      const req = httpMock.expectOne('/api/github/accounts');
      expect(req.request.method).toBe('GET');
      req.flush({ accounts });
      // Mounting also asks for the host's project templates (#536); none here.
      httpMock.expectOne('/api/templates').flush({ templates: [] });
    }

    function optionLabels(fixture: ReturnType<typeof create>): string[] {
      return Array.from(fixture.nativeElement.querySelectorAll('select.github-login option') as NodeListOf<HTMLOptionElement>)
        .map((option) => option.textContent?.trim() ?? '');
    }

    function submitButton(fixture: ReturnType<typeof create>): HTMLButtonElement {
      return fixture.nativeElement.querySelector('button[type="submit"]') as HTMLButtonElement;
    }

    it('lists every account in both forms, preselecting the first one', () => {
      const fixture = render();
      flushAccounts(TWO_ACCOUNTS);
      fixture.detectChanges();

      expect(fixture.componentInstance.githubAccountId).toBe(1);
      expect(optionLabels(fixture)).toEqual(['haninaguib', 'hani-thyme']);
      expect(fixture.nativeElement.querySelector('.no-accounts')).toBeNull();

      fixture.componentInstance.setMode('create');
      fixture.detectChanges();

      expect(optionLabels(fixture)).toEqual(['haninaguib', 'hani-thyme']);
      expect(fixture.nativeElement.querySelector('.no-accounts')).toBeNull();
    });

    it('still shows the select with exactly one account', () => {
      const fixture = render();
      flushAccounts([{ id: 9, login: 'solo', scopes: ['repo'], hasWorkflowScope: false, needsReconnect: false, tokenExpiresAt: null, createdAt: '2026-08-01T00:00:00Z' }]);
      fixture.detectChanges();

      expect(optionLabels(fixture)).toEqual(['solo']);
      expect(fixture.componentInstance.githubAccountId).toBe(9);
    });

    it('with zero accounts shows the sign-in hint, disables create, and keeps import enabled without an account', () => {
      const fixture = render();
      flushAccounts([]);
      fixture.componentInstance.gitUrl = 'https://github.com/foo/bar.git';
      fixture.detectChanges();

      expect(fixture.nativeElement.querySelector('select.github-login')).toBeNull();
      expect(fixture.nativeElement.querySelector('.no-accounts')?.textContent).toContain('GitHub accounts');
      expect(submitButton(fixture).disabled).toBeFalse();

      fixture.componentInstance.submit();
      const importReq = httpMock.expectOne('/api/projects');
      expect(importReq.request.body).toEqual({ gitUrl: 'https://github.com/foo/bar.git', name: '' });
      importReq.flush(PROJECT);

      fixture.componentInstance.setMode('create');
      fixture.componentInstance.org = 'my-org';
      fixture.componentInstance.newRepoName = 'my-project';
      fixture.detectChanges();

      expect(fixture.nativeElement.querySelector('.no-accounts')?.textContent).toContain('GitHub accounts');
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

    it('sends the chosen account id with an import', () => {
      const fixture = render();
      flushAccounts(TWO_ACCOUNTS);
      fixture.componentInstance.gitUrl = 'git@thyme.github.com:hani-thyme/ideation_1.git';
      fixture.componentInstance.githubAccountId = 2;

      fixture.componentInstance.submit();

      const req = httpMock.expectOne('/api/projects');
      expect(req.request.body).toEqual({
        gitUrl: 'git@thyme.github.com:hani-thyme/ideation_1.git',
        name: '',
        githubAccountId: 2,
      });
      req.flush(PROJECT);
    });

    it('sends the chosen account id with a create', () => {
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
        githubAccountId: 1,
      });
      req.flush({ ...PROJECT, name: 'my-project' });
    });

    it('treats a failed accounts request as no accounts', () => {
      const fixture = render();
      httpMock.expectOne('/api/github/accounts').flush({ error: 'boom' }, { status: 500, statusText: 'Error' });
      httpMock.expectOne('/api/templates').flush({ templates: [] });
      fixture.componentInstance.setMode('create');
      fixture.componentInstance.org = 'my-org';
      fixture.componentInstance.newRepoName = 'my-project';
      fixture.detectChanges();

      expect(fixture.componentInstance.githubAccountId).toBeNull();
      expect(fixture.nativeElement.querySelector('.no-accounts')?.textContent).toContain('GitHub accounts');
      expect(submitButton(fixture).disabled).toBeTrue();
    });
  });

  describe('template pull-down (#536)', () => {
    const TWO_TEMPLATES = [
      { name: 'node-server', title: 'A Node server', description: 'Express and a Dockerfile' },
      { name: 'springboot-angular', title: 'Spring Boot + Angular', description: 'One runnable jar' },
    ];

    // Rendering runs ngOnInit, which asks for the templates and the gh accounts.
    function render(): ReturnType<typeof create> {
      const fixture = create();
      fixture.detectChanges();
      httpMock.expectOne('/api/github/accounts').flush({
        accounts: [{ id: 1, login: 'haninaguib', scopes: ['repo'], hasWorkflowScope: false, needsReconnect: false, tokenExpiresAt: null, createdAt: '2026-08-01T00:00:00Z' }],
      });
      return fixture;
    }

    function flushTemplates(templates: { name: string; title: string; description: string }[]): void {
      const req = httpMock.expectOne('/api/templates');
      expect(req.request.method).toBe('GET');
      req.flush({ templates });
    }

    function templateOptionLabels(fixture: ReturnType<typeof create>): string[] {
      return Array.from(fixture.nativeElement.querySelectorAll('select.template option') as NodeListOf<HTMLOptionElement>)
        .map((option) => option.textContent?.trim() ?? '');
    }

    it('lists the templates on the create tab with "none" first and selected, and never on the import tab', async () => {
      const fixture = render();
      flushTemplates(TWO_TEMPLATES);
      fixture.detectChanges();

      expect(fixture.nativeElement.querySelector('select.template')).toBeNull();

      fixture.componentInstance.setMode('create');
      fixture.detectChanges();
      // ngModel writes the select's value on a resolved microtask, so settle before
      // reading the DOM selection.
      await fixture.whenStable();
      fixture.detectChanges();

      expect(templateOptionLabels(fixture)).toEqual(['none', 'A Node server', 'Spring Boot + Angular']);
      expect(fixture.componentInstance.template).toBeNull();
      expect((fixture.nativeElement.querySelector('select.template') as HTMLSelectElement).selectedIndex).toBe(0);
      expect(fixture.nativeElement.querySelector('.template-description')).toBeNull();
    });

    it('shows the chosen template description and sends its name as template on create', () => {
      const fixture = render();
      flushTemplates(TWO_TEMPLATES);
      fixture.componentInstance.setMode('create');
      fixture.componentInstance.org = 'my-org';
      fixture.componentInstance.newRepoName = 'my-project';
      fixture.componentInstance.template = 'node-server';
      fixture.detectChanges();

      expect(fixture.nativeElement.querySelector('.template-description')?.textContent).toContain('Express and a Dockerfile');

      fixture.componentInstance.submit();

      const req = httpMock.expectOne('/api/projects/new');
      expect(req.request.body).toEqual({
        org: 'my-org',
        name: 'my-project',
        bootstrapTWorkflow: false,
        githubAccountId: 1,
        template: 'node-server',
      });
      req.flush(PROJECT);
    });

    it('with zero templates shows only "none" and sends no template', () => {
      const fixture = render();
      flushTemplates([]);
      fixture.componentInstance.setMode('create');
      fixture.componentInstance.org = 'my-org';
      fixture.componentInstance.newRepoName = 'my-project';
      fixture.detectChanges();

      expect(templateOptionLabels(fixture)).toEqual(['none']);

      fixture.componentInstance.submit();

      const req = httpMock.expectOne('/api/projects/new');
      expect(req.request.body).toEqual({
        org: 'my-org',
        name: 'my-project',
        bootstrapTWorkflow: false,
        githubAccountId: 1,
      });
      req.flush(PROJECT);
    });

    it('treats a failed templates request as no templates', () => {
      const fixture = render();
      httpMock.expectOne('/api/templates').flush({ error: 'boom' }, { status: 500, statusText: 'Error' });
      fixture.componentInstance.setMode('create');
      fixture.detectChanges();

      expect(fixture.componentInstance.templates).toEqual([]);
      expect(templateOptionLabels(fixture)).toEqual(['none']);
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

    it('navigates to the new project\'s console page as soon as the create succeeds, before emitting (#537)', () => {
      const fixture = create();
      fixture.componentInstance.setMode('create');
      fixture.componentInstance.org = 'my-org';
      fixture.componentInstance.newRepoName = 'my-project';
      const order: string[] = [];
      navigate.and.callFake(() => {
        order.push('navigate');
        return Promise.resolve(true);
      });
      fixture.componentInstance.created.subscribe(() => order.push('created'));
      fixture.componentInstance.submit();

      httpMock.expectOne('/api/projects/new').flush({ ...PROJECT, id: 7, status: 'CLONING' });

      expect(navigate).toHaveBeenCalledWith(['/projects', 7, 'console']);
      expect(order).toEqual(['navigate', 'created']);
    });

    it('navigates there for a create with no template too (#537)', () => {
      const fixture = create();
      fixture.componentInstance.setMode('create');
      fixture.componentInstance.org = 'my-org';
      fixture.componentInstance.newRepoName = 'my-project';
      fixture.componentInstance.template = null;
      fixture.componentInstance.submit();

      httpMock.expectOne('/api/projects/new').flush({ ...PROJECT, id: 8, template: null });

      expect(navigate).toHaveBeenCalledWith(['/projects', 8, 'console']);
    });

    it('does not navigate when the create fails (#537)', () => {
      const fixture = create();
      fixture.componentInstance.setMode('create');
      fixture.componentInstance.org = 'my-org';
      fixture.componentInstance.newRepoName = 'my-project';
      fixture.componentInstance.submit();

      httpMock.expectOne('/api/projects/new').flush({ error: 'nope' }, { status: 400, statusText: 'Bad Request' });

      expect(navigate).not.toHaveBeenCalled();
      expect(fixture.componentInstance.error).toBe('nope');
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
