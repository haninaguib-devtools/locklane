import { Component, EventEmitter, Input, Output } from '@angular/core';

@Component({
  selector: 'app-worktree-tabs',
  standalone: true,
  templateUrl: './worktree-tabs.component.html',
  styleUrl: './worktree-tabs.component.css',
})
export class WorktreeTabsComponent {
  @Input() worktreeIds: string[] = [];
  @Input() selected: string | null = null;
  @Input() starting = false;
  @Output() selectedChange = new EventEmitter<string>();
  @Output() start = new EventEmitter<void>();

  select(id: string): void {
    this.selectedChange.emit(id);
  }
}
