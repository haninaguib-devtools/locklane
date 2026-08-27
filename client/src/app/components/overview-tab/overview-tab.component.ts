import { Component, EventEmitter, Input, Output } from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import DOMPurify from 'dompurify';
import { marked } from 'marked';
import { GhIssue, IssueDetail, ResumeSession } from '../../models/issue.model';
import { SessionListComponent } from '../session-list/session-list.component';

@Component({
  selector: 'app-overview-tab',
  standalone: true,
  imports: [SessionListComponent],
  templateUrl: './overview-tab.component.html',
  styleUrl: './overview-tab.component.css',
})
export class OverviewTabComponent {
  @Input({ required: true }) issue!: GhIssue;
  @Input() detail: IssueDetail | null = null;
  @Input() repoWebUrl: string | null = null;
  /** Past Claude/Codex conversations captured in this issue's consoles (#103). */
  @Input() sessions: ResumeSession[] = [];
  /** Disables reopening while a console is already being started. */
  @Input() busy = false;
  @Output() reopen = new EventEmitter<ResumeSession>();

  constructor(private readonly sanitizer: DomSanitizer) {}

  get bodyHtml(): SafeHtml | null {
    if (!this.issue.body) {
      return null;
    }
    const rawHtml = marked.parse(this.issue.body, { async: false });
    const safeHtml = DOMPurify.sanitize(rawHtml);
    return this.sanitizer.bypassSecurityTrustHtml(safeHtml);
  }

  get issueUrl(): string | null {
    return this.repoWebUrl ? `${this.repoWebUrl}/issues/${this.issue.number}` : null;
  }

  get prUrl(): string | null {
    return this.repoWebUrl && this.detail?.prNumber
      ? `${this.repoWebUrl}/pull/${this.detail.prNumber}`
      : null;
  }

  get recordUrl(): string | null {
    return this.repoWebUrl && this.detail?.recordPath
      ? `${this.repoWebUrl}/blob/${this.detail.branch ?? 'main'}/${this.detail.recordPath}`
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
