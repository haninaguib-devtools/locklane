import { Component, Input } from '@angular/core';
import { GhIssue, IssueDetail } from '../../models/issue.model';
import { DetailsPopupComponent } from '../details-popup/details-popup.component';

@Component({
  selector: 'app-issue-header',
  standalone: true,
  imports: [DetailsPopupComponent],
  templateUrl: './issue-header.component.html',
  styleUrl: './issue-header.component.css',
})
export class IssueHeaderComponent {
  @Input({ required: true }) issue!: GhIssue;
  @Input() detail: IssueDetail | null = null;

  popupOpen = false;

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

  togglePopup(): void {
    this.popupOpen = !this.popupOpen;
  }
}
