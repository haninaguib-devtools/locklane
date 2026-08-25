import { Component, EventEmitter, Input, OnInit, Output, inject } from '@angular/core';
import { GhIssue } from '../../models/issue.model';
import { IssuesService } from '../../services/issues.service';

// Deliberately minimal (#3's Non-goals: the sidenav/issue list itself is out of
// scope, "stays as it is today") — but there was no existing sidenav in this fresh
// repo to leave alone, so this is just enough real, GitHub-backed navigation for the
// console-first main area to have something to select. See the task record for the
// decision (paused mid-task to open #15/#16 for the data this needed).
@Component({
  selector: 'app-sidenav',
  standalone: true,
  templateUrl: './sidenav.component.html',
  styleUrl: './sidenav.component.css',
})
export class SidenavComponent implements OnInit {
  private readonly issuesService = inject(IssuesService);

  @Input() selected: number | null = null;
  @Output() selectedChange = new EventEmitter<number>();

  issues: GhIssue[] = [];
  loading = true;
  error = false;

  ngOnInit(): void {
    this.issuesService.list().subscribe({
      next: (issues) => {
        this.issues = issues;
        this.loading = false;
      },
      error: () => {
        this.loading = false;
        this.error = true;
      },
    });
  }

  select(issue: GhIssue): void {
    this.selectedChange.emit(issue.number);
  }
}
