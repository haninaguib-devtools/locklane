import { TestBed } from '@angular/core/testing';
import { IssueHeaderComponent } from './issue-header.component';
import { GhIssue } from '../../models/issue.model';

describe('IssueHeaderComponent', () => {
  function issue(body: string): GhIssue {
    return { number: 1, title: 'T', state: 'OPEN', labels: [], body, createdAt: '', updatedAt: '' };
  }

  it('extracts the first paragraph after any heading, headings stripped', () => {
    const c = new IssueHeaderComponent();
    c.issue = issue('## Goal\n\nSomething the reader should see.\n\n## Done when\n\nother stuff');

    expect(c.shortDescription).toBe('Something the reader should see.');
  });

  it('collapses internal newlines to a single line', () => {
    const c = new IssueHeaderComponent();
    c.issue = issue('## Goal\n\nLine one\nline two\nline three');

    expect(c.shortDescription).toBe('Line one line two line three');
  });

  it('truncates a long description with an ellipsis', () => {
    const c = new IssueHeaderComponent();
    c.issue = issue('## Goal\n\n' + 'x'.repeat(200));

    expect(c.shortDescription.length).toBe(140);
    expect(c.shortDescription.endsWith('…')).toBeTrue();
  });

  it('is empty when the body has no paragraph text', () => {
    const c = new IssueHeaderComponent();
    c.issue = issue('## Goal\n\n## Done when\n');

    expect(c.shortDescription).toBe('');
  });
});

describe('IssueHeaderComponent rendering', () => {
  function gh(overrides: Partial<GhIssue> = {}): GhIssue {
    return {
      number: 7,
      title: 'Make it better',
      state: 'OPEN',
      labels: [],
      body: '## Goal\n\nA gist.',
      createdAt: '',
      updatedAt: '',
      ...overrides,
    };
  }

  function render(issue: GhIssue, repoWebUrl: string | null): HTMLElement {
    const fixture = TestBed.createComponent(IssueHeaderComponent);
    fixture.componentRef.setInput('issue', issue);
    fixture.componentRef.setInput('repoWebUrl', repoWebUrl);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  beforeEach(() => TestBed.configureTestingModule({ imports: [IssueHeaderComponent] }));

  it('links the title to the issue on GitHub, leaving the number outside the anchor', () => {
    const el = render(gh(), 'https://github.com/acme/widgets');

    const anchor = el.querySelector('h1 a') as HTMLAnchorElement;
    expect(anchor.getAttribute('href')).toBe('https://github.com/acme/widgets/issues/7');
    expect(anchor.getAttribute('target')).toBe('_blank');
    expect(anchor.getAttribute('rel')).toBe('noopener');
    expect(anchor.textContent!.trim()).toBe('Make it better');
    expect(el.querySelector('h1 a .number')).toBeNull();
    expect(el.querySelector('h1 .number')!.textContent!.trim()).toBe('#7');
  });

  it('renders the title as plain text when no repo web URL is known', () => {
    const el = render(gh(), null);

    expect(el.querySelector('h1 a')).toBeNull();
    expect(el.querySelector('h1')!.textContent).toContain('Make it better');
  });

  it('shows one pill per label', () => {
    const el = render(gh({ labels: ['client', 'enhancement'] }), null);

    const pills = Array.from(el.querySelectorAll('.tags .tag')).map((p) => p.textContent!.trim());
    expect(pills).toEqual(['client', 'enhancement']);
  });

  it('renders no tag list at all when the issue has no labels', () => {
    const el = render(gh({ labels: [] }), null);

    expect(el.querySelector('.tags')).toBeNull();
  });
});
