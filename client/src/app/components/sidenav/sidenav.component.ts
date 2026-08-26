import { Component, EventEmitter, HostListener, OnInit, Output, Input, inject } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { forkJoin, map, of, switchMap } from 'rxjs';
import { Project, TreeNode } from '../../models/issue.model';
import { IssuesService } from '../../services/issues.service';
import { ProjectsService } from '../../services/projects.service';
import { PinStore } from '../../services/pin-store';
import { CollapseStore } from '../../services/collapse-store';
import { ProjectSectionStore } from '../../services/project-section-store';
import { filterPinnedTree, filterTree } from './tree-filter';

/** One issue, resolved to the project id it's selected/pinned/collapsed within (#44). */
export interface ProjectIssue {
  projectId: number;
  issueNumber: number;
}

interface Section {
  project: Project;
  tree: TreeNode[];
}

interface PinnedGroup {
  project: Project;
  nodes: TreeNode[];
}

// One collapsible section per project (#44), replacing the single "CASES" heading
// #3 shipped when the app only ever managed one project. Pinning/collapsing/
// selection all now carry a project id alongside the issue number, since the same
// issue number can appear in more than one project's section at once.
@Component({
  selector: 'app-sidenav',
  standalone: true,
  imports: [FormsModule, NgTemplateOutlet],
  templateUrl: './sidenav.component.html',
  styleUrl: './sidenav.component.css',
})
export class SidenavComponent implements OnInit {
  private readonly projectsService = inject(ProjectsService);
  private readonly issuesService = inject(IssuesService);
  private readonly pinStore = inject(PinStore);
  private readonly collapseStore = inject(CollapseStore);
  private readonly projectSectionStore = inject(ProjectSectionStore);

  @Input() selected: ProjectIssue | null = null;
  @Output() selectedChange = new EventEmitter<ProjectIssue>();

  private sections: Section[] = [];
  loading = true;
  refreshing = false;
  error = false;

  // Neither persists across reloads, matching the old app (#22's Goal).
  filterText = '';
  hideShipped = true;

  private openMenuFor: string | null = null;

  ngOnInit(): void {
    this.load(() => (this.loading = false));
  }

  refresh(): void {
    if (this.refreshing) {
      return;
    }
    this.refreshing = true;
    this.load(() => (this.refreshing = false));
  }

  private load(onDone: () => void): void {
    this.projectsService
      .list()
      .pipe(
        switchMap((projects) =>
          projects.length === 0
            ? of([] as Section[])
            : forkJoin(
                projects.map((project) =>
                  this.issuesService.tree(project.id).pipe(map((tree): Section => ({ project, tree }))),
                ),
              ),
        ),
      )
      .subscribe({
        next: (sections) => {
          this.sections = sections;
          this.error = false;
          onDone();
        },
        error: () => {
          this.error = true;
          onDone();
        },
      });
  }

  get projectSections(): Section[] {
    return this.sections;
  }

  get pinnedGroups(): PinnedGroup[] {
    const groups: PinnedGroup[] = [];
    for (const section of this.sections) {
      const pinnedForProject = this.pinStore.list().filter((p) => p.projectId === section.project.id);
      if (pinnedForProject.length === 0) {
        continue;
      }
      const pinnedNumbers = new Set(pinnedForProject.map((p) => p.issueNumber));
      const byNumber = new Map(this.flatten(section.tree).map((n) => [n.number, n]));
      const ordered = pinnedForProject
        .map((p) => byNumber.get(p.issueNumber))
        .filter((n): n is TreeNode => !!n)
        // A child that is *also* individually pinned gets its own top-level pinned
        // entry instead of being duplicated inside its pinned parent.
        .map((n) =>
          n.children.length > 0
            ? { ...n, children: n.children.filter((c) => !pinnedNumbers.has(c.number)) }
            : n,
        );
      // hideShipped never removes a pin, only the text filter can -- see tree-filter.ts.
      const nodes = filterPinnedTree(ordered, this.filterText, this.hideShipped);
      if (nodes.length > 0) {
        groups.push({ project: section.project, nodes });
      }
    }
    return groups;
  }

  mainNodesFor(section: Section): TreeNode[] {
    const pinnedNumbers = new Set(
      this.pinStore
        .list()
        .filter((p) => p.projectId === section.project.id)
        .map((p) => p.issueNumber),
    );
    const topLevel = section.tree
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

  isProjectCollapsed(projectId: number): boolean {
    return this.projectSectionStore.isCollapsed(projectId);
  }

  toggleProjectCollapse(projectId: number, event: Event): void {
    event.stopPropagation();
    this.projectSectionStore.toggle(projectId);
  }

  isCollapsed(projectId: number, node: TreeNode): boolean {
    // A fold never hides a filter match: an active filter always shows everything
    // it matched, regardless of stored fold state.
    return this.hasActiveFilter() ? false : this.collapseStore.isCollapsed(projectId, node.number);
  }

  toggleCollapse(projectId: number, node: TreeNode, event: Event): void {
    event.stopPropagation();
    this.collapseStore.toggle(projectId, node.number);
  }

  isPinned(projectId: number, issueNumber: number): boolean {
    return this.pinStore.isPinned(projectId, issueNumber);
  }

  togglePin(projectId: number, issueNumber: number, event: Event): void {
    event.stopPropagation();
    this.pinStore.toggle(projectId, issueNumber);
    this.openMenuFor = null;
  }

  isMenuOpen(projectId: number, issueNumber: number): boolean {
    return this.openMenuFor === this.menuKey(projectId, issueNumber);
  }

  toggleMenu(projectId: number, issueNumber: number, event: Event): void {
    event.stopPropagation();
    const key = this.menuKey(projectId, issueNumber);
    this.openMenuFor = this.openMenuFor === key ? null : key;
  }

  @HostListener('document:click')
  closeMenu(): void {
    this.openMenuFor = null;
  }

  isSelected(projectId: number, issueNumber: number): boolean {
    return this.selected !== null && this.selected.projectId === projectId && this.selected.issueNumber === issueNumber;
  }

  select(projectId: number, issueNumber: number): void {
    this.selectedChange.emit({ projectId, issueNumber });
  }

  private menuKey(projectId: number, issueNumber: number): string {
    return `${projectId}-${issueNumber}`;
  }

  private hasActiveFilter(): boolean {
    return this.filterText.trim().length > 0;
  }

  private flatten(nodes: TreeNode[]): TreeNode[] {
    return nodes.flatMap((n) => [n, ...n.children]);
  }
}
