import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { SidenavComponent } from './sidenav.component';
import { PinStore } from '../../services/pin-store';
import { CollapseStore } from '../../services/collapse-store';
import { TreeNode } from '../../models/issue.model';

describe('SidenavComponent', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    localStorage.removeItem('locklane.pinnedIssues');
    localStorage.removeItem('locklane.collapsedInitiatives');
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

  /** Sets the required projectId input and runs the ngOnChanges that triggers the load. */
  function init(): ReturnType<typeof TestBed.createComponent<SidenavComponent>> {
    const fixture = TestBed.createComponent(SidenavComponent);
    fixture.componentInstance.projectId = 1;
    fixture.componentInstance.ngOnChanges({
      projectId: { currentValue: 1, previousValue: null, firstChange: true, isFirstChange: () => true },
    });
    return fixture;
  }

  it('loads the tree from the backend', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/issues/tree').flush(tree());

    expect(fixture.componentInstance.mainNodes).toHaveSize(2);
    expect(fixture.componentInstance.loading).toBeFalse();
  });

  it('reports an error state when the fetch fails', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/issues/tree').error(new ProgressEvent('network error'));

    expect(fixture.componentInstance.error).toBeTrue();
    expect(fixture.componentInstance.loading).toBeFalse();
  });

  it('emits the selected issue number', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/issues/tree').flush(tree());

    let emitted: number | undefined;
    fixture.componentInstance.selectedChange.subscribe((n) => (emitted = n));
    fixture.componentInstance.select(4);

    expect(emitted).toBe(4);
  });

  it('a pinned issue moves out of mainNodes and into pinnedNodes', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/issues/tree').flush(tree());

    TestBed.inject(PinStore).toggle(4);
    fixture.detectChanges();

    expect(fixture.componentInstance.pinnedNodes.map((n) => n.number)).toEqual([4]);
    expect(fixture.componentInstance.mainNodes.map((n) => n.number)).toEqual([1]);
  });

  it('pinning a child task removes it from its unpinned parent in mainNodes too', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/issues/tree').flush(tree());
    fixture.componentInstance.hideShipped = false;

    TestBed.inject(PinStore).toggle(2); // pin child A; initiative #1 stays unpinned

    const initiativeInMain = fixture.componentInstance.mainNodes.find((n) => n.number === 1)!;
    expect(initiativeInMain.children.map((c) => c.number)).toEqual([3]); // 2 moved to Pinned
    expect(fixture.componentInstance.pinnedNodes.map((n) => n.number)).toEqual([2]);
  });

  it('a pinned child is excluded from its pinned parent to avoid duplication', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/issues/tree').flush(tree());

    fixture.componentInstance.hideShipped = false; // isolate de-duplication from ship-filtering
    const pins = TestBed.inject(PinStore);
    pins.toggle(1); // pin the initiative
    pins.toggle(2); // also pin one of its children
    fixture.detectChanges();

    const pinned = fixture.componentInstance.pinnedNodes;
    expect(pinned.map((n) => n.number)).toEqual([2, 1]); // most-recently-pinned first
    const initiativeEntry = pinned.find((n) => n.number === 1)!;
    expect(initiativeEntry.children.map((c) => c.number)).toEqual([3]); // 2 removed, not duplicated
  });

  it('hideShipped never removes a pinned entry itself, even if it is closed', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/issues/tree').flush(tree());

    TestBed.inject(PinStore).toggle(3); // pin the CLOSED child directly
    fixture.detectChanges();

    expect(fixture.componentInstance.hideShipped).toBeTrue();
    expect(fixture.componentInstance.pinnedNodes.map((n) => n.number)).toEqual([3]);
  });

  it('isCollapsed reflects CollapseStore, but an active filter always shows children', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/issues/tree').flush(tree());
    const initiative = fixture.componentInstance.mainNodes[0];

    TestBed.inject(CollapseStore).toggle(1);
    expect(fixture.componentInstance.isCollapsed(initiative)).toBeTrue();

    fixture.componentInstance.filterText = 'child';
    expect(fixture.componentInstance.isCollapsed(initiative)).toBeFalse();
  });

  it('hideShipped is on by default and hides the closed child', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/issues/tree').flush(tree());

    expect(fixture.componentInstance.hideShipped).toBeTrue();
    const initiative = fixture.componentInstance.mainNodes[0];
    expect(initiative.children.map((c) => c.number)).toEqual([2]);
  });

  it('refresh() re-fetches the tree and updates the list in place', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/issues/tree').flush(tree());

    fixture.componentInstance.refresh();
    expect(fixture.componentInstance.refreshing).toBeTrue();

    const updated: TreeNode[] = [
      ...tree(),
      { number: 5, title: 'New from GitHub', kind: 'TASK', state: 'OPEN', children: [] },
    ];
    httpMock.expectOne('/api/projects/1/issues/tree').flush(updated);

    expect(fixture.componentInstance.refreshing).toBeFalse();
    expect(fixture.componentInstance.mainNodes.map((n) => n.number)).toEqual([1, 4, 5]);
  });

  it('refresh() is a no-op while a refresh is already in flight', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/issues/tree').flush(tree());

    fixture.componentInstance.refresh();
    fixture.componentInstance.refresh();

    // Only one in-flight request: the second refresh() call was a no-op.
    httpMock.expectOne('/api/projects/1/issues/tree').flush(tree());
    expect(fixture.componentInstance.refreshing).toBeFalse();
  });

  it('refresh() surfaces an error without clearing the existing list', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/issues/tree').flush(tree());

    fixture.componentInstance.refresh();
    httpMock.expectOne('/api/projects/1/issues/tree').error(new ProgressEvent('network error'));

    expect(fixture.componentInstance.refreshing).toBeFalse();
    expect(fixture.componentInstance.error).toBeTrue();
    expect(fixture.componentInstance.mainNodes.map((n) => n.number)).toEqual([1, 4]);
  });
});
