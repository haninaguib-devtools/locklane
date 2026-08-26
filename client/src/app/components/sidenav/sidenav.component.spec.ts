import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ProjectIssue, SidenavComponent } from './sidenav.component';
import { PinStore } from '../../services/pin-store';
import { CollapseStore } from '../../services/collapse-store';
import { ProjectSectionStore } from '../../services/project-section-store';
import { Project, TreeNode } from '../../models/issue.model';

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
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
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
        children: [
          { number: 2, title: 'Child A', kind: 'TASK', state: 'OPEN', children: [] },
          { number: 3, title: 'Child B', kind: 'TASK', state: 'CLOSED', children: [] },
        ],
      },
      { number: 4, title: 'Standalone', kind: 'TASK', state: 'OPEN', children: [] },
    ];
  }

  /** Creates the component and flushes its project list. */
  function init(projects: Project[] = [PROJECT_A]): ReturnType<typeof TestBed.createComponent<SidenavComponent>> {
    const fixture = TestBed.createComponent(SidenavComponent);
    fixture.detectChanges();
    httpMock.expectOne('/api/projects').flush(projects);
    return fixture;
  }

  function flushTree(projectId: number, nodes: TreeNode[]): void {
    httpMock.expectOne(`/api/projects/${projectId}/issues/tree`).flush(nodes);
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
      { number: 9, title: 'Only in B', kind: 'TASK', state: 'OPEN', children: [] },
    ]);

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

  it('emits the selected project and issue', () => {
    const fixture = init();
    flushTree(1, tree());

    let emitted: ProjectIssue | undefined;
    fixture.componentInstance.selectedChange.subscribe((e) => (emitted = e));
    fixture.componentInstance.select(1, 4);

    expect(emitted).toEqual({ projectId: 1, issueNumber: 4 });
  });

  it('isSelected only matches the exact project/issue pair', () => {
    const fixture = init([PROJECT_A, PROJECT_B]);
    httpMock.expectOne('/api/projects/1/issues/tree').flush(tree());
    httpMock.expectOne('/api/projects/2/issues/tree').flush(tree());
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
      { number: 5, title: 'New from GitHub', kind: 'TASK', state: 'OPEN', children: [] },
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
});
