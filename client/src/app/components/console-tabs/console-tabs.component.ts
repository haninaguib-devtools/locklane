import { Component, EventEmitter, HostListener, Input, Output } from '@angular/core';
import { Agent } from '../../services/agent-store';
import { AgentPickerComponent } from '../agent-picker/agent-picker.component';
import { ConsoleTab, OVERVIEW_TAB_ID } from './console-labels';

export interface OpenConsoleRequest {
  worktree: boolean;
  agent: Agent;
}

@Component({
  selector: 'app-console-tabs',
  standalone: true,
  imports: [AgentPickerComponent],
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
  @Output() selectedChange = new EventEmitter<string>();
  @Output() open = new EventEmitter<OpenConsoleRequest>();
  @Output() close = new EventEmitter<string>();

  pickerOpen = false;
  location: 'main' | 'worktree' = 'worktree';
  agent: Agent = 'claude';

  select(id: string): void {
    this.selectedChange.emit(id);
  }

  closeTab(id: string, event: Event): void {
    event.stopPropagation();
    if (!confirm('Close this console? The session will be terminated and cannot be reattached.')) {
      return;
    }
    this.close.emit(id);
  }

  togglePicker(): void {
    this.pickerOpen = !this.pickerOpen;
  }

  confirmOpen(): void {
    this.pickerOpen = false;
    this.open.emit({ worktree: this.location === 'worktree', agent: this.agent });
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
