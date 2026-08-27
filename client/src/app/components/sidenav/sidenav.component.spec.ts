import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { SidenavComponent } from './sidenav.component';
import { PinStore } from '../../services/pin-store';
import { CollapseStore } from '../../services/collapse-store';
import { ProjectSectionStore } from '../../services/project-section-store';
import { ConsolesService } from '../../services/consoles.service';
import { EventsService } from '../../services/events.service';
import { IssuesService } from '../../services/issues.service';
import { AgentStore } from '../../services/agent-store';
import { Project, TreeNode } from '../../models/issue.model';
import { UsageSnapshot } from '../../models/usage.model';

describe('SidenavComponent', () => {
  let httpMock: HttpTestingController;

  const PROJECT_A: Project = {
    id: 1,
    name: 'proj-a',
    gitUrl: 'url-a',
    workareaPath: '/tmp/a',
    defaultBranch: 'main',
    status: 'READY',
    createdAt: '',
  };
  const PROJECT_B: Project = { ...PROJECT_A, id: 2, name: 'proj-b', gitUrl: 'url-b', workareaPath: '/tmp/b' };

  beforeEach(() => {
    localStorage.removeItem('locklane.pinnedIssues');
    localStorage.removeItem('locklane.collapsedInitiatives');
    localStorage.removeItem('locklane.collapsedProjectSections');
    TestBed.configureTestingModule({
      imports: [SidenavComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  // The usage widget (#137) keeps polling /api/usage on its own timer for as long as
  // it's mounted -- fakeAsync's tick() calls elsewhere in these tests can fast-forward
  // that timer, so a poll this test never asked about may still be outstanding. Drain
  // it here rather than asserting on it in every unrelated test.
  afterEach(() => {
    httpMock.match('/api/usage').forEach((request) => request.flush(EMPTY_USAGE));
    httpMock.verify();
    localStorage.removeItem('locklane.pinnedIssues');
    localStorage.removeItem('locklane.collapsedInitiatives');
    localStorage.removeItem('locklane.collapsedProjectSections');
  });

  function tree(): TreeNode[] {
    return [
      {
        number: 1,
        title: 'Initiative',
        kind: 'INITIATIVE',
        state: 'OPEN',
        hasActiveBranch: false,
        children: [
          { number: 2, title: 'Child A', kind: 'TASK', state: 'OPEN', hasActiveBranch: false, children: [] },
          { number: 3, title: 'Child B', kind: 'TASK', state: 'CLOSED', hasActiveBranch: false, children: [] },
        ],
      },
      { number: 4, title: 'Standalone', kind: 'TASK', state: 'OPEN', hasActiveBranch: false, children: [] },
    ];
  }

  const EMPTY_USAGE: UsageSnapshot = {
    claude: { available: false, fiveHour: null, weekly: null },
    codex: { available: false, fiveHour: null, weekly: null },
    updatedAt: new Date().toISOString(),
  };

  /**
   * Creates the component and flushes its project list, plus the usage widget's own
   * fetch (#137) -- a child of the sidenav that fetches independently of the project
   * list on its own `ngOnInit`, so every test that renders the sidenav owes it a
   * response or `httpMock.verify()` fails on an unflushed request.
   */
  function init(projects: Project[] = [PROJECT_A]): ReturnType<typeof TestBed.createComponent<SidenavComponent>> {
    const fixture = TestBed.createComponent(SidenavComponent);
    fixture.detectChanges();
    httpMock.expectOne('/api/projects').flush(projects);
    httpMock.expectOne('/api/usage').flush(EMPTY_USAGE);
    return fixture;
  }

  function flushTree(projectId: number, nodes: TreeNode[]): void {
    httpMock.expectOne(`/api/projects/${projectId}/issues/tree`).flush(nodes);
    flushConsoles();
  }

  /**
   * The sidenav fetches each loaded project's open consoles to drive its
   * open-console dot (#108), once every project's tree has come back. A no-op
   * when that fetch hasn't fired yet (e.g. a sibling project's tree is still
   * pending).
   */
  function flushConsoles(): void {
    httpMock.match((req) => /\/api\/projects\/\d+\/consoles$/.test(req.url)).forEach((request) => request.flush([]));
  }

  it('loads one section per project from the backend', () => {
    const fixture = init();
    flushTree(1, tree());

    const section = fixture.componentInstance.projectSections[0];
    expect(fixture.componentInstance.mainNodesFor(section)).toHaveSize(2);
    expect(fixture.componentInstance.loading).toBeFalse();
  });

  it('renders one section per project, each with its own tree', () => {
    const fixture = init([PROJECT_A, PROJECT_B]);
    httpMock.expectOne('/api/projects/1/issues/tree').flush(tree());
    httpMock.expectOne('/api/projects/2/issues/tree').flush([
      { number: 9, title: 'Only in B', kind: 'TASK', state: 'OPEN', hasActiveBranch: false, children: [] },
    ]);
    flushConsoles();

    const [sectionA, sectionB] = fixture.componentInstance.projectSections;
    expect(fixture.componentInstance.mainNodesFor(sectionA).map((n) => n.number)).toEqual([1, 4]);
    expect(fixture.componentInstance.mainNodesFor(sectionB).map((n) => n.number)).toEqual([9]);
  });

  it('reports an error state when a tree fetch fails', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/issues/tree').error(new ProgressEvent('network error'));

    expect(fixture.componentInstance.error).toBeTrue();
    expect(fixture.componentInstance.loading).toBeFalse();
  });

  it('renders each issue row, nested children included, as a real link to its issue route (#170)', () => {
    const fixture = init();
    flushTree(1, tree());
    fixture.detectChanges();

    const rows = Array.from(fixture.nativeElement.querySelectorAll('a.row')) as HTMLAnchorElement[];
    // Initiative #1, its open child #2 (closed #3 is hidden by hideShipped), standalone #4.
    expect(rows.map((row) => row.getAttribute('href'))).toEqual([
      '/projects/1/issues/1',
      '/projects/1/issues/2',
      '/projects/1/issues/4',
    ]);
  });

  it('a pinned row is a real link too (#170)', () => {
    const fixture = init();
    flushTree(1, tree());
    TestBed.inject(PinStore).toggle(1, 4);
    fixture.detectChanges();

    const pinnedRow = fixture.nativeElement.querySelector('a.row') as HTMLAnchorElement;
    expect(pinnedRow.getAttribute('href')).toBe('/projects/1/issues/4');
  });

  it('left-clicking a row navigates in-app through the router, not a page load (#170)', () => {
    const fixture = init();
    flushTree(1, tree());
    fixture.detectChanges();
    const navigate = spyOn(TestBed.inject(Router), 'navigateByUrl').and.resolveTo(true);

    const rows = Array.from(fixture.nativeElement.querySelectorAll('a.row')) as HTMLAnchorElement[];
    rows.find((row) => row.getAttribute('href') === '/projects/1/issues/4')!.click();

    expect(navigate).toHaveBeenCalledTimes(1);
    expect(String(navigate.calls.mostRecent().args[0])).toBe('/projects/1/issues/4');
  });

  it('the row twisty, kebab, and pin controls do not trigger navigation (#170)', () => {
    const fixture = init();
    flushTree(1, tree());
    fixture.detectChanges();
    const navigate = spyOn(TestBed.inject(Router), 'navigateByUrl').and.resolveTo(true);

    (fixture.nativeElement.querySelector('a.row .twist') as HTMLElement).click();
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('a.row .kebab') as HTMLElement).click();
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('a.row .menu button') as HTMLElement).click();

    expect(navigate).not.toHaveBeenCalled();
  });

  it('isSelected only matches the exact project/issue pair', () => {
    const fixture = init([PROJECT_A, PROJECT_B]);
    httpMock.expectOne('/api/projects/1/issues/tree').flush(tree());
    httpMock.expectOne('/api/projects/2/issues/tree').flush(tree());
    flushConsoles();
    fixture.componentInstance.selected = { projectId: 1, issueNumber: 4 };

    expect(fixture.componentInstance.isSelected(1, 4)).toBeTrue();
    expect(fixture.componentInstance.isSelected(2, 4)).toBeFalse();
  });

  it('a pinned issue moves out of mainNodesFor and into pinnedGroups', () => {
    const fixture = init();
    flushTree(1, tree());

    TestBed.inject(PinStore).toggle(1, 4);
    fixture.detectChanges();

    const section = fixture.componentInstance.projectSections[0];
    expect(fixture.componentInstance.pinnedGroups[0].nodes.map((n) => n.number)).toEqual([4]);
    expect(fixture.componentInstance.mainNodesFor(section).map((n) => n.number)).toEqual([1]);
  });

  it('a pin in one project does not pin the same issue number in another project', () => {
    const fixture = init([PROJECT_A, PROJECT_B]);
    httpMock.expectOne('/api/projects/1/issues/tree').flush(tree());
    httpMock.expectOne('/api/projects/2/issues/tree').flush(tree());
    flushConsoles();

    TestBed.inject(PinStore).toggle(1, 4);
    fixture.detectChanges();

    expect(fixture.componentInstance.pinnedGroups).toHaveSize(1);
    expect(fixture.componentInstance.pinnedGroups[0].project.id).toBe(1);
  });

  it('pinning a child task removes it from its unpinned parent in mainNodesFor too', () => {
    const fixture = init();
    flushTree(1, tree());
    fixture.componentInstance.hideShipped = false;

    TestBed.inject(PinStore).toggle(1, 2); // pin child A; initiative #1 stays unpinned

    const section = fixture.componentInstance.projectSections[0];
    const initiativeInMain = fixture.componentInstance.mainNodesFor(section).find((n) => n.number === 1)!;
    expect(initiativeInMain.children.map((c) => c.number)).toEqual([3]); // 2 moved to Pinned
    expect(fixture.componentInstance.pinnedGroups[0].nodes.map((n) => n.number)).toEqual([2]);
  });

  it('a pinned child is excluded from its pinned parent to avoid duplication', () => {
    const fixture = init();
    flushTree(1, tree());

    fixture.componentInstance.hideShipped = false; // isolate de-duplication from ship-filtering
    const pins = TestBed.inject(PinStore);
    pins.toggle(1, 1); // pin the initiative
    pins.toggle(1, 2); // also pin one of its children
    fixture.detectChanges();

    const pinned = fixture.componentInstance.pinnedGroups[0].nodes;
    expect(pinned.map((n) => n.number)).toEqual([2, 1]); // most-recently-pinned first
    const initiativeEntry = pinned.find((n) => n.number === 1)!;
    expect(initiativeEntry.children.map((c) => c.number)).toEqual([3]); // 2 removed, not duplicated
  });

  it('hideShipped never removes a pinned entry itself, even if it is closed', () => {
    const fixture = init();
    flushTree(1, tree());

    TestBed.inject(PinStore).toggle(1, 3); // pin the CLOSED child directly
    fixture.detectChanges();

    expect(fixture.componentInstance.hideShipped).toBeTrue();
    expect(fixture.componentInstance.pinnedGroups[0].nodes.map((n) => n.number)).toEqual([3]);
  });

  it('isCollapsed reflects CollapseStore, but an active filter always shows children', () => {
    const fixture = init();
    flushTree(1, tree());
    const section = fixture.componentInstance.projectSections[0];
    const initiative = fixture.componentInstance.mainNodesFor(section)[0];

    TestBed.inject(CollapseStore).toggle(1, 1);
    expect(fixture.componentInstance.isCollapsed(1, initiative)).toBeTrue();

    fixture.componentInstance.filterText = 'child';
    expect(fixture.componentInstance.isCollapsed(1, initiative)).toBeFalse();
  });

  it('a fold in one project does not fold the same issue number in another project', () => {
    const fixture = init([PROJECT_A, PROJECT_B]);
    httpMock.expectOne('/api/projects/1/issues/tree').flush(tree());
    httpMock.expectOne('/api/projects/2/issues/tree').flush(tree());
    flushConsoles();
    const [sectionA] = fixture.componentInstance.projectSections;
    const initiative = fixture.componentInstance.mainNodesFor(sectionA)[0];

    TestBed.inject(CollapseStore).toggle(1, 1);

    expect(fixture.componentInstance.isCollapsed(1, initiative)).toBeTrue();
    expect(fixture.componentInstance.isCollapsed(2, initiative)).toBeFalse();
  });

  it('toggling a project section folds and unfolds it, and persists via ProjectSectionStore', () => {
    const fixture = init();
    flushTree(1, tree());

    expect(fixture.componentInstance.isProjectCollapsed(1)).toBeFalse();
    fixture.componentInstance.toggleProjectCollapse(1, new Event('click'));

    expect(fixture.componentInstance.isProjectCollapsed(1)).toBeTrue();
    expect(TestBed.inject(ProjectSectionStore).isCollapsed(1)).toBeTrue();
  });

  it('hideShipped is on by default and hides the closed child', () => {
    const fixture = init();
    flushTree(1, tree());

    expect(fixture.componentInstance.hideShipped).toBeTrue();
    const section = fixture.componentInstance.projectSections[0];
    const initiative = fixture.componentInstance.mainNodesFor(section)[0];
    expect(initiative.children.map((c) => c.number)).toEqual([2]);
  });

  it('refresh() re-fetches everything and updates the list in place', () => {
    const fixture = init();
    flushTree(1, tree());

    fixture.componentInstance.refresh();
    expect(fixture.componentInstance.refreshing).toBeTrue();

    httpMock.expectOne('/api/projects').flush([PROJECT_A]);
    const updated: TreeNode[] = [
      ...tree(),
      { number: 5, title: 'New from GitHub', kind: 'TASK', state: 'OPEN', hasActiveBranch: false, children: [] },
    ];
    flushTree(1, updated);

    expect(fixture.componentInstance.refreshing).toBeFalse();
    const section = fixture.componentInstance.projectSections[0];
    expect(fixture.componentInstance.mainNodesFor(section).map((n) => n.number)).toEqual([1, 4, 5]);
  });

  it('refresh() is a no-op while a refresh is already in flight', () => {
    const fixture = init();
    flushTree(1, tree());

    fixture.componentInstance.refresh();
    fixture.componentInstance.refresh();

    // Only one in-flight request pair: the second refresh() call was a no-op.
    httpMock.expectOne('/api/projects').flush([PROJECT_A]);
    flushTree(1, tree());
    expect(fixture.componentInstance.refreshing).toBeFalse();
  });

  it('refresh() surfaces an error without clearing the existing list', () => {
    const fixture = init();
    flushTree(1, tree());

    fixture.componentInstance.refresh();
    httpMock.expectOne('/api/projects').flush([PROJECT_A]);
    httpMock.expectOne('/api/projects/1/issues/tree').error(new ProgressEvent('network error'));

    expect(fixture.componentInstance.refreshing).toBeFalse();
    expect(fixture.componentInstance.error).toBeTrue();
    const section = fixture.componentInstance.projectSections[0];
    expect(fixture.componentInstance.mainNodesFor(section).map((n) => n.number)).toEqual([1, 4]);
  });

  it('openAddProject shows the popup, onAddProjectClosed hides it', () => {
    const fixture = init();
    flushTree(1, tree());

    expect(fixture.componentInstance.showAddProject).toBeFalse();
    fixture.componentInstance.openAddProject();
    expect(fixture.componentInstance.showAddProject).toBeTrue();

    fixture.componentInstance.onAddProjectClosed();
    expect(fixture.componentInstance.showAddProject).toBeFalse();
  });

  it('onProjectCreated hides the popup and refreshes the project list', () => {
    const fixture = init();
    flushTree(1, tree());
    fixture.componentInstance.openAddProject();

    fixture.componentInstance.onProjectCreated();

    expect(fixture.componentInstance.showAddProject).toBeFalse();
    httpMock.expectOne('/api/projects').flush([PROJECT_A]);
    flushTree(1, tree());
  });

  it('a project still cloning shows a cloning state instead of its tree', () => {
    const cloning: Project = { ...PROJECT_A, status: 'CLONING' };
    const fixture = init([cloning]);
    flushTree(1, tree());

    const section = fixture.componentInstance.projectSections[0];
    expect(section.project.status).toBe('CLONING');
  });

  it('polls again while a project is still cloning, and stops once it settles', fakeAsync(() => {
    const cloning: Project = { ...PROJECT_A, status: 'CLONING' };
    const fixture = init([cloning]);
    flushTree(1, tree());

    tick(3000);
    httpMock.expectOne('/api/projects').flush([{ ...PROJECT_A, status: 'READY' }]);
    flushTree(1, tree());

    tick(3000);
    httpMock.expectNone('/api/projects');

    fixture.destroy();
  }));

  it('does not poll once every project is already settled', fakeAsync(() => {
    init();
    flushTree(1, tree());

    tick(3000);
    httpMock.expectNone('/api/projects');
  }));

  it('stops polling once the component is destroyed', fakeAsync(() => {
    const cloning: Project = { ...PROJECT_A, status: 'CLONING' };
    const fixture = init([cloning]);
    flushTree(1, tree());

    fixture.destroy();
    tick(3000);

    httpMock.expectNone('/api/projects');
  }));

  it('retryProject calls the retry endpoint and refreshes', () => {
    const fixture = init();
    flushTree(1, tree());

    fixture.componentInstance.retryProject(1, new Event('click'));

    httpMock.expectOne('/api/projects/1/retry').flush({ ...PROJECT_A });
    httpMock.expectOne('/api/projects').flush([PROJECT_A]);
    flushTree(1, tree());
  });

  it('deleteProject asks for confirmation, and on confirm calls delete and refreshes', () => {
    spyOn(window, 'confirm').and.returnValue(true);
    const fixture = init();
    flushTree(1, tree());

    fixture.componentInstance.deleteProject(1, new Event('click'));

    expect(window.confirm).toHaveBeenCalled();
    httpMock.expectOne('/api/projects/1').flush(null);
    httpMock.expectOne('/api/projects').flush([]);
  });

  it('deleteProject does nothing when the confirmation is declined', () => {
    spyOn(window, 'confirm').and.returnValue(false);
    const fixture = init();
    flushTree(1, tree());

    fixture.componentInstance.deleteProject(1, new Event('click'));

    httpMock.expectNone('/api/projects/1');
  });

  it('clicking a project header emits the project, without folding it (#85)', () => {
    const fixture = init();
    flushTree(1, tree());
    fixture.detectChanges();
    const emitted: number[] = [];
    fixture.componentInstance.projectSelected.subscribe((id) => emitted.push(id));

    const header = fixture.nativeElement.querySelector('.section-header') as HTMLElement;
    header.click();

    expect(emitted).toEqual([1]);
    expect(fixture.componentInstance.isProjectCollapsed(1)).toBeFalse();
  });

  it('the twisty still folds the section without selecting the project (#85)', () => {
    const fixture = init();
    flushTree(1, tree());
    fixture.detectChanges();
    const emitted: number[] = [];
    fixture.componentInstance.projectSelected.subscribe((id) => emitted.push(id));

    const twist = fixture.nativeElement.querySelector('.section-header .twist') as HTMLElement;
    twist.click();

    expect(fixture.componentInstance.isProjectCollapsed(1)).toBeTrue();
    expect(emitted).toEqual([]);
  });

  it('the Overview entry links to "/" (#197)', () => {
    const fixture = init();
    flushTree(1, tree());
    fixture.detectChanges();

    const link = fixture.nativeElement.querySelector('a.overview-entry') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/');
  });

  it('the Overview entry is only active when nothing else is selected (#197)', () => {
    const fixture = init();
    flushTree(1, tree());
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('a.overview-entry').classList).toContain('active');

    fixture.componentInstance.selectedProject = 1;
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('a.overview-entry').classList).not.toContain('active');
  });

  it('marks only the selected project header as active (#85)', () => {
    const fixture = init([PROJECT_A, PROJECT_B]);
    flushTree(1, tree());
    flushTree(2, tree());
    fixture.componentInstance.selectedProject = 2;
    fixture.detectChanges();

    expect(fixture.componentInstance.isProjectSelected(1)).toBeFalse();
    expect(fixture.componentInstance.isProjectSelected(2)).toBeTrue();
    const active = fixture.nativeElement.querySelectorAll('.section-header.active');
    expect(active.length).toBe(1);
  });

  /** Reaches past EventsService's public API (#129) -- there is no other way to fake an incoming socket message. */
  function emitAppEvent(event: unknown): void {
    (TestBed.inject(EventsService) as unknown as { eventsSubject: { next: (e: unknown) => void } }).eventsSubject.next(
      event,
    );
  }

  function emitReconnected(): void {
    (TestBed.inject(EventsService) as unknown as { reconnectedSubject: { next: () => void } }).reconnectedSubject.next();
  }

  it('an issuesChanged event re-fetches just that project\'s tree, in place', () => {
    const fixture = init([PROJECT_A, PROJECT_B]);
    httpMock.expectOne('/api/projects/1/issues/tree').flush(tree());
    httpMock.expectOne('/api/projects/2/issues/tree').flush(tree());
    flushConsoles();

    emitAppEvent({ type: 'issuesChanged', projectId: 1 });

    httpMock.expectNone('/api/projects'); // notify-then-fetch, not a full reload
    const updated: TreeNode[] = [
      ...tree(),
      { number: 5, title: 'New from GitHub', kind: 'TASK', state: 'OPEN', hasActiveBranch: false, children: [] },
    ];
    httpMock.expectOne('/api/projects/1/issues/tree').flush(updated);
    flushConsoles();

    const [sectionA, sectionB] = fixture.componentInstance.projectSections;
    expect(fixture.componentInstance.mainNodesFor(sectionA).map((n) => n.number)).toEqual([1, 4, 5]);
    expect(fixture.componentInstance.mainNodesFor(sectionB).map((n) => n.number)).toEqual([1, 4]);
  });

  it('an issuesChanged event for a project not currently loaded is ignored', () => {
    init();
    flushTree(1, tree());

    emitAppEvent({ type: 'issuesChanged', projectId: 999 });

    httpMock.expectNone('/api/projects/999/issues/tree');
  });

  it('a project-stale notification (#140) re-fetches that project\'s tree with fresh=true', () => {
    init();
    flushTree(1, tree());

    TestBed.inject(IssuesService).notifyProjectStale(1);

    const req = httpMock.expectOne((r) => r.url === '/api/projects/1/issues/tree');
    expect(req.request.params.get('fresh')).toBe('true');
    req.flush(tree());
    flushConsoles();
  });

  it('a project-stale notification for a project not currently loaded is ignored', () => {
    init();
    flushTree(1, tree());

    TestBed.inject(IssuesService).notifyProjectStale(999);

    httpMock.expectNone((r) => r.url === '/api/projects/999/issues/tree');
  });

  it('a reconnect does one full reload to catch up on missed events', () => {
    const fixture = init();
    flushTree(1, tree());

    emitReconnected();

    httpMock.expectOne('/api/projects').flush([PROJECT_A]);
    const updated: TreeNode[] = [
      ...tree(),
      { number: 5, title: 'New from GitHub', kind: 'TASK', state: 'OPEN', hasActiveBranch: false, children: [] },
    ];
    flushTree(1, updated);

    const section = fixture.componentInstance.projectSections[0];
    expect(fixture.componentInstance.mainNodesFor(section).map((n) => n.number)).toEqual([1, 4, 5]);
  });

  it('a consoleAttention waiting event marks that issue, an active event clears it (#130)', () => {
    const fixture = init();
    flushTree(1, tree());

    emitAppEvent({ type: 'consoleAttention', sessionId: '1-4-main-slug', state: 'waiting' });
    expect(fixture.componentInstance.hasAttentionWaiting(1, 4)).toBeTrue();
    expect(fixture.componentInstance.hasAttentionWaiting(1, 2)).toBeFalse();

    emitAppEvent({ type: 'consoleAttention', sessionId: '1-4-main-slug', state: 'active' });
    expect(fixture.componentInstance.hasAttentionWaiting(1, 4)).toBeFalse();
  });

  it('a consoleAttention event only marks the matching project (#130)', () => {
    const fixture = init([PROJECT_A, PROJECT_B]);
    httpMock.expectOne('/api/projects/1/issues/tree').flush(tree());
    httpMock.expectOne('/api/projects/2/issues/tree').flush(tree());
    flushConsoles();

    emitAppEvent({ type: 'consoleAttention', sessionId: '2-4-main-slug', state: 'waiting' });

    expect(fixture.componentInstance.hasAttentionWaiting(1, 4)).toBeFalse();
    expect(fixture.componentInstance.hasAttentionWaiting(2, 4)).toBeTrue();
  });

  /** The header's rendered text with whitespace collapsed, e.g. "proj-a (3)". */
  function headerText(fixture: { nativeElement: HTMLElement }): string {
    const label = fixture.nativeElement.querySelector('.section-header .project-label') as HTMLElement;
    return label.textContent!.trim().replace(/\s+/g, ' ');
  }

  it('the project header shows the open-issue count after the name (#186)', () => {
    const fixture = init();
    flushTree(1, tree());
    fixture.detectChanges();

    // Open: initiative #1, child #2, standalone #4. Closed child #3 is not counted.
    expect(headerText(fixture)).toBe('proj-a (3)');
  });

  it('the count ignores the text filter and the opened-issues toggle (#186)', () => {
    const fixture = init();
    flushTree(1, tree());

    fixture.componentInstance.filterText = 'no row matches this';
    fixture.componentInstance.hideShipped = false;
    fixture.detectChanges();

    expect(headerText(fixture)).toBe('proj-a (3)');
  });

  it('the count updates when an issuesChanged event refreshes the tree (#186)', () => {
    const fixture = init();
    flushTree(1, tree());

    emitAppEvent({ type: 'issuesChanged', projectId: 1 });
    httpMock.expectOne('/api/projects/1/issues/tree').flush([
      ...tree(),
      { number: 5, title: 'New from GitHub', kind: 'TASK', state: 'OPEN', hasActiveBranch: false, children: [] },
    ]);
    flushConsoles();
    fixture.detectChanges();

    expect(headerText(fixture)).toBe('proj-a (4)');
  });

  it('a project still cloning shows no count (#186)', () => {
    const cloning: Project = { ...PROJECT_A, status: 'CLONING' };
    const fixture = init([cloning]);
    flushTree(1, tree());
    fixture.detectChanges();

    expect(headerText(fixture)).toBe('proj-a');
    expect(fixture.nativeElement.querySelector('.section-header .issue-count')).toBeNull();
  });

  it('the pinned section\'s project name line carries no count (#186)', () => {
    const fixture = init();
    flushTree(1, tree());
    TestBed.inject(PinStore).toggle(1, 4);
    fixture.detectChanges();

    const pinnedName = fixture.nativeElement.querySelector('.project-name') as HTMLElement;
    expect(pinnedName.textContent!.trim()).toBe('proj-a');
  });

  it('the header "+" mints a new console and lands on it, without selecting the project (#180)', () => {
    localStorage.removeItem('locklane.sessionAgents');
    const fixture = init();
    flushTree(1, tree());
    fixture.detectChanges();
    const navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
    const emitted: number[] = [];
    fixture.componentInstance.projectSelected.subscribe((id) => emitted.push(id));
    const opened = jasmine.createSpy('onOpened');
    TestBed.inject(ConsolesService).onOpened.subscribe(opened);

    (fixture.nativeElement.querySelector('.section-header .new-console') as HTMLElement).click();
    httpMock
      .expectOne({ method: 'POST', url: '/api/projects/1/console' })
      .flush({ sessionId: 'proj-1-console-abc', workingDirectory: '/tmp/a' });
    flushConsoles();

    expect(navigate).toHaveBeenCalledWith(['/projects', 1, 'console'], {
      queryParams: { session: 'proj-1-console-abc' },
    });
    expect(emitted).toEqual([]);
    // The one-click entry has no agent picker: the new console gets the default agent.
    expect(TestBed.inject(AgentStore).get('proj-1-console-abc')).toBe('claude');
    // #194: the header consoles widget must learn about it, the same way any other
    // newly opened console is announced.
    expect(opened).toHaveBeenCalled();
    localStorage.removeItem('locklane.sessionAgents');
  });

  it('the "+" ignores further clicks while a console is still being minted (#180)', () => {
    const fixture = init();
    flushTree(1, tree());
    fixture.detectChanges();
    spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);

    const plus = fixture.nativeElement.querySelector('.section-header .new-console') as HTMLElement;
    plus.click();
    plus.click();

    // Only one mint in flight: the second click was a no-op.
    httpMock
      .expectOne({ method: 'POST', url: '/api/projects/1/console' })
      .flush({ sessionId: 'proj-1-console-abc', workingDirectory: '/tmp/a' });
    flushConsoles();
    localStorage.removeItem('locklane.sessionAgents');
  });

  it('a failed mint re-arms the "+" instead of leaving it stuck (#180)', () => {
    const fixture = init();
    flushTree(1, tree());
    fixture.detectChanges();
    const navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);

    const plus = fixture.nativeElement.querySelector('.section-header .new-console') as HTMLElement;
    plus.click();
    httpMock
      .expectOne({ method: 'POST', url: '/api/projects/1/console' })
      .error(new ProgressEvent('network error'));
    expect(navigate).not.toHaveBeenCalled();

    plus.click();
    httpMock
      .expectOne({ method: 'POST', url: '/api/projects/1/console' })
      .flush({ sessionId: 'proj-1-console-abc', workingDirectory: '/tmp/a' });
    flushConsoles();
    expect(navigate).toHaveBeenCalled();
    localStorage.removeItem('locklane.sessionAgents');
  });

  it('a project that is not READY has no "+" (#180)', () => {
    const cloning: Project = { ...PROJECT_A, status: 'CLONING' };
    const fixture = init([cloning]);
    flushTree(1, tree());
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.section-header .new-console')).toBeNull();
  });

  it('the project name is not indented further than an issue row (#85)', () => {
    const fixture = init();
    flushTree(1, tree());
    fixture.detectChanges();

    const header = fixture.nativeElement.querySelector('.section-header') as HTMLElement;
    const row = fixture.nativeElement.querySelector('.row') as HTMLElement;
    const left = (el: HTMLElement) => parseFloat(getComputedStyle(el).paddingLeft);
    expect(left(header)).toBeLessThanOrEqual(left(row));
  });
});
