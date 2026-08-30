import { Component, ElementRef, EventEmitter, Input, Output, ViewChild } from '@angular/core';
import { Agent } from '../../services/agent-store';
import { ConfirmDialogComponent } from '../confirm-dialog/confirm-dialog.component';
import { ConsoleTab, OVERVIEW_TAB_ID, tabText } from './console-labels';

export interface OpenConsoleRequest {
  agent: Agent;
}

/** A tab renamed in place (#393): an empty `name` means "clear it". */
export interface RenameConsoleRequest {
  id: string;
  name: string;
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
  // Exposed for the template: the user's own name for a tab, or its auto label (#393).
  readonly tabText = tabText;

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
  // #393: only the project console page lets a user name its tabs; the issue page
  // keeps the auto labels, so renaming is opt-in per call site rather than on
  // everywhere the shared strip is used.
  @Input() renamable = false;
  @Output() selectedChange = new EventEmitter<string>();
  @Output() open = new EventEmitter<OpenConsoleRequest>();
  @Output() close = new EventEmitter<string>();
  @Output() rename = new EventEmitter<RenameConsoleRequest>();

  // The longest name accepted, mirroring the engine's own bound (#393) so an
  // over-long name is prevented here rather than rejected after a round trip.
  readonly maxNameLength = 60;

  // The tab currently being renamed, and the text in its field. Only one tab is
  // ever editable at a time -- starting a rename elsewhere commits nothing and
  // simply moves the field.
  renamingId: string | null = null;
  draftName = '';

  // The rename field only exists while a tab is being renamed, so this setter runs
  // exactly when it appears -- the moment to put the cursor in it. `autofocus` does
  // nothing here: the attribute is only honoured when the document itself loads, not
  // when an element is added to a page that is already showing.
  @ViewChild('nameInput')
  set nameInput(input: ElementRef<HTMLInputElement> | undefined) {
    if (input) {
      input.nativeElement.focus();
      input.nativeElement.select();
    }
  }

  // The tab whose close is awaiting confirmation in the app-styled dialog (#231),
  // replacing the synchronous native `confirm()` this used to block on.
  pendingCloseId: string | null = null;

  select(id: string): void {
    this.selectedChange.emit(id);
  }

  /**
   * Double-clicking a tab turns its label into a field (#393), seeded with the name
   * the user already gave it -- never with the auto-generated label, so committing
   * an untouched field on a never-named tab is not a rename to the label's text.
   */
  startRename(tab: ConsoleTab, event: Event): void {
    if (!this.renamable) {
      return;
    }
    event.stopPropagation();
    this.renamingId = tab.id;
    this.draftName = tab.name ?? '';
  }

  /** Commits the field, trimmed; an empty result clears the name (#393). */
  commitRename(): void {
    const id = this.renamingId;
    if (id === null) {
      return;
    }
    this.renamingId = null;
    this.rename.emit({ id, name: this.draftName.trim() });
  }

  /** Abandons the field, changing nothing. */
  cancelRename(): void {
    this.renamingId = null;
    this.draftName = '';
  }

  onRenameInput(value: string): void {
    this.draftName = value;
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
