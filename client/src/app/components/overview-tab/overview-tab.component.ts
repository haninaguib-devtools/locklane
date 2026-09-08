import { Component, EventEmitter, Input, OnChanges, Output, SimpleChanges } from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { CheckRun, GhIssue, IssueDetail, ResumeSession } from '../../models/issue.model';
import { SessionListComponent } from '../session-list/session-list.component';

@Component({
  selector: 'app-overview-tab',
  standalone: true,
  imports: [SessionListComponent],
  templateUrl: './overview-tab.component.html',
  styleUrl: './overview-tab.component.css',
})
export class OverviewTabComponent implements OnChanges {
  @Input({ required: true }) issue!: GhIssue;
  @Input() detail: IssueDetail | null = null;
  @Input() repoWebUrl: string | null = null;
  /** Past Claude/Codex conversations captured in this issue's agent sessions (#103). */
  @Input() sessions: ResumeSession[] = [];
  /** Disables reopening while an agent session is already being started. */
  @Input() busy = false;
  @Output() reopen = new EventEmitter<ResumeSession>();

  /**
   * Whether the checks row shows its per-check detail. Collapsed on every render --
   * deliberately not remembered across page loads or between issues (#414).
   */
  checksExpanded = false;

  toggleChecks(): void {
    this.checksExpanded = !this.checksExpanded;
  }

  /**
   * The issue body rendered from markdown, or null while there is no body or the
   * render is still in flight. Set once per issue change from ngOnChanges rather than
   * computed by a getter: the markdown renderer is imported lazily (below), which makes
   * the render asynchronous -- and the old getter re-parsed and re-sanitized the whole
   * body on every change-detection pass anyway.
   */
  bodyHtml: SafeHtml | null = null;

  /** Bumped per render so a slower earlier render never overwrites a newer one. */
  private renderSeq = 0;

  constructor(private readonly sanitizer: DomSanitizer) {}

  ngOnChanges(changes: SimpleChanges): Promise<void> {
    return changes['issue'] ? this.renderBody() : Promise.resolve();
  }

  private async renderBody(): Promise<void> {
    const seq = ++this.renderSeq;
    const body = this.issue.body;
    if (!body) {
      this.bodyHtml = null;
      return;
    }
    // `marked` and `dompurify` together are ~73 KB of the initial bundle for the one
    // place -- this tab -- that renders markdown, so they are imported dynamically
    // and land in their own lazy chunk (#809), the same way TerminalComponent loads
    // the xterm WebGL addon. The body simply appears once the chunk has loaded.
    const [{ marked }, { default: DOMPurify }] = await Promise.all([
      import('marked'),
      import('dompurify'),
    ]);
    if (seq !== this.renderSeq) {
      return;
    }
    const rawHtml = marked.parse(body, { async: false });
    const safeHtml = DOMPurify.sanitize(rawHtml);
    this.bodyHtml = this.sanitizer.bypassSecurityTrustHtml(safeHtml);
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

  /** The PR's own Checks tab -- a trailing link inside the expanded panel (#414). */
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
