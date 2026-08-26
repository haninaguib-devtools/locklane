import { Component, Input } from '@angular/core';
import { GhIssue } from '../../models/issue.model';

@Component({
  selector: 'app-issue-header',
  standalone: true,
  templateUrl: './issue-header.component.html',
  styleUrl: './issue-header.component.css',
})
export class IssueHeaderComponent {
  @Input({ required: true }) issue!: GhIssue;

  // One line, truncated: the body's first paragraph, headings stripped, collapsed
  // to a single line.
  get shortDescription(): string {
    const firstParagraph = this.issue.body
      .replace(/^#{1,6}\s.*$/gm, '')
      .split(/\n\s*\n/)
      .map((p) => p.trim())
      .find((p) => p.length > 0);
    const oneLine = (firstParagraph ?? '').replace(/\s+/g, ' ').trim();
    return oneLine.length > 140 ? oneLine.slice(0, 139) + '…' : oneLine;
  }
}
