import { Component, EventEmitter, Input, Output } from '@angular/core';
import { Agent } from '../../services/agent-store';
import { ConfirmDialogComponent } from '../confirm-dialog/confirm-dialog.component';
import { ConsoleTab, OVERVIEW_TAB_ID } from './console-labels';

export interface OpenConsoleRequest {
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
  // The issue page pins an Overview tab first; the project-console page (#178)
  // has none — every console there is its own top-level tab.
  @Input() overview = true;
  // Read from Settings (#219) by the caller: neither the issue page's Console
  // button (#318) nor the project-console tab strip's "+" (#256) has an agent
  // picker of its own — both launch a new console with this agent directly.
  @Input() defaultAgent: Agent = 'claude';
  // The label on the open button — "+" everywhere except the issue page (#318),
  // which spells it out as "Console" now that it launches one specific thing.
  @Input() openLabel = '+';
  // The issue page (#318): a live console already ties the button's job (open
  // *the* worktree console for this issue) to state that's visible in the tab
  // strip itself, so the button hides rather than sitting there doing nothing
  // useful. The project-console strip keeps "+" visible to start more consoles.
  @Input() hideOpenWhenActive = false;
  @Output() selectedChange = new EventEmitter<string>();
  @Output() open = new EventEmitter<OpenConsoleRequest>();
  @Output() close = new EventEmitter<string>();

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

  // Whether the open button renders at all — hidden once the issue page (#318)
  // already has a live console for this issue, since the button's whole job is
  // opening/reusing that one console and the tab strip already shows it is open.
  get showOpenButton(): boolean {
    return !this.hideOpenWhenActive || this.tabs.length === 0;
  }

  // The "+" / "Console" button: there is nothing left to ask (#341 retired the
  // only other place a console could run, the project's main checkout), so it
  // always launches immediately with the default agent — the issue page's
  // Console button (#318), or the project-console strip's own scratch worktree
  // (#256/#314).
  plusClicked(): void {
    this.open.emit({ agent: this.defaultAgent });
  }
}
