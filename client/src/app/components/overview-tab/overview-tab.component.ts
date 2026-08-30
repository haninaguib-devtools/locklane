import { Component, EventEmitter, Input, Output } from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import DOMPurify from 'dompurify';
import { marked } from 'marked';
import { CheckRun, GhIssue, IssueDetail, ResumeSession } from '../../models/issue.model';
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
    if (!this.repoWebUrl || !this.detail?.recordPath) {
      return null;
    }
    // Once the issue ships, its wip/* branch is deleted (t-ship), so a shipped/closed
    // issue always links against main even when the detail still carries the old branch.
    const ref = this.issue.state === 'CLOSED' ? 'main' : (this.detail.branch ?? 'main');
    return `${this.repoWebUrl}/blob/${ref}/${this.detail.recordPath}`;
  }

  /** The PR's own Checks tab -- where the summary line points (#397). */
  get checksUrl(): string | null {
    return this.prUrl ? `${this.prUrl}/checks` : null;
  }

  checkRuns(detail: IssueDetail): CheckRun[] {
    return detail.checks.runs ?? [];
  }

  checkMarker(run: CheckRun): string {
    switch (run.state) {
      case 'passing':
        return '\u2713';
      case 'failing':
        return '\u2715';
      default:
        return '\u25cf';
    }
  }

  checksLabel(detail: IssueDetail): string {
    const { passing, failing, pending } = detail.checks;
    if (passing + failing + pending === 0) {
      return 'no CI runs';
    }
    const parts: string[] = [];
    if (failing > 0) {
      parts.push(`${failing} failing`);
    }
    if (passing > 0) {
      parts.push(`${passing} passing`);
    }
    if (pending > 0) {
      parts.push(`${pending} running`);
    }
    return parts.join(', ');
  }

  branchLabel(detail: IssueDetail): string {
    if (!detail.branch) {
      return 'no branch';
    }
    const draft = detail.prDraft ? ', draft' : '';
    return `${detail.branch} · PR #${detail.prNumber} (${(detail.prState ?? '').toLowerCase()}${draft})`;
  }
}
