import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { CdkDragDrop } from '@angular/cdk/drag-drop';
import { Section, SidenavComponent } from './sidenav.component';
import { PinStore } from '../../services/pin-store';
import { CollapseStore } from '../../services/collapse-store';
import { ProjectSectionStore } from '../../services/project-section-store';
import { EventsService } from '../../services/events.service';
import { IssuesService } from '../../services/issues.service';
import { Project, TreeNode } from '../../models/issue.model';
import { UsageSnapshot } from '../../models/usage.model';

describe('SidenavComponent', () => {

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

  beforeEach(() => {
    localStorage.removeItem('locklane.pinnedIssues');
    localStorage.removeItem('locklane.collapsedInitiatives');
    localStorage.removeItem('locklane.collapsedProjectSections');
    TestBed.configureTestingModule({
      imports: [SidenavComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
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
        labels: [],
        children: [
          { number: 2, title: 'Child A', kind: 'TASK', state: 'OPEN', hasActiveBranch: false, labels: [], children: [] },
          { number: 3, title: 'Child B', kind: 'TASK', state: 'CLOSED', hasActiveBranch: false, labels: [], children: [] },
        ],
      },
      { number: 4, title: 'Standalone', kind: 'TASK', state: 'OPEN', hasActiveBranch: false, labels: [], children: [] },
    ];
  }

  const EMPTY_USAGE: UsageSnapshot = {
    providers: [],
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

  function flushTree(projectId: number, nodes: TreeNode[], fresh = false): void {
    httpMock
      .expectOne(`/api/projects/${projectId}/issues/tree${fresh ? '?fresh=true' : ''}`)
      .flush({ nodes: nodes, github: GITHUB_OK });
    flushConsoles();
  }

  /**
   * The sidenav fetches each listed project's open consoles to drive its
   * open-console dot (#108) as soon as the project list arrives (#787), and again
   * after an event-driven re-fetch. A no-op when no such fetch is outstanding.
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
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    httpMock.expectOne('/api/projects/2/issues/tree').flush({ nodes: [
      { number: 9, title: 'Only in B', kind: 'TASK', state: 'OPEN', hasActiveBranch: false, labels: [], children: [] },
    ], github: GITHUB_OK });
    flushConsoles();

    const [sectionA, sectionB] = fixture.componentInstance.projectSections;
    expect(fixture.componentInstance.mainNodesFor(sectionA).map((n) => n.number)).toEqual([1, 4]);
    expect(fixture.componentInstance.mainNodesFor(sectionB).map((n) => n.number)).toEqual([9]);
  });

  it('a failed tree fetch marks that project failed, not the whole sidenav (#787)', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/issues/tree').error(new ProgressEvent('network error'));
    flushConsoles();
    fixture.detectChanges();

    expect(fixture.componentInstance.error).toBeFalse();
    expect(fixture.componentInstance.loading).toBeFalse();
    expect(fixture.componentInstance.projectSections[0].treeState).toBe('failed');
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.project-section[data-project-id="1"] .tree-error')?.textContent).toContain(
      'could not load issues',
    );
    expect(compiled.querySelector('.section-header .issue-count')).toBeNull();
  });

  it('renders each project as soon as its own tree lands, in project-list order, whatever order the responses arrive in (#787)', () => {
    const fixture = init([PROJECT_A, PROJECT_B]);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    const ids = () => fixture.componentInstance.projectSections.map((s) => s.project.id);
    const states = () => fixture.componentInstance.projectSections.map((s) => s.treeState);
    const hrefs = () => Array.from(compiled.querySelectorAll('a.row')).map((row) => row.getAttribute('href'));

    // Both sections exist, in list order, before any tree has come back -- each
    // showing its own loading state rather than the sidenav-wide one.
    expect(fixture.componentInstance.loading).toBeFalse();
    expect(ids()).toEqual([1, 2]);
    expect(states()).toEqual(['loading', 'loading']);
    expect(compiled.querySelectorAll('.project-section .tree-loading')).toHaveSize(2);
    expect(hrefs()).toEqual([]);

    // B answers first: its rows render while A is still loading, and A stays first.
    httpMock.expectOne('/api/projects/2/issues/tree').flush({ nodes: [
      { number: 9, title: 'Only in B', kind: 'TASK', state: 'OPEN', hasActiveBranch: false, labels: [], children: [] },
    ], github: GITHUB_OK });
    fixture.detectChanges();
    expect(ids()).toEqual([1, 2]);
    expect(states()).toEqual(['loading', 'loaded']);
    expect(hrefs()).toEqual(['/projects/2/issues/9']);
    expect(compiled.querySelectorAll('.project-section .tree-loading')).toHaveSize(1);
    expect(compiled.querySelector('.project-section[data-project-id="1"] .tree-loading')).toBeTruthy();
    // The open-issue count (#186) appears only once that project's tree is in.
    expect(compiled.querySelector('.project-section[data-project-id="1"] .issue-count')).toBeNull();
    expect(compiled.querySelector('.project-section[data-project-id="2"] .issue-count')?.textContent).toBe('(1)');

    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    flushConsoles();
    fixture.detectChanges();
    expect(ids()).toEqual([1, 2]);
    expect(states()).toEqual(['loaded', 'loaded']);
    expect(hrefs()).toEqual(['/projects/1/issues/1', '/projects/1/issues/2', '/projects/1/issues/4', '/projects/2/issues/9']);
    expect(compiled.querySelectorAll('.project-section .tree-loading')).toHaveSize(0);
  });

  it("one project's failed tree fetch leaves the others' loaded sections untouched (#787)", () => {
    const fixture = init([PROJECT_A, PROJECT_B]);
    httpMock.expectOne('/api/projects/2/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    httpMock.expectOne('/api/projects/1/issues/tree').error(new ProgressEvent('network error'));
    flushConsoles();
    fixture.detectChanges();

    expect(fixture.componentInstance.error).toBeFalse();
    expect(fixture.componentInstance.loading).toBeFalse();
    const [sectionA, sectionB] = fixture.componentInstance.projectSections;
    expect(sectionA.treeState).toBe('failed');
    expect(sectionB.treeState).toBe('loaded');
    expect(fixture.componentInstance.mainNodesFor(sectionB).map((n) => n.number)).toEqual([1, 4]);

    const compiled = fixture.nativeElement as HTMLElement;
    const errors = compiled.querySelectorAll('.tree-error');
    expect(errors).toHaveSize(1);
    expect(errors[0].closest('.project-section')?.getAttribute('data-project-id')).toBe('1');
    const hrefs = Array.from(compiled.querySelectorAll('a.row')).map((row) => row.getAttribute('href'));
    expect(hrefs).toEqual(['/projects/2/issues/1', '/projects/2/issues/2', '/projects/2/issues/4']);
    // Not the sidenav-wide error state: that one replaces every section.
    expect(compiled.querySelectorAll('.project-section')).toHaveSize(2);
  });

  it('a project new to the list shows its own loading state while the rest keep their rows (#787)', () => {
    const fixture = init([PROJECT_A]);
    flushTree(1, tree());
    fixture.detectChanges();

    emitAppEvent({ type: 'projectCreated', projectId: 2 });
    httpMock.expectOne('/api/projects').flush([PROJECT_A, PROJECT_B]);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(fixture.componentInstance.projectSections.map((s) => s.treeState)).toEqual(['loaded', 'loading']);
    // A's rows never flash back to a loading state while its new tree is out.
    expect(compiled.querySelectorAll('.project-section[data-project-id="1"] a.row')).toHaveSize(3);
    expect(compiled.querySelector('.project-section[data-project-id="1"] .tree-loading')).toBeNull();
    expect(compiled.querySelector('.project-section[data-project-id="2"] .tree-loading')).toBeTruthy();

    flushTree(2, tree());
    flushTree(1, tree());
  });

  it("a focused window renders its one project as soon as that project's tree lands (#286, #787)", () => {
    const fixture = TestBed.createComponent(SidenavComponent);
    fixture.componentInstance.focusedProjectId = 2;
    fixture.detectChanges();
    httpMock.expectOne('/api/projects').flush([PROJECT_A, PROJECT_B]);
    httpMock.expectOne('/api/usage').flush(EMPTY_USAGE);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(fixture.componentInstance.projectSections.map((s) => s.project.id)).toEqual([2]);
    expect(fixture.componentInstance.projectSections[0].treeState).toBe('loading');
    expect(compiled.querySelectorAll('.project-section .tree-loading')).toHaveSize(1);
    httpMock.expectNone('/api/projects/1/issues/tree');

    httpMock.expectOne('/api/projects/2/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    flushConsoles();
    fixture.detectChanges();
    const hrefs = Array.from(compiled.querySelectorAll('a.row')).map((row) => row.getAttribute('href'));
    expect(hrefs).toEqual(['/projects/2/issues/1', '/projects/2/issues/2', '/projects/2/issues/4']);
    httpMock.expectNone('/api/projects/1/issues/tree');
  });

  it("the selected row is focused once its project's tree lands, even while a sibling is still loading (#747, #787)", fakeAsync(() => {
    const fixture = init([PROJECT_A, PROJECT_B]);
    fixture.componentInstance.selected = { projectId: 2, issueNumber: 4 };
    tick(); // nothing to focus yet -- the row's tree has not arrived

    httpMock.expectOne('/api/projects/2/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    fixture.detectChanges();
    tick();

    const row = fixture.nativeElement.querySelector('a.row[data-project-id="2"][data-issue-number="4"]');
    expect(row).toBeTruthy();
    expect(document.activeElement).toBe(row);
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    flushConsoles();
  }));

  it('open-agent dots are fetched as soon as the list arrives, so rows carry them when they render (#108, #787)', () => {
    const fixture = init([PROJECT_A]);
    // The consoles request is already out before the tree has come back.
    httpMock.expectOne((req) => /\/api\/projects\/1\/consoles$/.test(req.url)).flush(['1-4-standalone']);
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    fixture.detectChanges();

    expect(fixture.componentInstance.hasOpenConsole(1, 4)).toBeTrue();
    expect(fixture.nativeElement.querySelector('a.row[data-issue-number="4"] .console-dot')).toBeTruthy();
  });

  it('a githubRefreshStatus event that lands before the tree does shows on that project alone (#619, #787)', () => {
    const fixture = init([PROJECT_A, PROJECT_B]);
    emitAppEvent({ type: 'githubRefreshStatus', projectId: 1, failing: true, failure: 'gh exited 1: HTTP 401' });
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.project-section[data-project-id="1"] .github-error')?.textContent).toContain('HTTP 401');
    expect(compiled.querySelector('.project-section[data-project-id="2"] .github-error')).toBeNull();

    const failing = { failing: true, failure: 'gh exited 1: HTTP 401', lastSuccessAt: null };
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: tree(), github: failing });
    httpMock.expectOne('/api/projects/2/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    flushConsoles();
    fixture.detectChanges();
    expect(compiled.querySelectorAll('.github-error')).toHaveSize(1);
    expect(compiled.querySelector('.project-section[data-project-id="1"] .github-error')).toBeTruthy();
  });

  it('a tree response from a load whose list a later reload has already replaced is dropped (#787)', () => {
    const fixture = init([PROJECT_A]);
    flushTree(1, tree());

    fixture.componentInstance.refresh();
    httpMock.expectOne('/api/projects').flush([PROJECT_A]);
    const stale = httpMock.expectOne('/api/projects/1/issues/tree?fresh=true');

    // A reconnect reloads directly (not through refresh()), rebuilding the list
    // while the refresh's own tree request is still out.
    emitReconnected();
    httpMock.expectOne('/api/projects').flush([PROJECT_A]);
    const updated: TreeNode[] = [
      ...tree(),
      { number: 5, title: 'New from GitHub', kind: 'TASK', state: 'OPEN', hasActiveBranch: false, labels: [], children: [] },
    ];
    flushTree(1, updated);
    expect(fixture.componentInstance.mainNodesFor(fixture.componentInstance.projectSections[0]).map((n) => n.number)).toEqual([1, 4, 5]);

    // The older response lands last and must not overwrite the newer tree -- but it
    // still settles the refresh it belonged to.
    expect(fixture.componentInstance.refreshing).toBeTrue();
    stale.flush({ nodes: tree(), github: GITHUB_OK });
    expect(fixture.componentInstance.mainNodesFor(fixture.componentInstance.projectSections[0]).map((n) => n.number)).toEqual([1, 4, 5]);
    expect(fixture.componentInstance.refreshing).toBeFalse();
  });

  it('a failed event-driven re-fetch marks that project failed while keeping its tree, and the next success clears it (#787)', () => {
    const fixture = init();
    flushTree(1, tree());

    emitAppEvent({ type: 'issuesChanged', projectId: 1 });
    httpMock.expectOne('/api/projects/1/issues/tree').error(new ProgressEvent('network error'));
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(fixture.componentInstance.projectSections[0].treeState).toBe('failed');
    expect(compiled.querySelector('.tree-error')).toBeTruthy();
    expect(compiled.querySelectorAll('a.row')).toHaveSize(3);

    emitAppEvent({ type: 'issuesChanged', projectId: 1 });
    flushTree(1, tree());
    fixture.detectChanges();
    expect(fixture.componentInstance.projectSections[0].treeState).toBe('loaded');
    expect(compiled.querySelector('.tree-error')).toBeNull();
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

  it('selecting a row moves DOM focus onto it (#747)', fakeAsync(() => {
    const fixture = init();
    flushTree(1, tree());
    fixture.detectChanges();

    fixture.componentInstance.selected = { projectId: 1, issueNumber: 4 };
    tick();

    const row = fixture.nativeElement.querySelector('a.row[data-issue-number="4"]');
    expect(document.activeElement).toBe(row);
  }));

  it('ArrowDown moves focus and drives the same navigation as clicking the next row (#747)', fakeAsync(() => {
    const fixture = init();
    flushTree(1, tree());
    fixture.detectChanges();
    const navigate = spyOn(TestBed.inject(Router), 'navigateByUrl').and.resolveTo(true);

    // Render order: initiative #1, its open child #2, standalone #4 (#3 hidden by hideShipped).
    const rows = Array.from(fixture.nativeElement.querySelectorAll('a.row')) as HTMLAnchorElement[];
    rows[0].focus();
    rows[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true }));

    expect(document.activeElement).toBe(rows[1]);
    expect(navigate).toHaveBeenCalledTimes(1);
    expect(String(navigate.calls.mostRecent().args[0])).toBe('/projects/1/issues/2');
  }));

  it('ArrowUp moves focus and drives the same navigation as clicking the previous row (#747)', fakeAsync(() => {
    const fixture = init();
    flushTree(1, tree());
    fixture.detectChanges();
    const navigate = spyOn(TestBed.inject(Router), 'navigateByUrl').and.resolveTo(true);

    const rows = Array.from(fixture.nativeElement.querySelectorAll('a.row')) as HTMLAnchorElement[];
    rows[1].focus();
    rows[1].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true, cancelable: true }));

    expect(document.activeElement).toBe(rows[0]);
    expect(navigate).toHaveBeenCalledTimes(1);
    expect(String(navigate.calls.mostRecent().args[0])).toBe('/projects/1/issues/1');
  }));

  it('ArrowDown skips a collapsed row\'s hidden children, following visible render order (#747)', fakeAsync(() => {
    const fixture = init();
    flushTree(1, tree());
    TestBed.inject(CollapseStore).toggle(1, 1); // collapse initiative #1
    fixture.detectChanges();
    const navigate = spyOn(TestBed.inject(Router), 'navigateByUrl').and.resolveTo(true);

    const rows = Array.from(fixture.nativeElement.querySelectorAll('a.row')) as HTMLAnchorElement[];
    // Collapsed: child #2 is out of the DOM entirely, same as closed #3.
    expect(rows.map((r) => r.getAttribute('href'))).toEqual(['/projects/1/issues/1', '/projects/1/issues/4']);

    rows[0].focus();
    rows[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true }));

    expect(document.activeElement).toBe(rows[1]);
    expect(String(navigate.calls.mostRecent().args[0])).toBe('/projects/1/issues/4');
  }));

  it('ArrowDown/ArrowUp do nothing at the ends of the visible list (#747)', fakeAsync(() => {
    const fixture = init();
    flushTree(1, tree());
    fixture.detectChanges();
    const navigate = spyOn(TestBed.inject(Router), 'navigateByUrl').and.resolveTo(true);

    const rows = Array.from(fixture.nativeElement.querySelectorAll('a.row')) as HTMLAnchorElement[];
    rows[rows.length - 1].focus();
    rows[rows.length - 1].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true }));

    expect(document.activeElement).toBe(rows[rows.length - 1]);
    expect(navigate).not.toHaveBeenCalled();
  }));

  it('arrow keys keep their normal behavior in the filter input, not just outside a row (#747)', () => {
    const fixture = init();
    flushTree(1, tree());
    fixture.detectChanges();
    const navigate = spyOn(TestBed.inject(Router), 'navigateByUrl').and.resolveTo(true);

    const input = fixture.nativeElement.querySelector('.filter-input') as HTMLInputElement;
    input.focus();
    const event = new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true });
    input.dispatchEvent(event);

    expect(event.defaultPrevented).toBeFalse();
    expect(navigate).not.toHaveBeenCalled();
  });

  it('isSelected only matches the exact project/issue pair', () => {
    const fixture = init([PROJECT_A, PROJECT_B]);
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    httpMock.expectOne('/api/projects/2/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
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
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    httpMock.expectOne('/api/projects/2/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
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
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    httpMock.expectOne('/api/projects/2/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
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

  it('hides the per-row status label while hideShipped is checked (#351)', () => {
    const fixture = init();
    flushTree(1, tree());
    fixture.detectChanges();

    expect(fixture.componentInstance.hideShipped).toBeTrue();
    expect(fixture.nativeElement.querySelectorAll('.issue-state')).toHaveSize(0);
  });

  it('shows the per-row status label once hideShipped is unchecked (#351)', () => {
    const fixture = init();
    flushTree(1, tree());
    fixture.componentInstance.hideShipped = false;
    fixture.detectChanges();

    const labels = Array.from(fixture.nativeElement.querySelectorAll('.issue-state')) as HTMLElement[];
    expect(labels.map((el) => el.textContent?.trim())).toEqual(['open', 'open', 'closed', 'open']);
  });

  it('an issue with an open console stays visible under hideShipped even when closed (#263)', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    httpMock.expectOne((req) => /\/api\/projects\/1\/consoles$/.test(req.url)).flush(['1-3-fix-thing']);

    const section = fixture.componentInstance.projectSections[0];
    const initiative = fixture.componentInstance.mainNodesFor(section)[0];

    expect(fixture.componentInstance.hideShipped).toBeTrue();
    expect(initiative.children.map((c) => c.number)).toEqual([2, 3]);
  });

  it('while hideShipped is checked, still shows the status label for a CLOSED row kept visible by an open console (#366)', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    httpMock.expectOne((req) => /\/api\/projects\/1\/consoles$/.test(req.url)).flush(['1-3-fix-thing']);
    fixture.detectChanges();

    expect(fixture.componentInstance.hideShipped).toBeTrue();
    // #1, #2, #4 are OPEN and stay label-less; #3 is CLOSED but visible only via
    // its open console (#263), so its label is the one signal telling the user that.
    const labels = Array.from(fixture.nativeElement.querySelectorAll('.issue-state')) as HTMLElement[];
    expect(labels.map((el) => el.textContent?.trim())).toEqual(['closed']);
  });

  it('an issue with an open console stays visible regardless of the typed search text (#263)', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    httpMock.expectOne((req) => /\/api\/projects\/1\/consoles$/.test(req.url)).flush(['1-4-standalone']);

    fixture.componentInstance.filterText = 'no match at all';
    const section = fixture.componentInstance.projectSections[0];

    expect(fixture.componentInstance.mainNodesFor(section).map((n) => n.number)).toEqual([4]);
  });

  it('an issue with no open console is unaffected by either filter (#263)', () => {
    const fixture = init();
    flushTree(1, tree());

    fixture.componentInstance.filterText = 'no match at all';
    const section = fixture.componentInstance.projectSections[0];

    expect(fixture.componentInstance.mainNodesFor(section)).toEqual([]);
  });

  it('refresh() re-fetches everything and updates the list in place', () => {
    const fixture = init();
    flushTree(1, tree());

    fixture.componentInstance.refresh();
    expect(fixture.componentInstance.refreshing).toBeTrue();

    httpMock.expectOne('/api/projects').flush([PROJECT_A]);
    const updated: TreeNode[] = [
      ...tree(),
      { number: 5, title: 'New from GitHub', kind: 'TASK', state: 'OPEN', hasActiveBranch: false, labels: [], children: [] },
    ];
    flushTree(1, updated, true);

    expect(fixture.componentInstance.refreshing).toBeFalse();
    const section = fixture.componentInstance.projectSections[0];
    expect(fixture.componentInstance.mainNodesFor(section).map((n) => n.number)).toEqual([1, 4, 5]);
  });

  it('refresh() requests the tree with fresh=true, bypassing the engine cache (#545)', () => {
    const fixture = init();
    flushTree(1, tree());

    fixture.componentInstance.refresh();
    httpMock.expectOne('/api/projects').flush([PROJECT_A]);
    const req = httpMock.expectOne((r) => r.url === '/api/projects/1/issues/tree');
    expect(req.request.params.get('fresh')).toBe('true');
    req.flush({ nodes: tree(), github: GITHUB_OK });
    flushConsoles();
  });

  it('the initial load does not request fresh=true (#545)', () => {
    init();
    const req = httpMock.expectOne((r) => r.url === '/api/projects/1/issues/tree');
    expect(req.request.params.has('fresh')).toBeFalse();
    req.flush({ nodes: tree(), github: GITHUB_OK });
    flushConsoles();
  });

  it('refresh() coalesces a call that arrives while one is already in flight (#738)', () => {
    const fixture = init();
    flushTree(1, tree());

    fixture.componentInstance.refresh();
    fixture.componentInstance.refresh();

    // Only one in-flight request pair while the first settles: the second refresh()
    // call is queued rather than firing its own request right away.
    httpMock.expectOne('/api/projects').flush([PROJECT_A]);
    flushTree(1, tree(), true);
    expect(fixture.componentInstance.refreshing).toBeTrue();

    // The queued call now runs for real, rather than being dropped -- a refresh
    // requested during the first one may need to see state the first started too
    // early to reflect (#738).
    httpMock.expectOne('/api/projects').flush([PROJECT_A]);
    flushTree(1, tree(), true);
    expect(fixture.componentInstance.refreshing).toBeFalse();
  });

  it('refresh() surfaces a failed project-list request without clearing the existing list (#801)', () => {
    const fixture = init();
    flushTree(1, tree());

    fixture.componentInstance.refresh();
    httpMock.expectOne('/api/projects').error(new ProgressEvent('network error'));
    fixture.detectChanges();

    expect(fixture.componentInstance.refreshing).toBeFalse();
    // Not the sidenav-wide error state: that one only ever covers the first load,
    // with nothing yet rendered to fall back to replacing.
    expect(fixture.componentInstance.error).toBeFalse();
    expect(fixture.componentInstance.listRefreshFailed).toBeTrue();
    const section = fixture.componentInstance.projectSections[0];
    expect(fixture.componentInstance.mainNodesFor(section).map((n) => n.number)).toEqual([1, 4]);

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.list-refresh-error')?.textContent).toContain('could not refresh issues');
    expect(compiled.querySelectorAll('.project-section')).toHaveSize(1);
    const hrefs = Array.from(compiled.querySelectorAll('a.row')).map((row) => row.getAttribute('href'));
    expect(hrefs).toEqual(['/projects/1/issues/1', '/projects/1/issues/2', '/projects/1/issues/4']);
  });

  it('the refresh-failed notice clears on the next successful list load (#801)', () => {
    const fixture = init();
    flushTree(1, tree());

    fixture.componentInstance.refresh();
    httpMock.expectOne('/api/projects').error(new ProgressEvent('network error'));
    fixture.detectChanges();
    expect(fixture.componentInstance.listRefreshFailed).toBeTrue();

    fixture.componentInstance.refresh();
    httpMock.expectOne('/api/projects').flush([PROJECT_A]);
    flushTree(1, tree(), true);
    fixture.detectChanges();

    expect(fixture.componentInstance.listRefreshFailed).toBeFalse();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.list-refresh-error')).toBeNull();
  });

  it("the first load's failure still shows the sidenav-wide error state, not the refresh notice (#801)", () => {
    const fixture = TestBed.createComponent(SidenavComponent);
    fixture.detectChanges();
    httpMock.expectOne('/api/projects').error(new ProgressEvent('network error'));
    httpMock.expectOne('/api/usage').flush(EMPTY_USAGE);
    fixture.detectChanges();

    expect(fixture.componentInstance.error).toBeTrue();
    expect(fixture.componentInstance.listRefreshFailed).toBeFalse();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.state')?.textContent).toContain('could not load issues');
    expect(compiled.querySelectorAll('.project-section')).toHaveSize(0);
  });

  it('a tree fetch that fails during refresh() marks that project failed and keeps its previous tree (#787)', () => {
    const fixture = init([PROJECT_A, PROJECT_B]);
    flushTree(1, tree());
    flushTree(2, tree());

    fixture.componentInstance.refresh();
    httpMock.expectOne('/api/projects').flush([PROJECT_A, PROJECT_B]);
    httpMock.expectOne('/api/projects/1/issues/tree?fresh=true').error(new ProgressEvent('network error'));
    expect(fixture.componentInstance.refreshing).toBeTrue(); // B's tree is still out
    const updated: TreeNode[] = [
      ...tree(),
      { number: 5, title: 'New from GitHub', kind: 'TASK', state: 'OPEN', hasActiveBranch: false, labels: [], children: [] },
    ];
    flushTree(2, updated, true);

    expect(fixture.componentInstance.refreshing).toBeFalse();
    expect(fixture.componentInstance.error).toBeFalse();
    const [sectionA, sectionB] = fixture.componentInstance.projectSections;
    expect(sectionA.treeState).toBe('failed');
    expect(fixture.componentInstance.mainNodesFor(sectionA).map((n) => n.number)).toEqual([1, 4]);
    expect(sectionB.treeState).toBe('loaded');
    expect(fixture.componentInstance.mainNodesFor(sectionB).map((n) => n.number)).toEqual([1, 4, 5]);
  });

  it('refreshing stays on, and a queued refresh waits, until every project\'s tree has settled (#738, #787)', () => {
    const fixture = init([PROJECT_A, PROJECT_B]);
    flushTree(1, tree());
    flushTree(2, tree());

    fixture.componentInstance.refresh();
    fixture.componentInstance.refresh(); // queued behind the first (#738)
    httpMock.expectOne('/api/projects').flush([PROJECT_A, PROJECT_B]);
    httpMock.expectOne('/api/projects/2/issues/tree?fresh=true').flush({ nodes: tree(), github: GITHUB_OK });

    // B is in, A is still out: the refresh is not over and the queued one waits.
    expect(fixture.componentInstance.refreshing).toBeTrue();
    httpMock.expectNone('/api/projects');
    httpMock.expectOne('/api/projects/1/issues/tree?fresh=true').error(new ProgressEvent('network error'));
    flushConsoles();

    // A failure settles A like a success would; now the queued run starts, and A
    // (failed, so not carried over) starts over as loading once its list lands.
    expect(fixture.componentInstance.projectSections[0].treeState).toBe('failed');
    httpMock.expectOne('/api/projects').flush([PROJECT_A, PROJECT_B]);
    expect(fixture.componentInstance.refreshing).toBeTrue();
    expect(fixture.componentInstance.projectSections[0].treeState).toBe('loading');
    flushTree(1, tree(), true);
    flushTree(2, tree(), true);
    expect(fixture.componentInstance.refreshing).toBeFalse();
    expect(fixture.componentInstance.projectSections.map((s) => s.treeState)).toEqual(['loaded', 'loaded']);
  });

  it("a reload keeps showing each loaded project's current tree until its own new one lands (#787)", () => {
    const fixture = init([PROJECT_A, PROJECT_B]);
    flushTree(1, tree());
    flushTree(2, tree());
    fixture.detectChanges();

    fixture.componentInstance.refresh();
    httpMock.expectOne('/api/projects').flush([PROJECT_A, PROJECT_B]);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelectorAll('.tree-loading')).toHaveSize(0);
    expect(compiled.querySelectorAll('a.row')).toHaveSize(6);

    const updated: TreeNode[] = [
      ...tree(),
      { number: 5, title: 'New from GitHub', kind: 'TASK', state: 'OPEN', hasActiveBranch: false, labels: [], children: [] },
    ];
    flushTree(1, updated, true);
    const [sectionA, sectionB] = fixture.componentInstance.projectSections;
    expect(fixture.componentInstance.mainNodesFor(sectionA).map((n) => n.number)).toEqual([1, 4, 5]);
    expect(fixture.componentInstance.mainNodesFor(sectionB).map((n) => n.number)).toEqual([1, 4]);
    flushTree(2, tree(), true);
  });

  it('a project still cloning shows a cloning state instead of its tree', () => {
    const cloning: Project = { ...PROJECT_A, status: 'CLONING' };
    const fixture = init([cloning]);
    flushTree(1, tree());

    const section = fixture.componentInstance.projectSections[0];
    expect(section.project.status).toBe('CLONING');
  });

  it('updates in place off a projectStatus event when a clone settles, without re-polling (#721)', fakeAsync(() => {
    const cloning: Project = { ...PROJECT_A, status: 'CLONING' };
    const fixture = init([cloning]);
    flushTree(1, tree());

    emitAppEvent({ type: 'projectStatus', projectId: 1, status: 'READY', defaultBranch: 'main' });

    expect(fixture.componentInstance.projectSections[0].project.status).toBe('READY');
    expect(fixture.componentInstance.projectSections[0].project.defaultBranch).toBe('main');
    // A newly READY row fetches its real tree once (#729) -- one fetch, no poll.
    flushTree(1, tree());
    tick(3000);
    httpMock.expectNone('/api/projects');
    httpMock.expectNone('/api/projects/1/issues/tree');

    fixture.destroy();
  }));

  it('a projectStatus FAILED event marks that project failed in place (#721)', () => {
    const cloning: Project = { ...PROJECT_A, status: 'CLONING' };
    const fixture = init([cloning]);
    flushTree(1, tree());

    emitAppEvent({ type: 'projectStatus', projectId: 1, status: 'FAILED' });

    expect(fixture.componentInstance.projectSections[0].project.status).toBe('FAILED');
  });

  it('a projectStatus event for a project the reload still does not carry leaves the loaded rows alone (#721, #760)', () => {
    const fixture = init([PROJECT_A]);
    flushTree(1, tree());

    emitAppEvent({ type: 'projectStatus', projectId: 999, status: 'READY', defaultBranch: 'main' });

    // Unlisted here, so the list is reloaded (#760) -- but it still does not carry
    // 999 (another account's project, say), and nothing else changes.
    httpMock.expectOne('/api/projects').flush([PROJECT_A]);
    flushTree(1, tree());

    expect(fixture.componentInstance.projectSections.map((s) => s.project.id)).toEqual([1]);
    expect(fixture.componentInstance.projectSections[0].project.status).toBe('READY');
    httpMock.expectNone('/api/projects/999/issues/tree');
  });

  it('a projectStatus READY for a project not listed here reloads the list and, once the row exists, fetches its tree (#760)', () => {
    const fixture = init([PROJECT_A]);
    flushTree(1, tree());

    // Another window created project 2 and its clone just settled. This window
    // never listed it, and no reload of its own is in flight -- before #760 the
    // event was held for a reload that was never going to come.
    emitAppEvent({ type: 'projectStatus', projectId: 2, status: 'READY', defaultBranch: 'develop' });

    // The event-driven reload does not bypass the engine's cache (no fresh=true):
    // the list changed, not every project's cached tree.
    const cloning: Project = { ...PROJECT_B, status: 'CLONING' };
    httpMock.expectOne('/api/projects').flush([PROJECT_A, cloning]);
    flushTree(1, tree());
    flushTree(2, []);

    const section = fixture.componentInstance.projectSections[1];
    expect(section.project.id).toBe(2);
    expect(section.project.status).toBe('READY');
    expect(section.project.defaultBranch).toBe('develop');

    // The held event settled the row once it existed, and the newly READY row loads
    // its real tree instead of sitting empty -- one fetch, no further reload.
    httpMock.expectNone('/api/projects');
    flushTree(2, tree());
    expect(fixture.componentInstance.projectSections[1].tree.length).toBe(2);
  });

  it('a projectStatus event that lands while the reveal reload is still in flight settles the row once the reload lands (#729)', () => {
    const fixture = init([PROJECT_A]);
    flushTree(1, tree());

    // Import: the new project is revealed via a fresh reload. The list answers
    // CLONING, but the trees are still pending when the engine settles the clone.
    const cloning: Project = { ...PROJECT_B, status: 'CLONING' };
    fixture.componentInstance.revealProject(2);
    httpMock.expectOne('/api/projects').flush([PROJECT_A, cloning]);
    emitAppEvent({ type: 'projectStatus', projectId: 2, status: 'READY', defaultBranch: 'develop' });
    flushTree(1, tree(), true);
    httpMock.expectOne('/api/projects/2/issues/tree?fresh=true').flush({ nodes: [], github: GITHUB_OK });
    flushConsoles();

    const section = fixture.componentInstance.projectSections[1];
    expect(section.project.id).toBe(2);
    expect(section.project.status).toBe('READY');
    expect(section.project.defaultBranch).toBe('develop');

    // The newly READY row loads its real tree instead of sitting empty, without re-polling.
    httpMock.expectNone('/api/projects');
    flushTree(2, tree());
    expect(fixture.componentInstance.projectSections[1].tree.length).toBe(2);
  });

  it('a projectStatus event that lands before the reveal reload even lists the project is not lost (#729)', () => {
    const fixture = init([PROJECT_A]);
    flushTree(1, tree());

    const cloning: Project = { ...PROJECT_B, status: 'CLONING' };
    fixture.componentInstance.revealProject(2);
    emitAppEvent({ type: 'projectStatus', projectId: 2, status: 'FAILED' });
    httpMock.expectOne('/api/projects').flush([PROJECT_A, cloning]);
    flushTree(1, tree(), true);
    flushTree(2, [], true);

    expect(fixture.componentInstance.projectSections[1].project.status).toBe('FAILED');
    httpMock.expectNone('/api/projects/2/issues/tree');
  });

  it('a held projectStatus event is dropped once its project is deleted (#729)', () => {
    const fixture = init([PROJECT_A]);
    flushTree(1, tree());

    // The unlisted event starts the reload itself (#760); the delete lands before
    // that reload does, so the held event must not be applied to the row it brings.
    emitAppEvent({ type: 'projectStatus', projectId: 2, status: 'READY', defaultBranch: 'main' });
    emitAppEvent({ type: 'projectDeleted', projectId: 2 });
    const cloning: Project = { ...PROJECT_B, status: 'CLONING' };
    httpMock.expectOne('/api/projects').flush([PROJECT_A, cloning]);
    flushTree(1, tree());
    flushTree(2, []);

    expect(fixture.componentInstance.projectSections[1].project.status).toBe('CLONING');
    httpMock.expectNone('/api/projects/2/issues/tree');
  });

  it('a projectDeleted event drops that project\'s section without a manual refresh (#721, absorbed from #720)', () => {
    const fixture = init([PROJECT_A, PROJECT_B]);
    flushTree(1, tree());
    flushTree(2, tree());

    emitAppEvent({ type: 'projectDeleted', projectId: 1 });

    expect(fixture.componentInstance.projectSections.map((s) => s.project.id)).toEqual([2]);
  });

  it('a projectDeleted event for a project not currently loaded is a no-op (#721)', () => {
    const fixture = init([PROJECT_A]);
    flushTree(1, tree());

    emitAppEvent({ type: 'projectDeleted', projectId: 999 });

    expect(fixture.componentInstance.projectSections.map((s) => s.project.id)).toEqual([1]);
  });

  it('a cloning row shows a staged line and ticking elapsed seconds (#717)', fakeAsync(() => {
    const cloning: Project = { ...PROJECT_A, status: 'CLONING' };
    const fixture = init([cloning]);
    flushTree(1, tree());
    fixture.detectChanges();

    tick(9000);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.project-status.cloning')?.textContent).toContain('9s');
    const line = compiled.querySelector('.cloning-state')?.textContent ?? '';
    expect(line).toContain('cloning repository…');
    expect(line).toContain('9s');

    // Settles to READY on the projectStatus event (#721), leaving no tick timer behind.
    emitAppEvent({ type: 'projectStatus', projectId: 1, status: 'READY', defaultBranch: 'main' });
    flushTree(1, tree());
    fixture.detectChanges();
    expect(compiled.querySelector('.cloning-state')).toBeFalsy();

    fixture.destroy();
  }));

  it('revealProject expands, scrolls to, and briefly highlights the new row (#717)', fakeAsync(() => {
    const cloning: Project = { ...PROJECT_B, status: 'CLONING' };
    const fixture = init([PROJECT_A, cloning]);
    flushTree(1, tree());
    flushTree(2, tree());
    fixture.detectChanges();

    TestBed.inject(ProjectSectionStore).toggle(2);
    fixture.detectChanges();
    expect(fixture.componentInstance.isProjectCollapsed(2)).toBeTrue();

    let done = 0;
    fixture.componentInstance.revealProject(2, () => done++);
    expect(fixture.componentInstance.isProjectCollapsed(2)).toBeFalse();
    // revealProject refreshes fresh (#545) so the just-created row exists.
    httpMock.expectOne('/api/projects').flush([PROJECT_A, cloning]);
    flushTree(1, tree(), true);
    flushTree(2, tree(), true);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(fixture.componentInstance.revealedProjectId).toBe(2);
    expect(compiled.querySelector('.project-section.revealed[data-project-id="2"]')).toBeTruthy();
    expect(done).toBe(1);

    // The highlight clears after 3s regardless of clone status, with no re-poll.
    tick(3000);
    httpMock.expectNone('/api/projects');
    fixture.detectChanges();
    expect(fixture.componentInstance.revealedProjectId).toBeNull();

    // Settling to READY (#721) updates in place, stopping every timer.
    emitAppEvent({ type: 'projectStatus', projectId: 2, status: 'READY', defaultBranch: 'main' });
    flushTree(2, tree());
    fixture.detectChanges();

    fixture.destroy();
  }));

  it('a failed reveal reload still fires done rather than trapping the waiter (#717)', fakeAsync(() => {
    const fixture = init([PROJECT_A]);
    flushTree(1, tree());
    fixture.detectChanges();

    let done = 0;
    fixture.componentInstance.revealProject(9, () => done++);
    httpMock.expectOne('/api/projects').error(new ProgressEvent('network error'));
    fixture.detectChanges();

    expect(done).toBe(1);
    expect(fixture.componentInstance.revealedProjectId).toBeNull();
    tick(3000);
    httpMock.expectNone('/api/projects');
    fixture.destroy();
  }));

  it('revealProject during an already in-flight refresh is not lost once that refresh settles without the new row (#738)', () => {
    const fixture = init([PROJECT_A]);
    flushTree(1, tree());

    // Some other refresh (e.g. retryProject, or another reveal) is already in flight
    // when the new project's create request finishes.
    fixture.componentInstance.refresh();
    fixture.componentInstance.revealProject(2);

    // The reveal's own refresh() call is coalesced onto the one already running,
    // rather than firing a second /api/projects request immediately.
    httpMock.expectOne('/api/projects').flush([PROJECT_A]);
    flushTree(1, tree(), true);

    // That in-flight refresh started before the project existed, so it can't have
    // revealed it -- but the coalesced refresh it queued now fires for real.
    expect(fixture.componentInstance.revealedProjectId).toBeNull();
    httpMock.expectOne('/api/projects').flush([PROJECT_A, PROJECT_B]);
    flushTree(1, tree(), true);
    flushTree(2, tree(), true);

    expect(fixture.componentInstance.revealedProjectId).toBe(2);
  });

  it('retryProject calls the retry endpoint and refreshes', () => {
    const fixture = init();
    flushTree(1, tree());

    fixture.componentInstance.retryProject(1, new Event('click'));

    httpMock.expectOne('/api/projects/1/retry').flush({ ...PROJECT_A });
    httpMock.expectOne('/api/projects').flush([PROJECT_A]);
    flushTree(1, tree(), true);
  });

  it('deleteProject awaits confirmation in the dialog before calling delete', () => {
    const fixture = init();
    flushTree(1, tree());

    fixture.componentInstance.deleteProject(1, new Event('click'));

    expect(fixture.componentInstance.pendingDeleteProjectId).toBe(1);
    httpMock.expectNone('/api/projects/1');
  });

  it('confirmDeleteProject calls delete and refreshes', () => {
    const fixture = init();
    flushTree(1, tree());
    fixture.componentInstance.deleteProject(1, new Event('click'));

    fixture.componentInstance.confirmDeleteProject();

    expect(fixture.componentInstance.pendingDeleteProjectId).toBeNull();
    httpMock.expectOne('/api/projects/1').flush(null);
    httpMock.expectOne('/api/projects').flush([]);
  });

  it('cancelDeleteProject does nothing', () => {
    const fixture = init();
    flushTree(1, tree());
    fixture.componentInstance.deleteProject(1, new Event('click'));

    fixture.componentInstance.cancelDeleteProject();

    expect(fixture.componentInstance.pendingDeleteProjectId).toBeNull();
    httpMock.expectNone('/api/projects/1');
  });

  it('shows the backend refusal inline when a failed project cannot be deleted (#250)', () => {
    const failed: Project = { ...PROJECT_A, status: 'FAILED' };
    const fixture = init([failed]);
    flushTree(1, []);
    fixture.componentInstance.deleteProject(1, new Event('click'));

    fixture.componentInstance.confirmDeleteProject();
    httpMock
      .expectOne('/api/projects/1')
      .flush(
        { error: 'This project has an open worktree or console — close it before deleting the project.' },
        { status: 409, statusText: 'Conflict' },
      );

    expect(fixture.componentInstance.deleteErrorFor(1)).toBe(
      'This project has an open worktree or console — close it before deleting the project.',
    );
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'This project has an open worktree or console',
    );
  });

  it('deleteProject clears a previous delete error', () => {
    const fixture = init();
    flushTree(1, tree());
    fixture.componentInstance.deleteProject(1, new Event('click'));
    fixture.componentInstance.confirmDeleteProject();
    httpMock.expectOne('/api/projects/1').flush({ error: 'nope' }, { status: 409, statusText: 'Conflict' });
    expect(fixture.componentInstance.deleteErrorFor(1)).toBe('nope');

    fixture.componentInstance.deleteProject(1, new Event('click'));

    expect(fixture.componentInstance.deleteErrorFor(1)).toBeNull();
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
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    httpMock.expectOne('/api/projects/2/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    flushConsoles();

    emitAppEvent({ type: 'issuesChanged', projectId: 1 });

    httpMock.expectNone('/api/projects'); // notify-then-fetch, not a full reload
    const updated: TreeNode[] = [
      ...tree(),
      { number: 5, title: 'New from GitHub', kind: 'TASK', state: 'OPEN', hasActiveBranch: false, labels: [], children: [] },
    ];
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: updated, github: GITHUB_OK });
    flushConsoles();

    const [sectionA, sectionB] = fixture.componentInstance.projectSections;
    expect(fixture.componentInstance.mainNodesFor(sectionA).map((n) => n.number)).toEqual([1, 4, 5]);
    expect(fixture.componentInstance.mainNodesFor(sectionB).map((n) => n.number)).toEqual([1, 4]);
  });

  it('a githubRefreshStatus event shows the failure for that project, and a successful reload clears it (#619)', () => {
    const fixture = init([PROJECT_A, PROJECT_B]);
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    httpMock.expectOne('/api/projects/2/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    flushConsoles();
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.github-error')).toBeNull();

    emitAppEvent({
      type: 'githubRefreshStatus',
      projectId: 1,
      failing: true,
      failure: 'gh exited 1: HTTP 401: Bad credentials',
      lastSuccessAt: new Date(Date.now() - 5 * 60 * 1000).toISOString(),
    });
    fixture.detectChanges();

    httpMock.expectNone('/api/projects/1/issues/tree'); // the event carries the outcome; no re-fetch
    const errors = (fixture.nativeElement as HTMLElement).querySelectorAll('.github-error');
    expect(errors.length).toBe(1);
    expect(errors[0].textContent).toContain('HTTP 401: Bad credentials');
    expect(errors[0].textContent).toContain('last refreshed 5 min ago');
    expect((fixture.nativeElement as HTMLElement).querySelector('.github-error button')).toBeNull(); // not dismissable

    // The refresh button makes a fresh attempt and reports honestly: still failing.
    fixture.componentInstance.refresh();
    httpMock.expectOne('/api/projects').flush([PROJECT_A, PROJECT_B]);
    const failing = { failing: true, failure: 'gh exited 1: HTTP 401: Bad credentials', lastSuccessAt: null };
    httpMock.expectOne('/api/projects/1/issues/tree?fresh=true').flush({ nodes: tree(), github: failing });
    httpMock.expectOne('/api/projects/2/issues/tree?fresh=true').flush({ nodes: tree(), github: GITHUB_OK });
    flushConsoles();
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.github-error')?.textContent).toContain(
      'never refreshed successfully',
    );

    // The next successful load clears it.
    emitAppEvent({ type: 'issuesChanged', projectId: 1 });
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    flushConsoles();
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.github-error')).toBeNull();
  });

  it('a githubRefreshStatus event for a project not currently loaded is ignored (#619)', () => {
    const fixture = init();
    flushTree(1, tree());

    emitAppEvent({ type: 'githubRefreshStatus', projectId: 999, failing: true, failure: 'nope' });
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelector('.github-error')).toBeNull();
  });

  it('an issuesChanged event for a project not listed here reloads the list instead of being dropped (#760)', () => {
    const fixture = init([PROJECT_A]);
    flushTree(1, tree());

    // Project 2 was created in another window and this one never listed it: there
    // is no row to fetch a tree into, so the list itself is reloaded.
    emitAppEvent({ type: 'issuesChanged', projectId: 2 });

    httpMock.expectNone('/api/projects/2/issues/tree');
    httpMock.expectOne('/api/projects').flush([PROJECT_A, PROJECT_B]);
    flushTree(1, tree());
    flushTree(2, tree());

    expect(fixture.componentInstance.projectSections.map((s) => s.project.id)).toEqual([1, 2]);
    expect(fixture.componentInstance.mainNodesFor(fixture.componentInstance.projectSections[1]).map((n) => n.number)).toEqual([1, 4]);
  });

  it('a projectCreated event reloads the list, so a project created in another window appears here (#760)', () => {
    const fixture = init([PROJECT_A]);
    flushTree(1, tree());

    emitAppEvent({ type: 'projectCreated', projectId: 2 });

    // Not fresh (#545): the list changed, not every project's cached tree.
    const cloning: Project = { ...PROJECT_B, status: 'CLONING' };
    httpMock.expectOne('/api/projects').flush([PROJECT_A, cloning]);
    flushTree(1, tree());
    flushTree(2, []);

    expect(fixture.componentInstance.projectSections.map((s) => s.project.id)).toEqual([1, 2]);
    expect(fixture.componentInstance.projectSections[1].project.status).toBe('CLONING');
  });

  it('a projectCreated event during an in-flight refresh queues behind it rather than racing it (#738, #760)', () => {
    const fixture = init([PROJECT_A]);
    flushTree(1, tree());

    fixture.componentInstance.refresh();
    emitAppEvent({ type: 'projectCreated', projectId: 2 });

    // One list request in flight: the event's reload waits for the running one...
    httpMock.expectOne('/api/projects').flush([PROJECT_A]);
    flushTree(1, tree(), true);
    // ...then runs for real, since the running one may have been sent before the
    // row existed. Only the event asked for this run, so it is not fresh.
    httpMock.expectOne('/api/projects').flush([PROJECT_A, PROJECT_B]);
    flushTree(1, tree());
    flushTree(2, tree());

    expect(fixture.componentInstance.refreshing).toBeFalse();
    expect(fixture.componentInstance.projectSections.map((s) => s.project.id)).toEqual([1, 2]);
  });

  it('re-narrows as soon as the focusedProjectId input changes, without waiting for refresh() (#803)', () => {
    const fixture = init([PROJECT_A, PROJECT_B]);
    flushTree(1, tree());
    flushTree(2, tree());
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    const ids = () => fixture.componentInstance.projectSections.map((s) => s.project.id);
    expect(ids()).toEqual([1, 2]);

    // The window becomes focused on project 2: only its section stays, and no other
    // project's tree is asked for.
    fixture.componentRef.setInput('focusedProjectId', 2);
    fixture.detectChanges();
    httpMock.expectOne('/api/projects').flush([PROJECT_A, PROJECT_B]);
    fixture.detectChanges();
    expect(ids()).toEqual([2]);
    expect(compiled.querySelectorAll('.project-section')).toHaveSize(1);
    httpMock.expectNone('/api/projects/1/issues/tree');
    flushTree(2, tree());
    fixture.detectChanges();
    expect(compiled.querySelectorAll('.project-section[data-project-id="2"] a.row')).toHaveSize(3);

    // And back to an ordinary window: every project is listed again.
    fixture.componentRef.setInput('focusedProjectId', null);
    fixture.detectChanges();
    httpMock.expectOne('/api/projects').flush([PROJECT_A, PROJECT_B]);
    fixture.detectChanges();
    expect(ids()).toEqual([1, 2]);
    expect(compiled.querySelectorAll('.project-section')).toHaveSize(2);
    flushTree(1, tree());
    flushTree(2, tree());
    expect(fixture.componentInstance.refreshing).toBeFalse();
  });

  it('a focused sidenav ignores projectCreated, issuesChanged, and projectStatus for any other project (#286, #760)', () => {
    const fixture = TestBed.createComponent(SidenavComponent);
    fixture.componentInstance.focusedProjectId = 1;
    fixture.detectChanges();
    httpMock.expectOne('/api/projects').flush([PROJECT_A, PROJECT_B]);
    flushTree(1, tree());
    httpMock.expectOne('/api/usage').flush(EMPTY_USAGE);

    // A focused window lists exactly one project -- a reload could never carry
    // another, so these are not a reason to run one.
    emitAppEvent({ type: 'projectCreated', projectId: 3 });
    emitAppEvent({ type: 'issuesChanged', projectId: 2 });
    emitAppEvent({ type: 'projectStatus', projectId: 3, status: 'READY', defaultBranch: 'main' });

    httpMock.expectNone('/api/projects');
    expect(fixture.componentInstance.projectSections.map((s) => s.project.id)).toEqual([1]);
  });

  it('an in-flight issuesChanged re-fetch lands on the right project after a reload replaced the list (#760)', () => {
    const fixture = init([PROJECT_A, PROJECT_B]);
    flushTree(1, tree());
    flushTree(2, tree());

    emitAppEvent({ type: 'issuesChanged', projectId: 2 });
    const inFlight = httpMock.expectOne('/api/projects/2/issues/tree');

    // A reconnect replaces `sections` -- in a different order -- while that
    // re-fetch is still in flight, so project 2's row is now at index 0.
    emitReconnected();
    httpMock.expectOne('/api/projects').flush([PROJECT_B, PROJECT_A]);
    flushTree(2, tree());
    flushTree(1, tree());
    expect(fixture.componentInstance.projectSections.map((s) => s.project.id)).toEqual([2, 1]);

    const updated: TreeNode[] = [
      ...tree(),
      { number: 5, title: 'New from GitHub', kind: 'TASK', state: 'OPEN', hasActiveBranch: false, labels: [], children: [] },
    ];
    inFlight.flush({ nodes: updated, github: GITHUB_OK });
    flushConsoles();

    // Before #760 the response was written to the index captured at request time
    // (1), handing project 2's tree to project 1's row.
    const [first, second] = fixture.componentInstance.projectSections;
    expect(first.project.id).toBe(2);
    expect(fixture.componentInstance.mainNodesFor(first).map((n) => n.number)).toEqual([1, 4, 5]);
    expect(second.project.id).toBe(1);
    expect(fixture.componentInstance.mainNodesFor(second).map((n) => n.number)).toEqual([1, 4]);
  });

  it('a project-stale notification (#140) re-fetches that project\'s tree with fresh=true', () => {
    init();
    flushTree(1, tree());

    TestBed.inject(IssuesService).notifyProjectStale(1);

    const req = httpMock.expectOne((r) => r.url === '/api/projects/1/issues/tree');
    expect(req.request.params.get('fresh')).toBe('true');
    req.flush({ nodes: tree(), github: GITHUB_OK });
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
      { number: 5, title: 'New from GitHub', kind: 'TASK', state: 'OPEN', hasActiveBranch: false, labels: [], children: [] },
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
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    httpMock.expectOne('/api/projects/2/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    flushConsoles();

    emitAppEvent({ type: 'consoleAttention', sessionId: '2-4-main-slug', state: 'waiting' });

    expect(fixture.componentInstance.hasAttentionWaiting(1, 4)).toBeFalse();
    expect(fixture.componentInstance.hasAttentionWaiting(2, 4)).toBeTrue();
  });

  it('a consoleAttention waiting event for a project-level console marks that project, an active event clears it (#450)', () => {
    const fixture = init();
    flushTree(1, tree());

    emitAppEvent({ type: 'consoleAttention', sessionId: '1-console', state: 'waiting' });
    expect(fixture.componentInstance.hasAttentionWaitingForProject(1)).toBeTrue();
    // The event lands on the project row only -- no issue row is marked.
    expect(fixture.componentInstance.hasAttentionWaiting(1, 4)).toBeFalse();

    emitAppEvent({ type: 'consoleAttention', sessionId: '1-console', state: 'active' });
    expect(fixture.componentInstance.hasAttentionWaitingForProject(1)).toBeFalse();
  });

  it('an issue-attached console waiting does not mark the project row -- it tracks project-level consoles exclusively (#450)', () => {
    const fixture = init();
    flushTree(1, tree());

    emitAppEvent({ type: 'consoleAttention', sessionId: '1-4-main-slug', state: 'waiting' });

    expect(fixture.componentInstance.hasAttentionWaiting(1, 4)).toBeTrue();
    expect(fixture.componentInstance.hasAttentionWaitingForProject(1)).toBeFalse();
  });

  it('a suffixed project-console session id marks only its own project (#450)', () => {
    const fixture = init([PROJECT_A, PROJECT_B]);
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    httpMock.expectOne('/api/projects/2/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    flushConsoles();

    emitAppEvent({ type: 'consoleAttention', sessionId: '2-console-abc', state: 'waiting' });

    expect(fixture.componentInstance.hasAttentionWaitingForProject(2)).toBeTrue();
    expect(fixture.componentInstance.hasAttentionWaitingForProject(1)).toBeFalse();
  });

  it('one project console going active does not clear another still-waiting one (#450)', () => {
    const fixture = init();
    flushTree(1, tree());

    emitAppEvent({ type: 'consoleAttention', sessionId: '1-console-a', state: 'waiting' });
    emitAppEvent({ type: 'consoleAttention', sessionId: '1-console-b', state: 'waiting' });
    emitAppEvent({ type: 'consoleAttention', sessionId: '1-console-a', state: 'active' });

    expect(fixture.componentInstance.hasAttentionWaitingForProject(1)).toBeTrue();
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
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: [
      ...tree(),
      { number: 5, title: 'New from GitHub', kind: 'TASK', state: 'OPEN', hasActiveBranch: false, labels: [], children: [] },
    ], github: GITHUB_OK });
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

  it('the header "+" asks the console page for a new console, without selecting the project (#180, #370)', () => {
    const fixture = init();
    flushTree(1, tree());
    fixture.detectChanges();
    const navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
    const emitted: number[] = [];
    fixture.componentInstance.projectSelected.subscribe((id) => emitted.push(id));

    (fixture.nativeElement.querySelector('.section-header .new-console') as HTMLElement).click();

    // #370: the click mints nothing here -- a session the engine has never attached
    // to is missing from the console page's open list, so handing one over by id
    // landed the user in some other console and stranded the new one's worktree.
    // The request rides in `?new` and the page mints it.
    expect(navigate).toHaveBeenCalledWith(['/projects', 1, 'console'], { queryParams: { new: 1 } });
    httpMock.expectNone({ method: 'POST', url: '/api/projects/1/console' });
    expect(emitted).toEqual([]);
  });

  it('the header "+" asks for a new console even when the project already has some open (#370)', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    // This project already has an open project-level console -- the case that used
    // to hand the user back into that existing console instead of a new one.
    httpMock.expectOne((req) => /\/api\/projects\/1\/consoles$/.test(req.url)).flush(['1-console-a1b2c3d4']);
    fixture.detectChanges();
    const navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);

    (fixture.nativeElement.querySelector('.section-header .new-console') as HTMLElement).click();

    expect(navigate).toHaveBeenCalledWith(['/projects', 1, 'console'], { queryParams: { new: 1 } });
    httpMock.expectNone({ method: 'POST', url: '/api/projects/1/console' });
  });

  it('the "+" stays enabled -- opening is the console page\'s job now (#370)', () => {
    const fixture = init();
    flushTree(1, tree());
    fixture.detectChanges();
    spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);

    const plus = fixture.nativeElement.querySelector('.section-header .new-console') as HTMLButtonElement;
    plus.click();
    fixture.detectChanges();

    expect(plus.disabled).toBeFalse();
  });

  it('a project-level console with no issue attached lights the project dot (#330)', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    httpMock.expectOne((req) => /\/api\/projects\/1\/consoles$/.test(req.url)).flush(['1-console-a1b2c3d4']);

    expect(fixture.componentInstance.hasOpenConsoleForProject(1)).toBeTrue();
  });

  it('the legacy project-level console session id shape lights the project dot (#330)', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    httpMock.expectOne((req) => /\/api\/projects\/1\/consoles$/.test(req.url)).flush(['1-console']);

    expect(fixture.componentInstance.hasOpenConsoleForProject(1)).toBeTrue();
  });

  it('a project with no open console of either shape has no project dot (#330)', () => {
    const fixture = init();
    flushTree(1, tree());

    expect(fixture.componentInstance.hasOpenConsoleForProject(1)).toBeFalse();
  });

  it('a project-level console in one project does not light another project\'s dot (#330)', () => {
    const fixture = init([PROJECT_A, PROJECT_B]);
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    httpMock.expectOne('/api/projects/2/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    httpMock.expectOne((req) => /\/api\/projects\/1\/consoles$/.test(req.url)).flush(['1-console-a1b2c3d4']);
    httpMock.expectOne((req) => /\/api\/projects\/2\/consoles$/.test(req.url)).flush([]);

    expect(fixture.componentInstance.hasOpenConsoleForProject(1)).toBeTrue();
    expect(fixture.componentInstance.hasOpenConsoleForProject(2)).toBeFalse();
  });

  it('an issue-attached console alone does not light the project dot (#330)', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    httpMock.expectOne((req) => /\/api\/projects\/1\/consoles$/.test(req.url)).flush(['1-2-fix-bug']);

    expect(fixture.componentInstance.hasOpenConsole(1, 2)).toBeTrue();
    expect(fixture.componentInstance.hasOpenConsoleForProject(1)).toBeFalse();
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

  it('popOutProject opens the current route with focus=1 when the project is currently active (#286)', () => {
    const fixture = init();
    flushTree(1, tree());
    const router = TestBed.inject(Router);
    spyOnProperty(router, 'url', 'get').and.returnValue('/projects/1/issues/4?session=abc');
    fixture.componentInstance.selected = { projectId: 1, issueNumber: 4 };
    const openSpy = spyOn(window, 'open');

    fixture.componentInstance.popOutProject(1, new Event('click'));

    expect(openSpy).toHaveBeenCalledWith('/projects/1/issues/4?session=abc&focus=1', '_blank');
  });

  it("popOutProject falls back to the project's base route when it is not the active project (#286)", () => {
    const fixture = init([PROJECT_A, PROJECT_B]);
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    httpMock.expectOne('/api/projects/2/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    flushConsoles();
    fixture.componentInstance.selected = { projectId: 1, issueNumber: 4 };
    const openSpy = spyOn(window, 'open');

    fixture.componentInstance.popOutProject(2, new Event('click'));

    expect(openSpy).toHaveBeenCalledWith('/projects/2/issues?focus=1', '_blank');
  });

  it('clicking the pop-out control does not select the project (#286)', () => {
    const fixture = init();
    flushTree(1, tree());
    fixture.detectChanges();
    spyOn(window, 'open');
    const emitted: number[] = [];
    fixture.componentInstance.projectSelected.subscribe((id) => emitted.push(id));

    (fixture.nativeElement.querySelector('.section-header .pop-out') as HTMLElement).click();

    expect(emitted).toEqual([]);
  });

  it('dragging a project section reorders it immediately and persists the new order (#541)', () => {
    const fixture = init([PROJECT_A, PROJECT_B]);
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    httpMock.expectOne('/api/projects/2/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    flushConsoles();

    fixture.componentInstance.onProjectSectionDrop({ previousIndex: 0, currentIndex: 1 } as unknown as CdkDragDrop<Section[]>);

    expect(fixture.componentInstance.projectSections.map((s) => s.project.id)).toEqual([2, 1]);
    httpMock.expectOne('/api/projects/order').flush(null);
  });

  it('dropping a project section back on its own position is a no-op (#541)', () => {
    const fixture = init([PROJECT_A, PROJECT_B]);
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    httpMock.expectOne('/api/projects/2/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    flushConsoles();

    fixture.componentInstance.onProjectSectionDrop({ previousIndex: 0, currentIndex: 0 } as unknown as CdkDragDrop<Section[]>);

    expect(fixture.componentInstance.projectSections.map((s) => s.project.id)).toEqual([1, 2]);
    httpMock.expectNone('/api/projects/order');
  });

  it('a failed persist reloads to fall back to whatever order the server actually kept (#541)', () => {
    const fixture = init([PROJECT_A, PROJECT_B]);
    httpMock.expectOne('/api/projects/1/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    httpMock.expectOne('/api/projects/2/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    flushConsoles();

    fixture.componentInstance.onProjectSectionDrop({ previousIndex: 0, currentIndex: 1 } as unknown as CdkDragDrop<Section[]>);
    httpMock.expectOne('/api/projects/order').error(new ProgressEvent('network error'));

    httpMock.expectOne('/api/projects').flush([PROJECT_A, PROJECT_B]);
    flushTree(1, tree());
    httpMock.expectOne('/api/projects/2/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    flushConsoles();
    expect(fixture.componentInstance.projectSections.map((s) => s.project.id)).toEqual([1, 2]);
  });

  it('a focused project only loads and renders that one project, never fetching another (#286)', () => {
    const fixture = TestBed.createComponent(SidenavComponent);
    fixture.componentInstance.focusedProjectId = 2;
    fixture.detectChanges();

    httpMock.expectOne('/api/projects').flush([PROJECT_A, PROJECT_B]);
    httpMock.expectOne('/api/projects/2/issues/tree').flush({ nodes: tree(), github: GITHUB_OK });
    flushConsoles();
    httpMock.expectOne('/api/usage').flush(EMPTY_USAGE);

    httpMock.expectNone('/api/projects/1/issues/tree');
    expect(fixture.componentInstance.projectSections.map((s) => s.project.id)).toEqual([2]);
  });
});
