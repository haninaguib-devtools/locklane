import { TestBed } from '@angular/core/testing';
import { DomSanitizer } from '@angular/platform-browser';
import { OverviewTabComponent } from './overview-tab.component';
import { CheckRun, GhIssue, IssueDetail } from '../../models/issue.model';

describe('OverviewTabComponent', () => {
  const fakeSanitizer = {
    bypassSecurityTrustHtml: (html: string) => html,
  } as unknown as DomSanitizer;

  function component(): OverviewTabComponent {
    return new OverviewTabComponent(fakeSanitizer);
  }

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
      checks: { passing: 0, failing: 0, pending: 0, runs: [] },
      branch: null,
      prNumber: null,
      prState: null,
      prDraft: false,
      flowSteps: [],
      ...overrides,
    };
  }

  it('reports no CI runs when there are no checks at all', () => {
    const c = component();
    expect(c.checksLabel(detail({}))).toBe('no CI runs');
  });

  it('leads with failures, then passes, then what is still running', () => {
    const c = component();
    const d = detail({ checks: { passing: 8, failing: 1, pending: 1, runs: [] } });
    expect(c.checksLabel(d)).toBe('1 failing, 8 passing, 1 running');
  });

  it('leaves out the kinds with nothing in them', () => {
    const c = component();
    expect(c.checksLabel(detail({ checks: { passing: 3, failing: 0, pending: 0, runs: [] } })))
      .toBe('3 passing');
    expect(c.checksLabel(detail({ checks: { passing: 2, failing: 0, pending: 1, runs: [] } })))
      .toBe('2 passing, 1 running');
  });

  it('points the summary line at the PR checks tab, and nowhere without a PR', () => {
    const c = component();
    c.issue = issue();
    c.repoWebUrl = 'https://github.com/o/r';
    c.detail = detail({ prNumber: 7 });
    expect(c.checksUrl).toBe('https://github.com/o/r/pull/7/checks');
    c.detail = detail({});
    expect(c.checksUrl).toBeNull();
  });

  it('reports no branch when there is no PR', () => {
    const c = component();
    expect(c.branchLabel(detail({}))).toBe('no branch');
  });

  it('formats the branch, PR number, state, and draft flag', () => {
    const c = component();
    const d = detail({ branch: 'wip/1-slug', prNumber: 9, prState: 'OPEN', prDraft: true });
    expect(c.branchLabel(d)).toBe('wip/1-slug · PR #9 (open, draft)');
  });

  it('omits the draft marker for a non-draft PR', () => {
    const c = component();
    const d = detail({ branch: 'wip/1-slug', prNumber: 9, prState: 'MERGED', prDraft: false });
    expect(c.branchLabel(d)).toBe('wip/1-slug · PR #9 (merged)');
  });

  it('has no issue, PR, or record url without a repo web url', () => {
    const c = component();
    c.issue = issue();
    c.detail = detail({ prNumber: 9, recordPath: 'docs/tasks/000100/163-slug.md' });
    expect(c.issueUrl).toBeNull();
    expect(c.prUrl).toBeNull();
    expect(c.recordUrl).toBeNull();
  });

  it('builds the issue url from the repo web url and issue number', () => {
    const c = component();
    c.issue = issue({ number: 42 });
    c.repoWebUrl = 'https://github.com/org/repo';
    expect(c.issueUrl).toBe('https://github.com/org/repo/issues/42');
  });

  it('builds the PR url from the repo web url and PR number', () => {
    const c = component();
    c.issue = issue();
    c.detail = detail({ prNumber: 9 });
    c.repoWebUrl = 'https://github.com/org/repo';
    expect(c.prUrl).toBe('https://github.com/org/repo/pull/9');
  });

  it('has no PR url when there is no PR yet', () => {
    const c = component();
    c.issue = issue();
    c.detail = detail({});
    c.repoWebUrl = 'https://github.com/org/repo';
    expect(c.prUrl).toBeNull();
  });

  it('builds the record url from the repo web url, the branch, and the record path', () => {
    const c = component();
    c.issue = issue({ number: 42 });
    c.repoWebUrl = 'https://github.com/org/repo';
    c.detail = detail({ branch: 'wip/163-slug', recordPath: 'docs/tasks/000100/163-slug.md' });
    expect(c.recordUrl).toBe(
      'https://github.com/org/repo/blob/wip/163-slug/docs/tasks/000100/163-slug.md',
    );
    expect(c.recordUrl).not.toBe(c.issueUrl);
  });

  it('uses main for the record url once the issue is closed/shipped, even with a branch', () => {
    const c = component();
    c.issue = issue({ number: 42, state: 'CLOSED' });
    c.repoWebUrl = 'https://github.com/org/repo';
    c.detail = detail({ branch: 'wip/163-slug', recordPath: 'docs/tasks/000100/163-slug.md' });
    expect(c.recordUrl).toBe(
      'https://github.com/org/repo/blob/main/docs/tasks/000100/163-slug.md',
    );
  });

  it('falls back to main for the record url when there is no branch yet', () => {
    const c = component();
    c.issue = issue({ number: 42 });
    c.repoWebUrl = 'https://github.com/org/repo';
    c.detail = detail({ recordPath: 'docs/tasks/000100/163-slug.md' });
    expect(c.recordUrl).toBe(
      'https://github.com/org/repo/blob/main/docs/tasks/000100/163-slug.md',
    );
  });

  it('has no record url when there is no record yet', () => {
    const c = component();
    c.issue = issue();
    c.detail = detail({});
    c.repoWebUrl = 'https://github.com/org/repo';
    expect(c.recordUrl).toBeNull();
  });

  it('has no body html when the issue body is empty', () => {
    const c = component();
    c.issue = issue({ body: '' });
    expect(c.bodyHtml).toBeNull();
  });

  it('renders markdown headings and lists as HTML rather than raw text', () => {
    const c = component();
    c.issue = issue({ body: '## Heading\n\n- one\n- two' });
    const html = c.bodyHtml as unknown as string;
    expect(html).toContain('<h2>Heading</h2>');
    expect(html).toContain('<li>one</li>');
    expect(html).not.toContain('##');
  });

  it('sanitizes a script tag out of the rendered body', () => {
    const c = component();
    c.issue = issue({ body: 'hello<script>alert(1)</script>world' });
    const html = c.bodyHtml as unknown as string;
    expect(html).not.toContain('<script>');
    expect(html).not.toContain('alert(1)');
  });
});

