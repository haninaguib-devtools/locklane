import { Component, EventEmitter, Input, Output } from '@angular/core';
import { IssueDetail } from '../../models/issue.model';

@Component({
  selector: 'app-details-popup',
  standalone: true,
  templateUrl: './details-popup.component.html',
  styleUrl: './details-popup.component.css',
})
export class DetailsPopupComponent {
  @Input() detail: IssueDetail | null = null;
  @Output() closed = new EventEmitter<void>();

  checksLabel(detail: IssueDetail): string {
    const { passing, failing, pending } = detail.checks;
    if (passing + failing + pending === 0) {
      return 'no CI runs';
    }
    if (failing > 0) {
      return `${failing} failing / ${passing} passing`;
    }
    return pending > 0 ? `${passing} passing, ${pending} pending` : `${passing} checks green`;
  }

  branchLabel(detail: IssueDetail): string {
    if (!detail.branch) {
      return 'no branch';
    }
    const draft = detail.prDraft ? ', draft' : '';
    return `${detail.branch} · PR #${detail.prNumber} (${(detail.prState ?? '').toLowerCase()}${draft})`;
  }
}
