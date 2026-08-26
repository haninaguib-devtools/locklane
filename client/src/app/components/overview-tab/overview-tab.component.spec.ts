import { OverviewTabComponent } from './overview-tab.component';
import { GhIssue, IssueDetail } from '../../models/issue.model';

describe('OverviewTabComponent', () => {
  function issue(overrides: Partial<GhIssue> = {}): GhIssue {
    return {
      number: 42,
      title: 'T',
      state: 'OPEN',
      labels: [],
      body: 'the full body',
      createdAt: '',
      updatedAt: '',
      ...overrides,
    };
  }

  function detail(overrides: Partial<IssueDetail>): IssueDetail {
    return {
      number: 42,
      recordPath: null,
      checks: { passing: 0, failing: 0, pending: 0 },
      branch: null,
      prNumber: null,
      prState: null,
      prDraft: false,
      flowSteps: [],
      ...overrides,
    };
  }

  it('reports no CI runs when there are no checks at all', () => {
    const c = new OverviewTabComponent();
    expect(c.checksLabel(detail({}))).toBe('no CI runs');
  });

  it('leads with failures when any check failed', () => {
    const c = new OverviewTabComponent();
    const d = detail({ checks: { passing: 2, failing: 1, pending: 0 } });
    expect(c.checksLabel(d)).toBe('1 failing / 2 passing');
  });

  it('reports pending checks when nothing failed but some are still running', () => {
    const c = new OverviewTabComponent();
    const d = detail({ checks: { passing: 2, failing: 0, pending: 1 } });
    expect(c.checksLabel(d)).toBe('2 passing, 1 pending');
  });

  it('reports all-green when everything passed', () => {
    const c = new OverviewTabComponent();
    const d = detail({ checks: { passing: 3, failing: 0, pending: 0 } });
    expect(c.checksLabel(d)).toBe('3 checks green');
  });

  it('reports no branch when there is no PR', () => {
    const c = new OverviewTabComponent();
    expect(c.branchLabel(detail({}))).toBe('no branch');
  });

  it('formats the branch, PR number, state, and draft flag', () => {
    const c = new OverviewTabComponent();
    const d = detail({ branch: 'wip/1-slug', prNumber: 9, prState: 'OPEN', prDraft: true });
    expect(c.branchLabel(d)).toBe('wip/1-slug · PR #9 (open, draft)');
  });

  it('omits the draft marker for a non-draft PR', () => {
    const c = new OverviewTabComponent();
    const d = detail({ branch: 'wip/1-slug', prNumber: 9, prState: 'MERGED', prDraft: false });
    expect(c.branchLabel(d)).toBe('wip/1-slug · PR #9 (merged)');
  });

  it('has no issue or PR url without a repo web url', () => {
    const c = new OverviewTabComponent();
    c.issue = issue();
    c.detail = detail({ prNumber: 9 });
    expect(c.issueUrl).toBeNull();
    expect(c.prUrl).toBeNull();
  });

  it('builds the issue url from the repo web url and issue number', () => {
    const c = new OverviewTabComponent();
    c.issue = issue({ number: 42 });
    c.repoWebUrl = 'https://github.com/org/repo';
    expect(c.issueUrl).toBe('https://github.com/org/repo/issues/42');
  });

  it('builds the PR url from the repo web url and PR number', () => {
    const c = new OverviewTabComponent();
    c.issue = issue();
    c.detail = detail({ prNumber: 9 });
    c.repoWebUrl = 'https://github.com/org/repo';
    expect(c.prUrl).toBe('https://github.com/org/repo/pull/9');
  });

  it('has no PR url when there is no PR yet', () => {
    const c = new OverviewTabComponent();
    c.issue = issue();
    c.detail = detail({});
    c.repoWebUrl = 'https://github.com/org/repo';
    expect(c.prUrl).toBeNull();
  });
});
