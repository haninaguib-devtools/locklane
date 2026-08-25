import { Component, EventEmitter, Input, Output } from '@angular/core';
import { Agent } from '../../services/agent-store';
import { ConsoleTab } from './console-labels';

export interface OpenConsoleRequest {
  worktree: boolean;
  agent: Agent;
}

@Component({
  selector: 'app-console-tabs',
  standalone: true,
  templateUrl: './console-tabs.component.html',
  styleUrl: './console-tabs.component.css',
})
export class ConsoleTabsComponent {
  @Input() tabs: ConsoleTab[] = [];
  @Input() selected: string | null = null;
  @Input() starting = false;
  @Output() selectedChange = new EventEmitter<string>();
  @Output() open = new EventEmitter<OpenConsoleRequest>();

  pickerOpen = false;
  location: 'main' | 'worktree' = 'worktree';
  agent: Agent = 'claude';

  select(id: string): void {
    this.selectedChange.emit(id);
  }

  togglePicker(): void {
    this.pickerOpen = !this.pickerOpen;
  }

  confirmOpen(): void {
    this.pickerOpen = false;
    this.open.emit({ worktree: this.location === 'worktree', agent: this.agent });
  }
}
