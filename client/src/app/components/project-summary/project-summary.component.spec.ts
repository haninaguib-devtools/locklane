import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { ProjectSummaryComponent, countIssues } from './project-summary.component';
import { Project, TreeNode } from '../../models/issue.model';
import { OpenProjectConsole } from '../../services/project-console.service';
import { AgentStore } from '../../services/agent-store';
import { ConsolesService } from '../../services/consoles.service';
import { LastConsoleStore } from '../../services/last-console-store';

describe('ProjectSummaryComponent', () => {

  const GITHUB_OK = { failing: false, failure: null, lastSuccessAt: null };
  let httpMock: HttpTestingController;

  const PROJECT: Project = {
    id: 1,
    name: 'proj-a',
    gitUrl: 'git@example.com:acme/proj-a.git',
    workareaPath: '/tmp/a',
    defaultBranch: 'main',
    accentColor: null,
    template: null,
    status: 'READY',
    createdAt: '2026-08-26T10:00:00Z',
  };

  function tree(): TreeNode[] {
    return [
      {
        number: 1,
        title: 'Initiative',
        kind: 'INITIATIVE',
        state: 'OPEN',
        hasActiveBranch: false,
        labels: [],
        children: [
          { number: 2, title: 'Child A', kind: 'TASK', state: 'OPEN', hasActiveBranch: false, labels: [], children: [] },
          { number: 3, title: 'Child B', kind: 'TASK', state: 'CLOSED', hasActiveBranch: false, labels: [], children: [] },
        ],
      },
      { number: 4, title: 'Standalone', kind: 'TASK', state: 'CLOSED', hasActiveBranch: false, labels: [], children: [] },
    ];
  }

  function session(sessionId: string, createdAt = '2026-08-27T09:00:00Z'): OpenProjectConsole {
    return { sessionId, workingDirectory: '/tmp/a', createdAt, lastAttachedAt: createdAt };
  }

  beforeEach(() => {
    localStorage.removeItem('locklane.sessionAgents');
    localStorage.removeItem('locklane.defaultAgent');
    localStorage.removeItem('locklane.lastConsole');
    TestBed.configureTestingModule({
      imports: [ProjectSummaryComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.removeItem('locklane.sessionAgents');
    localStorage.removeItem('locklane.defaultAgent');
    localStorage.removeItem('locklane.lastConsole');
  });

  /**
   * Creates the component for a project id and flushes its requests: the project
   * list and issue tree always, plus the open-consoles list and the worktree list
   * (#320) whenever the target project is READY (#221) -- a cloning or failed
   * project never fetches either.
   */
  function init(
    projects: Project[] = [PROJECT],
    nodes: TreeNode[] = tree(),
    projectId = 1,
    consoles: OpenProjectConsole[] = [],
  ): ReturnType<typeof TestBed.createComponent<ProjectSummaryComponent>> {
    const fixture = TestBed.createComponent(ProjectSummaryComponent);
    fixture.componentRef.setInput('projectId', projectId);
    fixture.detectChanges();
    httpMock.expectOne('/api/projects').flush(projects);
    const ready = projects.find((p) => p.id === projectId)?.status === 'READY';
    if (ready) {
      httpMock.expectOne(`/api/projects/${projectId}/console/sessions`).flush(consoles);
    }
    httpMock.expectOne(`/api/projects/${projectId}/issues/tree`).flush({ nodes: nodes, github: GITHUB_OK });
    fixture.detectChanges();
    if (ready) {
      httpMock.expectOne(`/api/projects/${projectId}/worktrees`).flush([]);
      fixture.detectChanges();
    }
    return fixture;
  }

  it('counts every issue in the tree, including nested children', () => {
    const fixture = init();
    expect(fixture.componentInstance.counts).toEqual({
      total: 4,
      open: 2,
      closed: 2,
      initiatives: 1,
      tasks: 3,
    });
  });

  it('countIssues tallies an empty tree as all zeroes', () => {
    expect(countIssues([])).toEqual({ total: 0, open: 0, closed: 0, initiatives: 0, tasks: 0 });
  });

  it('picks its project out of the list and renders its facts', () => {
    const fixture = init();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(fixture.componentInstance.project).toEqual(PROJECT);
    expect(text).toContain('proj-a');
    expect(text).toContain('git@example.com:acme/proj-a.git');
    expect(text).toContain('main');
    expect(text).toContain('/tmp/a');
    expect(text).toContain('ready');
  });

  it('renders the counts as tiles', () => {
    const fixture = init();
    const values = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.count .value'),
    ).map((el) => el.textContent?.trim());
    expect(values).toEqual(['4', '2', '2', '1', '3']);
  });

  it('shows an error state when the project is not in the list', () => {
    const fixture = init([], tree());
    expect(fixture.componentInstance.error).toBe(true);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('could not load this project');
  });

  it('keeps the project visible when only the tree fetch fails', () => {
    const fixture = TestBed.createComponent(ProjectSummaryComponent);
    fixture.componentRef.setInput('projectId', 1);
    fixture.detectChanges();
    httpMock.expectOne('/api/projects').flush([PROJECT]);
    httpMock.expectOne('/api/projects/1/console/sessions').flush([]);
    httpMock
      .expectOne('/api/projects/1/issues/tree')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();
    httpMock.expectOne('/api/projects/1/worktrees').flush([]);
    fixture.detectChanges();

    expect(fixture.componentInstance.error).toBe(false);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('proj-a');
    expect(text).toContain('could not load the issue counts');
  });

  it('hides the console button while the project is still cloning', () => {
    const fixture = init([{ ...PROJECT, status: 'CLONING' }]);

    expect((fixture.nativeElement as HTMLElement).querySelector('.console-button')).toBeFalsy();
  });

  it('reads "Open console" and starts one, landing on it with the default agent, when none is open (#221)', () => {
    const fixture = init();
    const opened = jasmine.createSpy('onOpened');
    TestBed.inject(ConsolesService).onOpened.subscribe(opened);
    const navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);

    const button = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.console-button')!;
    expect(button.textContent?.trim()).toBe('Open console');
    button.click();
    fixture.detectChanges();

    expect(button.disabled).toBeTrue();
    const req = httpMock.expectOne('/api/projects/1/console');
    expect(req.request.method).toBe('POST');
    req.flush({ sessionId: 'proj-1-console-abc', workingDirectory: '/tmp/a' });
    fixture.detectChanges();

    expect(navigate).toHaveBeenCalledWith(['/projects', 1, 'console'], {
      queryParams: { session: 'proj-1-console-abc' },
    });
    expect(TestBed.inject(AgentStore).get('proj-1-console-abc')).toBe('claude');
    expect(opened).toHaveBeenCalled();
  });

  it('uses the Settings default agent (not a hardcoded one) when starting a console', () => {
    localStorage.setItem('locklane.defaultAgent', 'codex');
    const fixture = init();
    spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);

    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.console-button')!.click();
    httpMock
      .expectOne('/api/projects/1/console')
      .flush({ sessionId: 'proj-1-console-abc', workingDirectory: '/tmp/a' });

    expect(TestBed.inject(AgentStore).get('proj-1-console-abc')).toBe('codex');
  });

  it('shows an error and re-arms the button when starting a console fails', () => {
    const fixture = init();

    const button = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.console-button')!;
    button.click();
    httpMock.expectOne('/api/projects/1/console').flush(null, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(fixture.componentInstance.consoleError).toBeTrue();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('could not start a console');
    expect(button.disabled).toBeFalse();
  });

  it('reads "Open consoles" and navigates to the most recently interacted-with one when any are open (#221)', () => {
    const fixture = init([PROJECT], tree(), 1, [session('proj-1-console-a'), session('proj-1-console-b')]);
    TestBed.inject(LastConsoleStore).set(1, 'proj-1-console-b');
    const navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);

    const button = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.console-button')!;
    expect(button.textContent?.trim()).toBe('Open consoles');
    button.click();

    expect(navigate).toHaveBeenCalledWith(['/projects', 1, 'console'], {
      queryParams: { session: 'proj-1-console-b' },
    });
  });

  it('falls back to the last console in the list when there is no recorded recency (#221)', () => {
    const fixture = init([PROJECT], tree(), 1, [session('proj-1-console-a'), session('proj-1-console-b')]);
    const navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);

    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.console-button')!.click();

    expect(navigate).toHaveBeenCalledWith(['/projects', 1, 'console'], {
      queryParams: { session: 'proj-1-console-b' },
    });
  });

  it('falls back to the last console in the list when the recorded one is no longer open (#221)', () => {
    const fixture = init([PROJECT], tree(), 1, [session('proj-1-console-a'), session('proj-1-console-b')]);
    TestBed.inject(LastConsoleStore).set(1, 'proj-1-console-gone');
    const navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);

    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.console-button')!.click();

    expect(navigate).toHaveBeenCalledWith(['/projects', 1, 'console'], {
      queryParams: { session: 'proj-1-console-b' },
    });
  });

  it('reloads when the project id changes', () => {
    const fixture = init();
    fixture.componentRef.setInput('projectId', 2);
    fixture.detectChanges();
    httpMock.expectOne('/api/projects').flush([PROJECT, { ...PROJECT, id: 2, name: 'proj-b' }]);
    httpMock.expectOne('/api/projects/2/console/sessions').flush([]);
    httpMock.expectOne('/api/projects/2/issues/tree').flush({ nodes: [], github: GITHUB_OK });
    fixture.detectChanges();
    httpMock.expectOne('/api/projects/2/worktrees').flush([]);
    fixture.detectChanges();

    expect(fixture.componentInstance.project?.name).toBe('proj-b');
    expect(fixture.componentInstance.counts?.total).toBe(0);
  });

  it('opening delete confirmation does not call the API', () => {
    const fixture = init();

    fixture.componentInstance.openDeleteConfirm();

    expect(fixture.componentInstance.showDeleteConfirm).toBeTrue();
    httpMock.expectNone(`/api/projects/${PROJECT.id}`);
  });

  it('cancelling delete confirmation does nothing', () => {
    const fixture = init();
    fixture.componentInstance.openDeleteConfirm();

    fixture.componentInstance.cancelDelete();

    expect(fixture.componentInstance.showDeleteConfirm).toBeFalse();
    httpMock.expectNone(`/api/projects/${PROJECT.id}`);
  });

  it('confirming delete calls the API and navigates to the overview on success', () => {
    const fixture = init();
    fixture.componentInstance.openDeleteConfirm();
    const router = TestBed.inject(Router);
    const navigateSpy = spyOn(router, 'navigate');

    fixture.componentInstance.confirmDelete();

    httpMock.expectOne(`/api/projects/${PROJECT.id}`).flush(null);

    expect(fixture.componentInstance.showDeleteConfirm).toBeFalse();
    expect(fixture.componentInstance.deleting).toBeFalse();
    expect(navigateSpy).toHaveBeenCalledWith(['/']);
  });

  it('emits projectDeleted on successful delete, so the sidenav can drop the project (#249)', () => {
    const fixture = init();
    fixture.componentInstance.openDeleteConfirm();
    spyOn(TestBed.inject(Router), 'navigate');
    const deleted = jasmine.createSpy('projectDeleted');
    fixture.componentInstance.projectDeleted.subscribe(deleted);

    fixture.componentInstance.confirmDelete();
    httpMock.expectOne(`/api/projects/${PROJECT.id}`).flush(null);

    expect(deleted).toHaveBeenCalled();
  });

  it('does not emit projectDeleted when the delete fails', () => {
    const fixture = init();
    fixture.componentInstance.openDeleteConfirm();
    const deleted = jasmine.createSpy('projectDeleted');
    fixture.componentInstance.projectDeleted.subscribe(deleted);

    fixture.componentInstance.confirmDelete();
    httpMock
      .expectOne(`/api/projects/${PROJECT.id}`)
      .flush({ error: 'nope' }, { status: 409, statusText: 'Conflict' });

    expect(deleted).not.toHaveBeenCalled();
  });

  it('shows no chosen accent swatch when the project has no accent color set (#428)', () => {
    const fixture = init();

    const chosen = (fixture.nativeElement as HTMLElement).querySelectorAll('.accent-swatch.chosen');
    expect(chosen.length).toBe(0);
  });

  it('marks the swatch matching the project\'s stored accent color as chosen (#428)', () => {
    const fixture = init([{ ...PROJECT, accentColor: '#5c8a4e' }]);

    const chosen = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.accent-swatch.chosen')!;
    expect(chosen.title).toBe('Sage');
  });

  it('sets the project accent color via PUT, updates the swatch, and refreshes CurrentProjectService (#428)', () => {
    const fixture = init();

    const swatches = (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('.accent-swatch');
    swatches[1].click();

    const req = httpMock.expectOne('/api/projects/1/accent-color');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ accentColor: '#5c8a4e' });
    req.flush(null);
    fixture.detectChanges();

    expect(fixture.componentInstance.project?.accentColor).toBe('#5c8a4e');
    // CurrentProjectService is only ever constructed lazily, on first actual use
    // -- this is that first use. In the real app it's already alive by the time
    // this page exists (AppComponent's topbar reads it unconditionally), so its
    // own constructor fetch and this explicit refresh() never coincide there;
    // here, with no AppComponent in the tree, both fire: one from construction,
    // one from the explicit call refreshCurrentProject() always makes.
    const requests = httpMock.match('/api/projects');
    expect(requests.length).toBe(2);
    requests.forEach((request) => request.flush([{ ...PROJECT, accentColor: '#5c8a4e' }]));
  });

  it('shows an error and re-arms the swatches when setting the accent color fails (#428)', () => {
    const fixture = init();

    (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('.accent-swatch')[0].click();
    httpMock.expectOne('/api/projects/1/accent-color').flush(null, { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();

    expect(fixture.componentInstance.savingAccentColor).toBeFalse();
    expect(fixture.componentInstance.project?.accentColor).toBeNull();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('could not set the accent color');
  });

  it('shows the backend refusal inline when the project has an open worktree or console', () => {
    const fixture = init();
    fixture.componentInstance.openDeleteConfirm();
    const router = TestBed.inject(Router);
    const navigateSpy = spyOn(router, 'navigate');

    fixture.componentInstance.confirmDelete();

    httpMock
      .expectOne(`/api/projects/${PROJECT.id}`)
      .flush(
        { error: 'This project has an open worktree or console — close it before deleting the project.' },
        { status: 409, statusText: 'Conflict' },
      );

    expect(navigateSpy).not.toHaveBeenCalled();
    expect(fixture.componentInstance.deleteError).toBe(
      'This project has an open worktree or console — close it before deleting the project.',
    );
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'This project has an open worktree or console',
    );
  });
});
