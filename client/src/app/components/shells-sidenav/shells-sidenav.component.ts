import { Component, EventEmitter, Input, OnChanges, Output } from '@angular/core';
import { OpenShell } from '../../services/shells.service';
import { Project } from '../../models/issue.model';
import { ShellRow, labelShells, shellRowText } from './shell-labels';

/**
 * One sidenav section: a project's name and its shells, labeled. `project` is the
 * full row from the projects list when the group's project is known there, null
 * otherwise — the `+` control (#448) needs its `workareaPath` to mint a
 * main-checkout shell, so a group rendered under the fallback name shows no `+`.
 */
interface ShellGroup {
  projectId: number;
  name: string;
  project: Project | null;
  rows: ShellRow[];
}

/**
 * The Shells window's sidenav (#446): every open shell, grouped by project and,
 * within a project, by issue or the project's main checkout — main-checkout shells
 * first, then issues ascending, each location keeping the listing's own
 * oldest-first order so labels stay stable as shells come and go elsewhere.
 * Selection and closing are the parent's job; this component only emits.
 */
@Component({
  selector: 'app-shells-sidenav',
  standalone: true,
  imports: [],
  templateUrl: './shells-sidenav.component.html',
  styleUrl: './shells-sidenav.component.css',
})
export class ShellsSidenavComponent implements OnChanges {
  @Input({ required: true }) shells: OpenShell[] = [];
  @Input() projects: Project[] = [];
  @Input() selected: string | null = null;
  @Output() selectShell = new EventEmitter<OpenShell>();
  @Output() closeShell = new EventEmitter<OpenShell>();
  /** The `+` on a project section (#448): open a new shell at this project's main checkout. */
  @Output() openMainShell = new EventEmitter<Project>();

  groups: ShellGroup[] = [];

  ngOnChanges(): void {
    const byProject = new Map<number, OpenShell[]>();
    for (const shell of this.shells) {
      const group = byProject.get(shell.projectId) ?? [];
      group.push(shell);
      byProject.set(shell.projectId, group);
    }
    const byId = new Map(this.projects.map((project) => [project.id, project]));
    this.groups = [...byProject.entries()].map(([projectId, shells]) => {
      const ordered = [
        ...shells.filter((shell) => shell.mainCheckout),
        ...shells
          .filter((shell) => !shell.mainCheckout)
          .sort((a, b) => (a.issueNumber ?? 0) - (b.issueNumber ?? 0)),
      ];
      const project = byId.get(projectId) ?? null;
      return {
        projectId,
        name: project?.name ?? `project ${projectId}`,
        project,
        rows: labelShells(ordered),
      };
    });
  }

  text(row: ShellRow): string {
    return shellRowText(row);
  }
}
