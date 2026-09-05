import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { IssuesService } from './issues.service';
import { GhIssue, IssueDetail, ResumeSession, TreeNode } from '../models/issue.model';

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
      checks: { passing: 0, failing: 0, pending: 0, runs: [] },
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
      { number: 1, title: 'Initiative', kind: 'INITIATIVE', state: 'OPEN', hasActiveBranch: false, labels: [], children: [] },
    ];
    service.tree(1).subscribe((result) => expect(result).toEqual(tree));

    const req = httpMock.expectOne('/api/projects/1/issues/tree');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.has('fresh')).toBeFalse();
    req.flush({ nodes: tree, github: { failing: false, failure: null, lastSuccessAt: null } });
  });

  it('treeWithStatus returns the tree together with the GitHub refresh outcome (#619)', () => {
    const response = {
      nodes: [] as TreeNode[],
      github: { failing: true, failure: 'HTTP 401: Bad credentials', lastSuccessAt: '2026-09-02T10:00:00Z' },
    };
    service.treeWithStatus(1).subscribe((result) => expect(result).toEqual(response));

    httpMock.expectOne('/api/projects/1/issues/tree').flush(response);
  });

  it('passes fresh=true to bypass the cache when asked', () => {
    service.tree(1, true).subscribe();

    const req = httpMock.expectOne((r) => r.url === '/api/projects/1/issues/tree');
    expect(req.request.params.get('fresh')).toBe('true');
    req.flush({ nodes: [], github: { failing: false, failure: null, lastSuccessAt: null } });
  });

  it('notifies subscribers when a project is marked stale', () => {
    let notified: number | undefined;
    service.onProjectStale.subscribe((projectId) => (notified = projectId));

    service.notifyProjectStale(7);

    expect(notified).toBe(7);
  });

  it('fetches worktree ids from GET /api/projects/{projectId}/issues/{number}/worktrees', () => {
    service.worktrees(1, 5).subscribe((result) => expect(result).toEqual(['1-5-slug']));

    const req = httpMock.expectOne('/api/projects/1/issues/5/worktrees');
    expect(req.request.method).toBe('GET');
    req.flush(['1-5-slug']);
  });

  it('starts a worktree session via POST /api/projects/{projectId}/issues/{number}/worktrees (#341: always a worktree)', () => {
    service
      .startSession(1, 5)
      .subscribe((result) => expect(result).toEqual({ worktreeId: '1-5-slug', workingDirectory: '/tmp/repo-5' }));

    const req = httpMock.expectOne((r) => r.url === '/api/projects/1/issues/5/worktrees');
    expect(req.request.method).toBe('POST');
    req.flush({ worktreeId: '1-5-slug', workingDirectory: '/tmp/repo-5' });
  });

  it('fetches past sessions from GET /api/projects/{projectId}/issues/{number}/resume-sessions (#103)', () => {
    const sessions: ResumeSession[] = [
      {
        worktreeId: '1-5-slug',
        tool: 'claude',
        toolLabel: 'Claude',
        resumeId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
        capturedAt: '2026-08-27T10:00:00Z',
        title: null,
      },
    ];
    service.resumeSessions(1, 5).subscribe((result) => expect(result).toEqual(sessions));

    const req = httpMock.expectOne('/api/projects/1/issues/5/resume-sessions');
    expect(req.request.method).toBe('GET');
    req.flush(sessions);
  });

  it('reopens a past session via POST .../resume-sessions/reopen with the original console id (#103)', () => {
    service
      .reopenSession(1, 5, '1-5-slug')
      .subscribe((result) =>
        expect(result).toEqual({ worktreeId: '1-5-resume-a1b2c3d4', workingDirectory: '/tmp/repo-5' }),
      );

    const req = httpMock.expectOne((r) => r.url === '/api/projects/1/issues/5/resume-sessions/reopen');
    expect(req.request.method).toBe('POST');
    expect(req.request.params.get('from')).toBe('1-5-slug');
    req.flush({ worktreeId: '1-5-resume-a1b2c3d4', workingDirectory: '/tmp/repo-5' });
  });

  it('closes a session via DELETE /api/projects/{projectId}/issues/{number}/worktrees/{worktreeId}', () => {
    service.closeSession(1, 5, '1-5-slug').subscribe();

    const req = httpMock.expectOne('/api/projects/1/issues/5/worktrees/1-5-slug');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });
});
