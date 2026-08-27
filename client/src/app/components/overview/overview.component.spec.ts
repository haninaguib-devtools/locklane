import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { OverviewComponent, aggregateCounts } from './overview.component';
import { Project, TreeNode } from '../../models/issue.model';

describe('OverviewComponent', () => {
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
    TestBed.configureTestingModule({
      imports: [OverviewComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function init(projects: Project[]): ReturnType<typeof TestBed.createComponent<OverviewComponent>> {
    const fixture = TestBed.createComponent(OverviewComponent);
    fixture.detectChanges();
    httpMock.expectOne('/api/projects').flush(projects);
    return fixture;
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

  it('shows the "select a project" empty state when there are no projects', () => {
    const fixture = init([]);
    fixture.detectChanges();

    expect(fixture.componentInstance.rows).toEqual([]);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('select a project to begin');
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
    httpMock.expectOne('/api/projects/1/issues/tree').flush(tree());
    httpMock.expectOne('/api/projects/2/issues/tree').flush([
      { number: 9, title: 'Only in B', kind: 'TASK', state: 'OPEN', hasActiveBranch: false, labels: [], children: [] },
    ]);
    fixture.detectChanges();

    expect(fixture.componentInstance.totals).toEqual({ total: 5, open: 3, closed: 2, initiatives: 1, tasks: 4 });
    const values = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.count .value'),
    ).map((el) => el.textContent?.trim());
    expect(values).toEqual(['2', '5', '3', '2', '1', '4']);
  });

  it('links a READY project to its issues page', () => {
    const fixture = init([PROJECT_A]);
    httpMock.expectOne('/api/projects/1/issues/tree').flush(tree());
    fixture.detectChanges();

    const link = (fixture.nativeElement as HTMLElement).querySelector<HTMLAnchorElement>('a.project-row');
    expect(link!.getAttribute('href')).toBe('/projects/1/issues');
  });

  it('a non-READY project has no href, even though its (empty) tree is still fetched like project-summary\'s (#85)', () => {
    const cloning: Project = { ...PROJECT_A, status: 'CLONING' };
    const fixture = init([cloning]);
    httpMock.expectOne('/api/projects/1/issues/tree').flush([]);
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
    httpMock.expectOne('/api/projects/1/issues/tree').flush(tree());
    fixture.detectChanges();

    const label = (fixture.nativeElement as HTMLElement).querySelector('.completion-label');
    expect(label!.textContent?.trim()).toBe('2/4 closed');
  });
});
