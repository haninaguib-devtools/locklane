import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { OverviewComponent, aggregateCounts } from './overview.component';
import { Project, TreeNode } from '../../models/issue.model';
import { AgentStore } from '../../services/agent-store';
import { ConsolesService } from '../../services/consoles.service';
import { EventsService } from '../../services/events.service';

describe('OverviewComponent', () => {

  const GITHUB_OK = { failing: false, failure: null, lastSuccessAt: null };
  let httpMock: HttpTestingController;

  const PROJECT_A: Project = {
    id: 1,
    name: 'proj-a',
    gitUrl: 'url-a',
    workareaPath: '/tmp/a',
    defaultBranch: 'main',
    accentColor: null,
    template: null,
    status: 'READY',
    createdAt: '',
  };
  const PROJECT_B: Project = { ...PROJECT_A, id: 2, name: 'proj-b', gitUrl: 'url-b', workareaPath: '/tmp/b' };

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

  beforeEach(() => {
    localStorage.removeItem('locklane.sessionAgents');
    TestBed.configureTestingModule({
      imports: [OverviewComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.removeItem('locklane.sessionAgents');
  });

  function init(projects: Project[]): ReturnType<typeof TestBed.createComponent<OverviewComponent>> {
    const fixture = TestBed.createComponent(OverviewComponent);
    fixture.detectChanges();
    httpMock.expectOne('/api/projects').flush(projects);
    return fixture;
  }

  /** Reaches past EventsService's public API (#129) -- there is no other way to fake an incoming socket message. */
  function emitAppEvent(event: unknown): void {
    (TestBed.inject(EventsService) as unknown as { eventsSubject: { next: (e: unknown) => void } }).eventsSubject.next(
      event,
    );
  }

  describe('aggregateCounts', () => {
    it('sums totals across every project', () => {
      const a = { total: 4, open: 2, closed: 2, initiatives: 1, tasks: 3 };
      const b = { total: 1, open: 1, closed: 0, initiatives: 0, tasks: 1 };
      expect(aggregateCounts([a, b])).toEqual({ total: 5, open: 3, closed: 2, initiatives: 1, tasks: 4 });
    });

    it('skips a null entry (a project whose tree failed to load)', () => {
      const a = { total: 4, open: 2, closed: 2, initiatives: 1, tasks: 3 };
      expect(aggregateCounts([a, null])).toEqual(a);
    });

    it('tallies an empty list as all zeroes', () => {
      expect(aggregateCounts([])).toEqual({ total: 0, open: 0, closed: 0, initiatives: 0, tasks: 0 });
    });
  });

  it('shows a dedicated zero-project empty state with no stat tiles', () => {
    const fixture = init([]);
    fixture.detectChanges();

    expect(fixture.componentInstance.rows).toEqual([]);
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('No projects yet');
    expect(compiled.textContent).not.toContain('select a project to begin');
    expect(compiled.querySelector('.count')).toBeFalsy();
  });

  it('the zero-project CTA emits addProject (#227)', () => {
    const fixture = init([]);
    fixture.detectChanges();
    let emitted = false;
    fixture.componentInstance.addProject.subscribe(() => (emitted = true));

    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.zero-cta')!.click();

    expect(emitted).toBeTrue();
  });

  it('refresh() re-fetches the project list (#227)', () => {
    const fixture = init([]);
    fixture.detectChanges();

    fixture.componentInstance.refresh();
    httpMock.expectOne('/api/projects').flush([PROJECT_A]);
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: [], github: GITHUB_OK });
    fixture.detectChanges();

    expect(fixture.componentInstance.rows.length).toBe(1);
  });

  it('updates a cloning row off a projectStatus event, without flashing loading or re-polling (#721)', fakeAsync(() => {
    const fixture = init([{ ...PROJECT_A, status: 'CLONING' }]);
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: [], github: GITHUB_OK });
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('cloning');
    expect(fixture.componentInstance.loading).toBeFalse();

    emitAppEvent({ type: 'projectStatus', projectId: 1, status: 'READY', defaultBranch: 'main' });
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    fixture.detectChanges();

    expect(fixture.componentInstance.rows[0].project.status).toBe('READY');
    expect(fixture.componentInstance.loading).toBeFalse();
    expect(compiled.textContent).toContain('ready');

    tick(3000);
    httpMock.expectNone('/api/projects');
    fixture.destroy();
  }));

  it('a projectStatus FAILED event marks that row failed with no counts (#721)', () => {
    const fixture = init([{ ...PROJECT_A, status: 'CLONING' }]);
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: [], github: GITHUB_OK });
    fixture.detectChanges();

    emitAppEvent({ type: 'projectStatus', projectId: 1, status: 'FAILED' });

    expect(fixture.componentInstance.rows[0].project.status).toBe('FAILED');
    expect(fixture.componentInstance.rows[0].counts).toBeNull();
  });

  it('a projectStatus event for a project not currently loaded is ignored (#721)', () => {
    const fixture = init([PROJECT_A]);
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    fixture.detectChanges();

    emitAppEvent({ type: 'projectStatus', projectId: 999, status: 'READY', defaultBranch: 'main' });

    expect(fixture.componentInstance.rows.length).toBe(1);
  });

  it('a projectDeleted event drops that row (#721, absorbed from #720)', () => {
    const fixture = init([PROJECT_A, PROJECT_B]);
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    httpMock.expectOne('/api/projects/2/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    fixture.detectChanges();

    emitAppEvent({ type: 'projectDeleted', projectId: 1 });

    expect(fixture.componentInstance.rows.map((r) => r.project.id)).toEqual([2]);
  });

  it('shows an error state when the project list fails to load', () => {
    const fixture = TestBed.createComponent(OverviewComponent);
    fixture.detectChanges();
    httpMock.expectOne('/api/projects').flush('boom', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(fixture.componentInstance.error).toBeTrue();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('could not load the workspace overview');
  });

  it('aggregates counts across every project into the stat tiles', () => {
    const fixture = init([PROJECT_A, PROJECT_B]);
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    httpMock.expectOne('/api/projects/2/issues/tree').flush({ nodes: [
      { number: 9, title: 'Only in B', kind: 'TASK', state: 'OPEN', hasActiveBranch: false, labels: [], children: [] },
    ], github: GITHUB_OK });
    fixture.detectChanges();

    expect(fixture.componentInstance.totals).toEqual({ total: 5, open: 3, closed: 2, initiatives: 1, tasks: 4 });
    const values = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.count .value'),
    ).map((el) => el.textContent?.trim());
    expect(values).toEqual(['2', '5', '3', '2', '1', '4']);
  });

  it('links a READY project to its issues page', () => {
    const fixture = init([PROJECT_A]);
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    fixture.detectChanges();

    const link = (fixture.nativeElement as HTMLElement).querySelector<HTMLAnchorElement>('a.project-row');
    expect(link!.getAttribute('href')).toBe('/projects/1/issues');
  });

  it('a non-READY project has no href, even though its (empty) tree is still fetched like project-summary\'s (#85)', () => {
    const cloning: Project = { ...PROJECT_A, status: 'CLONING' };
    const fixture = init([cloning]);
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: [], github: GITHUB_OK });
    fixture.detectChanges();

    const row = (fixture.nativeElement as HTMLElement).querySelector<HTMLAnchorElement>('a.project-row');
    expect(row!.hasAttribute('href')).toBeFalse();
    expect(row!.classList).toContain('disabled');
    expect(row!.querySelector('.project-totals')!.textContent?.trim()).toBe('0 issues');
  });

  it('a project whose tree fails to load still renders, with counts absent', () => {
    const fixture = init([PROJECT_A]);
    httpMock.expectOne('/api/projects/1/issues/tree').error(new ProgressEvent('network error'));
    fixture.detectChanges();

    expect(fixture.componentInstance.error).toBeFalse();
    expect(fixture.componentInstance.rows[0].counts).toBeNull();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('proj-a');
  });

  it('shows a closed/total completion indicator per project', () => {
    const fixture = init([PROJECT_A]);
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    fixture.detectChanges();

    const label = (fixture.nativeElement as HTMLElement).querySelector('.completion-label');
    expect(label!.textContent?.trim()).toBe('2/4 closed');
  });

  it('opens a shell console for a READY project and navigates to it (#256)', () => {
    const fixture = init([PROJECT_A]);
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    fixture.detectChanges();
    const router = TestBed.inject(Router);
    spyOn(router, 'navigate');
    const opened = jasmine.createSpy('onOpened');
    TestBed.inject(ConsolesService).onOpened.subscribe(opened);

    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.shell-btn')!.click();
    const req = httpMock.expectOne('/api/projects/1/console');
    expect(req.request.method).toBe('POST');
    req.flush({ sessionId: '1-console-a1b2c3d4', workingDirectory: '/repo' });

    expect(TestBed.inject(AgentStore).get('1-console-a1b2c3d4')).toBe('shell');
    expect(opened).toHaveBeenCalled();
    expect(router.navigate).toHaveBeenCalledWith(['/projects', 1, 'console'], {
      queryParams: { session: '1-console-a1b2c3d4' },
    });
  });

  it('has no shell button for a project that is not READY', () => {
    const cloning: Project = { ...PROJECT_A, status: 'CLONING' };
    const fixture = init([cloning]);
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: [], github: GITHUB_OK });
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelector('.shell-btn')).toBeFalsy();
  });

  it('guards the shell button against a double click starting two sessions', () => {
    const fixture = init([PROJECT_A]);
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    fixture.detectChanges();

    const button = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.shell-btn')!;
    button.click();
    button.click();

    const req = httpMock.expectOne('/api/projects/1/console');
    expect(req.request.method).toBe('POST');
    req.flush({ sessionId: '1-console-a1b2c3d4', workingDirectory: '/repo' });
  });
});
