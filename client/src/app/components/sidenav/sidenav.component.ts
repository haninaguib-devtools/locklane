import { Component, EventEmitter, HostListener, Input, OnInit, Output, inject } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TreeNode } from '../../models/issue.model';
import { IssuesService } from '../../services/issues.service';
import { PinStore } from '../../services/pin-store';
import { CollapseStore } from '../../services/collapse-store';
import { filterPinnedTree, filterTree } from './tree-filter';

// Deliberately minimal in #3 (the sidenav/issue list was out of scope there) --
// this task adds resizable width (owned by the parent, see AppComponent), a
// pinned section, initiative nesting fed by #21's tree endpoint, and a filter,
// referencing the old app's behavior only (see the task record).
@Component({
  selector: 'app-sidenav',
  standalone: true,
  imports: [FormsModule, NgTemplateOutlet],
  templateUrl: './sidenav.component.html',
  styleUrl: './sidenav.component.css',
})
export class SidenavComponent implements OnInit {
  private readonly issuesService = inject(IssuesService);
  private readonly pinStore = inject(PinStore);
  private readonly collapseStore = inject(CollapseStore);

  @Input() selected: number | null = null;
  @Output() selectedChange = new EventEmitter<number>();

  private tree: TreeNode[] = [];
  loading = true;
  error = false;

  // Neither persists across reloads, matching the old app (#22's Goal).
  filterText = '';
  hideShipped = true;

  openMenuFor: number | null = null;

  ngOnInit(): void {
    this.issuesService.tree().subscribe({
      next: (tree) => {
        this.tree = tree;
        this.loading = false;
      },
      error: () => {
        this.loading = false;
        this.error = true;
      },
    });
  }

  get pinnedNodes(): TreeNode[] {
    const pinnedNumbers = new Set(this.pinStore.list());
    const byNumber = new Map(this.flatten(this.tree).map((n) => [n.number, n]));
    const ordered = this.pinStore
      .list()
      .map((num) => byNumber.get(num))
      .filter((n): n is TreeNode => !!n)
      // A child that is *also* individually pinned gets its own top-level pinned
      // entry instead of being duplicated inside its pinned parent.
      .map((n) =>
        n.children.length > 0
          ? { ...n, children: n.children.filter((c) => !pinnedNumbers.has(c.number)) }
          : n,
      );
    // hideShipped never removes a pin, only the text filter can -- see tree-filter.ts.
    return filterPinnedTree(ordered, this.filterText, this.hideShipped);
  }

  get mainNodes(): TreeNode[] {
    const pinnedNumbers = new Set(this.pinStore.list());
    const topLevel = this.tree
      .filter((n) => !pinnedNumbers.has(n.number))
      // A pinned child moves to the Pinned section entirely -- it disappears from
      // its (unpinned) parent's nested children here too, not just avoiding
      // duplication within the Pinned section itself.
      .map((n) =>
        n.children.length > 0
          ? { ...n, children: n.children.filter((c) => !pinnedNumbers.has(c.number)) }
          : n,
      );
    return filterTree(topLevel, this.filterText, this.hideShipped);
  }

  isCollapsed(node: TreeNode): boolean {
    // A fold never hides a filter match: an active filter always shows everything
    // it matched, regardless of stored fold state.
    return this.hasActiveFilter() ? false : this.collapseStore.isCollapsed(node.number);
  }

  toggleCollapse(node: TreeNode, event: Event): void {
    event.stopPropagation();
    this.collapseStore.toggle(node.number);
  }

  isPinned(issueNumber: number): boolean {
    return this.pinStore.isPinned(issueNumber);
  }

  togglePin(issueNumber: number, event: Event): void {
    event.stopPropagation();
    this.pinStore.toggle(issueNumber);
    this.openMenuFor = null;
  }

  toggleMenu(issueNumber: number, event: Event): void {
    event.stopPropagation();
    this.openMenuFor = this.openMenuFor === issueNumber ? null : issueNumber;
  }

  @HostListener('document:click')
  closeMenu(): void {
    this.openMenuFor = null;
  }

  select(issueNumber: number): void {
    this.selectedChange.emit(issueNumber);
  }

  private hasActiveFilter(): boolean {
    return this.filterText.trim().length > 0;
  }

  private flatten(nodes: TreeNode[]): TreeNode[] {
    return nodes.flatMap((n) => [n, ...n.children]);
  }
}
