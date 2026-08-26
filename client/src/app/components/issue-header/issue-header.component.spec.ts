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
