import { Component, EventEmitter, HostListener, Input, Output } from '@angular/core';
import { Agent } from '../../services/agent-store';
import { ConfirmDialogComponent } from '../confirm-dialog/confirm-dialog.component';
import { ConsoleTab, OVERVIEW_TAB_ID } from './console-labels';

export interface OpenConsoleRequest {
  worktree: boolean;
  agent: Agent;
}

@Component({
  selector: 'app-console-tabs',
  standalone: true,
  imports: [ConfirmDialogComponent],
  templateUrl: './console-tabs.component.html',
  styleUrl: './console-tabs.component.css',
})
export class ConsoleTabsComponent {
  // Exposed for the template's Overview tab, pinned first in the same strip (#96).
  readonly overviewId = OVERVIEW_TAB_ID;

  @Input() tabs: ConsoleTab[] = [];
  @Input() selected: string | null = null;
  @Input() starting = false;
  // The issue page pins an Overview tab first and lets "+" choose main vs
  // worktree; the project-console page (#178) has neither — no overview, and
  // every console runs in the project's own checkout.
  @Input() overview = true;
  @Input() locationChoice = true;
  // Read from Settings (#219) by the caller: the locationChoice flow (#220) has
  // no agent picker of its own, and neither does the project-console tab strip's
  // "+" any more (#256) — both launch a new console with this agent directly.
  @Input() defaultAgent: Agent = 'claude';
  @Output() selectedChange = new EventEmitter<string>();
  @Output() open = new EventEmitter<OpenConsoleRequest>();
  @Output() close = new EventEmitter<string>();

  pickerOpen = false;
  location: 'main' | 'worktree' = 'worktree';

  // The tab whose close is awaiting confirmation in the app-styled dialog (#231),
  // replacing the synchronous native `confirm()` this used to block on.
  pendingCloseId: string | null = null;

  select(id: string): void {
    this.selectedChange.emit(id);
  }

  closeTab(id: string, event: Event): void {
    event.stopPropagation();
    this.pendingCloseId = id;
  }

  confirmClose(): void {
    const id = this.pendingCloseId;
    this.pendingCloseId = null;
    if (id !== null) {
      this.close.emit(id);
    }
  }

  cancelClose(): void {
    this.pendingCloseId = null;
  }

  // The "+" button (#256): the locationChoice flow still needs to ask where
  // (#220), so it opens that popup; the project-console flow has nothing left to
  // ask, so it launches immediately with the default agent, matching the
  // sidenav's one-click "+" (`openNewConsole`).
  plusClicked(): void {
    if (this.locationChoice) {
      this.pickerOpen = !this.pickerOpen;
    } else {
      this.open.emit({ worktree: false, agent: this.defaultAgent });
    }
  }

  // The locationChoice flow (#220): choosing where launches immediately, using
  // the Settings default agent instead of the removed agent picker + separate
  // "open" click.
  chooseLocation(location: 'main' | 'worktree'): void {
    this.pickerOpen = false;
    this.open.emit({ worktree: location === 'worktree', agent: this.defaultAgent });
  }

  // Bound to `document` (not the host) so a click anywhere else on the page reaches
  // it, including outside this component entirely.
  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent): void {
    if (!this.pickerOpen) {
      return;
    }
    const target = event.target as HTMLElement | null;
    if (!target?.closest('app-console-tabs')) {
      this.pickerOpen = false;
    }
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.pickerOpen = false;
  }
}
