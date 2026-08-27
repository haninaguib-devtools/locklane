import { Component, EventEmitter, Input, Output } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ResumeSession } from '../../models/issue.model';

// The Overview tab's list of past Claude/Codex conversations captured in this
// issue's consoles (#102/#103) — each row reopenable as a new console that
// resumes that exact conversation. Display-only otherwise: no editing or
// deleting saved sessions here.
@Component({
  selector: 'app-session-list',
  standalone: true,
  imports: [DatePipe],
  templateUrl: './session-list.component.html',
  styleUrl: './session-list.component.css',
})
export class SessionListComponent {
  @Input({ required: true }) sessions: ResumeSession[] = [];
  /** Disables the reopen buttons while a console is already being started. */
  @Input() busy = false;
  @Output() reopen = new EventEmitter<ResumeSession>();
}