describe('OverviewTabComponent details list', () => {
  beforeEach(() => TestBed.configureTestingModule({ imports: [OverviewTabComponent] }));

  function render(
    labels: string[],
    checks: IssueDetail['checks'] = { passing: 0, failing: 0, pending: 0, runs: [] },
    prNumber: number | null = null
  ): HTMLElement {
    const fixture = TestBed.createComponent(OverviewTabComponent);
    fixture.componentRef.setInput('issue', {
      number: 42,
      title: 'T',
      state: 'OPEN',
      labels,
      body: '',
      createdAt: '',
      updatedAt: '',
    } as GhIssue);
    fixture.componentRef.setInput('detail', {
      number: 42,
      recordPath: null,
      checks,
      branch: null,
      prNumber,
      prState: null,
      prDraft: false,
      flowSteps: [],
    } as IssueDetail);
    fixture.componentRef.setInput('repoWebUrl', 'https://github.com/o/r');
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('lists where the work lives first, then the record, then the checks', () => {
    const terms = Array.from(render([]).querySelectorAll('.details dt')).map((dt) =>
      dt.textContent!.trim()
    );
    expect(terms).toEqual(['branch & PR', 'record', 'checks']);
  });

  it('renders one row per check, each linked to its own run', () => {
    const runs: CheckRun[] = [
      { name: 'build', state: 'failing', url: 'https://ci/1' },
      { name: 'record', state: 'passing', url: 'https://ci/2' },
      { name: 'lint', state: 'pending', url: null },
    ];
    const el = render([], { passing: 1, failing: 1, pending: 1, runs }, 7);

    const rows = Array.from(el.querySelectorAll('.checks .check'));
    expect(rows.map((r) => r.querySelector('a, span:not(.marker):not(.visually-hidden)')!.textContent!.trim()))
      .toEqual(['build', 'record', 'lint']);
    expect(rows.map((r) => r.querySelector('a')?.getAttribute('href') ?? null))
      .toEqual(['https://ci/1', 'https://ci/2', null]);
    expect(rows[0].querySelector('.marker')!.classList).toContain('marker-failing');
    expect(rows[2].querySelector('a')).toBeNull();
  });

  it('says no CI runs and lists nothing when the PR has no checks', () => {
    const el = render([]);
    expect(el.querySelector('.checks')).toBeNull();
    expect(el.querySelectorAll('.details dd')[2].textContent!.trim()).toBe('no CI runs');
  });

  it('no longer carries a tags row, even when the issue has labels', () => {
    const terms = Array.from(render(['client']).querySelectorAll('.details dt')).map((dt) =>
      dt.textContent!.trim()
    );
    expect(terms).not.toContain('tags');
  });
});
