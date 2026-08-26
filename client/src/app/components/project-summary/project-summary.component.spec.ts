import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ProjectSummaryComponent, countIssues } from './project-summary.component';
import { Project, TreeNode } from '../../models/issue.model';

describe('ProjectSummaryComponent', () => {
  let httpMock: HttpTestingController;

  const PROJECT: Project = {
    id: 1,
    name: 'proj-a',
    gitUrl: 'git@example.com:acme/proj-a.git',
    workareaPath: '/tmp/a',
    defaultBranch: 'main',
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
        children: [
          { number: 2, title: 'Child A', kind: 'TASK', state: 'OPEN', children: [] },
          { number: 3, title: 'Child B', kind: 'TASK', state: 'CLOSED', children: [] },
        ],
      },
      { number: 4, title: 'Standalone', kind: 'TASK', state: 'CLOSED', children: [] },
    ];
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ProjectSummaryComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  /** Creates the component for a project id and flushes both of its requests. */
  function init(
    projects: Project[] = [PROJECT],
    nodes: TreeNode[] = tree(),
    projectId = 1,
  ): ReturnType<typeof TestBed.createComponent<ProjectSummaryComponent>> {
    const fixture = TestBed.createComponent(ProjectSummaryComponent);
    fixture.componentRef.setInput('projectId', projectId);
    fixture.detectChanges();
    httpMock.expectOne('/api/projects').flush(projects);
    httpMock.expectOne(`/api/projects/${projectId}/issues/tree`).flush(nodes);
    fixture.detectChanges();
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
    httpMock
      .expectOne('/api/projects/1/issues/tree')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(fixture.componentInstance.error).toBe(false);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('proj-a');
    expect(text).toContain('could not load the issue counts');
  });

  it('reloads when the project id changes', () => {
    const fixture = init();
    fixture.componentRef.setInput('projectId', 2);
    fixture.detectChanges();
    httpMock.expectOne('/api/projects').flush([PROJECT, { ...PROJECT, id: 2, name: 'proj-b' }]);
    httpMock.expectOne('/api/projects/2/issues/tree').flush([]);
    fixture.detectChanges();

    expect(fixture.componentInstance.project?.name).toBe('proj-b');
    expect(fixture.componentInstance.counts?.total).toBe(0);
  });
});
