import { Component, Input } from '@angular/core';
import { GhIssue, IssueDetail } from '../../models/issue.model';
import { FlowStripComponent } from '../flow-strip/flow-strip.component';

@Component({
  selector: 'app-overview-tab',
  standalone: true,
  imports: [FlowStripComponent],
  templateUrl: './overview-tab.component.html',
  styleUrl: './overview-tab.component.css',
})
export class OverviewTabComponent {
  @Input({ required: true }) issue!: GhIssue;
  @Input() detail: IssueDetail | null = null;
  @Input() repoWebUrl: string | null = null;

  get issueUrl(): string | null {
    return this.repoWebUrl ? `${this.repoWebUrl}/issues/${this.issue.number}` : null;
  }

  get prUrl(): string | null {
    return this.repoWebUrl && this.detail?.prNumber
      ? `${this.repoWebUrl}/pull/${this.detail.prNumber}`
      : null;
  }

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
