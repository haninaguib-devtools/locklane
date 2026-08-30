import { Component, EventEmitter, Input, Output } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ResumeSession } from '../../models/issue.model';

// The list of past Claude/Codex/OpenCode conversations captured in a set of
// consoles — an issue's, on the Overview tab (#102/#103), or a project's own,
// on the project console page (#372). Each row is reopenable as a new console
// that resumes that exact conversation. Display-only otherwise: no editing,
// renaming or deleting saved sessions here.
//
// Since #373 a row shows the short name the CLI generated for the conversation
// when there is one, and falls back to the captured timestamp when there is
// not — which is every row's display before #373, and still the display for a
// conversation too short to have been titled, a Codex older than v0.150.0, or
// a tool that is not installed.
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
