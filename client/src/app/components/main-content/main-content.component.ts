import { Component, Input, OnChanges, SimpleChanges, inject } from '@angular/core';
import { GhIssue, IssueDetail } from '../../models/issue.model';
import { IssuesService } from '../../services/issues.service';
import { IssueHeaderComponent } from '../issue-header/issue-header.component';
import { FlowStripComponent } from '../flow-strip/flow-strip.component';
import { WorktreeTabsComponent } from '../worktree-tabs/worktree-tabs.component';
import { TerminalComponent } from '../terminal/terminal.component';

@Component({
  selector: 'app-main-content',
  standalone: true,
  imports: [IssueHeaderComponent, FlowStripComponent, WorktreeTabsComponent, TerminalComponent],
  templateUrl: './main-content.component.html',
  styleUrl: './main-content.component.css',
})
export class MainContentComponent implements OnChanges {
  private readonly issuesService = inject(IssuesService);

  @Input({ required: true }) issueNumber!: number;

  issue: GhIssue | null = null;
  detail: IssueDetail | null = null;
  worktreeIds: string[] = [];
  selectedWorktree: string | null = null;

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['issueNumber']) {
      this.load(this.issueNumber);
    }
  }

  private load(number: number): void {
    this.issue = null;
    this.detail = null;
    this.worktreeIds = [];
    this.selectedWorktree = null;

    this.issuesService.get(number).subscribe((issue) => {
      this.issue = issue;
    });
    this.issuesService.detail(number).subscribe((detail) => {
      this.detail = detail;
    });
    this.issuesService.worktrees(number).subscribe((ids) => {
      this.worktreeIds = ids;
      this.selectedWorktree = ids[0] ?? null;
    });
  }

  selectWorktree(id: string): void {
    this.selectedWorktree = id;
  }
}
