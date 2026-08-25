import { DetailsPopupComponent } from './details-popup.component';
import { IssueDetail } from '../../models/issue.model';

describe('DetailsPopupComponent', () => {
  function detail(overrides: Partial<IssueDetail>): IssueDetail {
    return {
      number: 1,
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
    const c = new DetailsPopupComponent();
    expect(c.checksLabel(detail({}))).toBe('no CI runs');
  });

  it('leads with failures when any check failed', () => {
    const c = new DetailsPopupComponent();
    const d = detail({ checks: { passing: 2, failing: 1, pending: 0 } });
    expect(c.checksLabel(d)).toBe('1 failing / 2 passing');
  });

  it('reports pending checks when nothing failed but some are still running', () => {
    const c = new DetailsPopupComponent();
    const d = detail({ checks: { passing: 2, failing: 0, pending: 1 } });
    expect(c.checksLabel(d)).toBe('2 passing, 1 pending');
  });

  it('reports all-green when everything passed', () => {
    const c = new DetailsPopupComponent();
    const d = detail({ checks: { passing: 3, failing: 0, pending: 0 } });
    expect(c.checksLabel(d)).toBe('3 checks green');
  });

  it('reports no branch when there is no PR', () => {
    const c = new DetailsPopupComponent();
    expect(c.branchLabel(detail({}))).toBe('no branch');
  });

  it('formats the branch, PR number, state, and draft flag', () => {
    const c = new DetailsPopupComponent();
    const d = detail({ branch: 'wip/1-slug', prNumber: 9, prState: 'OPEN', prDraft: true });
    expect(c.branchLabel(d)).toBe('wip/1-slug · PR #9 (open, draft)');
  });

  it('omits the draft marker for a non-draft PR', () => {
    const c = new DetailsPopupComponent();
    const d = detail({ branch: 'wip/1-slug', prNumber: 9, prState: 'MERGED', prDraft: false });
    expect(c.branchLabel(d)).toBe('wip/1-slug · PR #9 (merged)');
  });
});
