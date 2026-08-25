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

  it('lists issues from GET /api/issues', () => {
    const issues: GhIssue[] = [
      { number: 1, title: 'First', state: 'OPEN', labels: [], body: '', createdAt: '', updatedAt: '' },
    ];
    service.list().subscribe((result) => expect(result).toEqual(issues));

    const req = httpMock.expectOne('/api/issues');
    expect(req.request.method).toBe('GET');
    req.flush(issues);
  });

  it('fetches a single issue from GET /api/issues/{number}', () => {
    const issue: GhIssue = {
      number: 5,
      title: 'Fifth',
      state: 'OPEN',
      labels: [],
      body: '',
      createdAt: '',
      updatedAt: '',
    };
    service.get(5).subscribe((result) => expect(result).toEqual(issue));

    const req = httpMock.expectOne('/api/issues/5');
    expect(req.request.method).toBe('GET');
    req.flush(issue);
  });

  it('fetches issue detail from GET /api/issues/{number}/detail', () => {
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
    service.detail(5).subscribe((result) => expect(result).toEqual(detail));

    const req = httpMock.expectOne('/api/issues/5/detail');
    expect(req.request.method).toBe('GET');
    req.flush(detail);
  });

  it('fetches the issue tree from GET /api/issues/tree', () => {
    const tree: TreeNode[] = [
      { number: 1, title: 'Initiative', kind: 'INITIATIVE', state: 'OPEN', children: [] },
    ];
    service.tree().subscribe((result) => expect(result).toEqual(tree));

    const req = httpMock.expectOne('/api/issues/tree');
    expect(req.request.method).toBe('GET');
    req.flush(tree);
  });

  it('fetches worktree ids from GET /api/issues/{number}/worktrees', () => {
    service.worktrees(5).subscribe((result) => expect(result).toEqual(['5-slug']));

    const req = httpMock.expectOne('/api/issues/5/worktrees');
    expect(req.request.method).toBe('GET');
    req.flush(['5-slug']);
  });

  it('starts a session via POST /api/issues/{number}/worktrees', () => {
    service
      .startSession(5)
      .subscribe((result) => expect(result).toEqual({ worktreeId: '5-slug', workingDirectory: '/tmp/repo-5' }));

    const req = httpMock.expectOne('/api/issues/5/worktrees');
    expect(req.request.method).toBe('POST');
    req.flush({ worktreeId: '5-slug', workingDirectory: '/tmp/repo-5' });
  });
});
