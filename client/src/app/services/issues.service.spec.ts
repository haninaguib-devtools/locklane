import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { IssuesService } from './issues.service';
import { GhIssue, IssueDetail, TreeNode } from '../models/issue.model';

describe('IssuesService', () => {
  let service: IssuesService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(IssuesService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lists issues from GET /api/projects/{projectId}/issues', () => {
    const issues: GhIssue[] = [
      { number: 1, title: 'First', state: 'OPEN', labels: [], body: '', createdAt: '', updatedAt: '' },
    ];
    service.list(1).subscribe((result) => expect(result).toEqual(issues));

    const req = httpMock.expectOne('/api/projects/1/issues');
    expect(req.request.method).toBe('GET');
    req.flush(issues);
  });

  it('fetches a single issue from GET /api/projects/{projectId}/issues/{number}', () => {
    const issue: GhIssue = {
      number: 5,
      title: 'Fifth',
      state: 'OPEN',
      labels: [],
      body: '',
      createdAt: '',
      updatedAt: '',
    };
    service.get(1, 5).subscribe((result) => expect(result).toEqual(issue));

    const req = httpMock.expectOne('/api/projects/1/issues/5');
    expect(req.request.method).toBe('GET');
    req.flush(issue);
  });

  it('fetches issue detail from GET /api/projects/{projectId}/issues/{number}/detail', () => {
    const detail: IssueDetail = {
      number: 5,
      recordPath: null,
      checks: { passing: 0, failing: 0, pending: 0 },
      branch: null,
      prNumber: null,
      prState: null,
      prDraft: false,
      flowSteps: [],
    };
    service.detail(1, 5).subscribe((result) => expect(result).toEqual(detail));

    const req = httpMock.expectOne('/api/projects/1/issues/5/detail');
    expect(req.request.method).toBe('GET');
    req.flush(detail);
  });

  it('fetches the issue tree from GET /api/projects/{projectId}/issues/tree', () => {
    const tree: TreeNode[] = [
      { number: 1, title: 'Initiative', kind: 'INITIATIVE', state: 'OPEN', children: [] },
    ];
    service.tree(1).subscribe((result) => expect(result).toEqual(tree));

    const req = httpMock.expectOne('/api/projects/1/issues/tree');
    expect(req.request.method).toBe('GET');
    req.flush(tree);
  });

  it('fetches worktree ids from GET /api/projects/{projectId}/issues/{number}/worktrees', () => {
    service.worktrees(1, 5).subscribe((result) => expect(result).toEqual(['1-5-slug']));

    const req = httpMock.expectOne('/api/projects/1/issues/5/worktrees');
    expect(req.request.method).toBe('GET');
    req.flush(['1-5-slug']);
  });

  it('starts a worktree session via POST /api/projects/{projectId}/issues/{number}/worktrees by default', () => {
    service
      .startSession(1, 5)
      .subscribe((result) => expect(result).toEqual({ worktreeId: '1-5-slug', workingDirectory: '/tmp/repo-5' }));

    const req = httpMock.expectOne((r) => r.url === '/api/projects/1/issues/5/worktrees');
    expect(req.request.method).toBe('POST');
    expect(req.request.params.get('worktree')).toBe('true');
    req.flush({ worktreeId: '1-5-slug', workingDirectory: '/tmp/repo-5' });
  });

  it('passes worktree=false when the console targets the main checkout', () => {
    service.startSession(1, 5, false).subscribe();

    const req = httpMock.expectOne((r) => r.url === '/api/projects/1/issues/5/worktrees');
    expect(req.request.params.get('worktree')).toBe('false');
    req.flush({ worktreeId: '1-5-main-a1b2c3d4', workingDirectory: '/tmp/repo' });
  });

  it('closes a session via DELETE /api/projects/{projectId}/issues/{number}/worktrees/{worktreeId}', () => {
    service.closeSession(1, 5, '1-5-slug').subscribe();

    const req = httpMock.expectOne('/api/projects/1/issues/5/worktrees/1-5-slug');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });
});
