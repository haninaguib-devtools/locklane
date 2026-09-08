import { Component, ElementRef, ViewChild, computed, effect, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { Observable, combineLatest, forkJoin, map, merge, of, switchMap } from 'rxjs';
import { AgentSessionsService, issueNumberFromSessionId } from '../../services/agent-sessions.service';
import { IssuesService } from '../../services/issues.service';
import { CurrentProjectService } from '../../services/current-project.service';
import { OpenProjectAgentSession, ProjectAgentSessionService } from '../../services/project-agent-session.service';
import { AgentStore } from '../../services/agent-store';
import { ActiveAgentSessionStore } from '../../services/active-agent-session-store';
import { AttentionStore } from '../../services/attention-store';
import { Project } from '../../models/issue.model';
import { labelProjectAgentSessions, tabText } from '../agent-session-tabs/agent-session-labels';

/**
 * An issue's own agent session (issueNumber set) or one of the project's own agent sessions
 * (#139/#177, issueNumber null -- there is no issue to jump to, and no per-issue
 * "active agent session" to remember). Carries its own project (#290) since entries now
 * span every project the user has, not just whichever one is currently selected.
 * `title` is the single line a row renders (#449) -- for a project agent session this
 * already includes the "Project - " prefix and the tab's own current text, read
 * from the same source (`tabText()`) the tab strip itself uses.
 */
export interface AgentSessionEntry {
  sessionId: string;
  projectId: number;
  issueNumber: number | null;
  title: string;
}

/** One project's entries, in the order `groups` below picks headings by (#290). */
export interface AgentSessionGroup {
  projectId: number;
  projectName: string;
  entries: { entry: AgentSessionEntry; index: number }[];
}

// The "Open Shells"-style header badge (#32): shows how many agent sessions are open
// across every project the user has (#290), narrowed to just one project's
// agent sessions only inside a popped-out single-project focused window (#309, #449),
// and a picker that jumps straight to one. Redesigned in #105 to match portstow's
// `open-shells` modal (scrim, focus trap, arrow/enter/escape) and to read
// `entries` off a reactive stream --
// `onOpened`/`onClosed` (#108) -- instead of a cached field only `refresh()` ever
// touched, which is what let the badge miss an opened agent session until something else
// happened to close.
@Component({
  selector: 'app-agent-session-indicator',
  standalone: true,
  templateUrl: './agent-session-indicator.component.html',
  styleUrl: './agent-session-indicator.component.css',
})
export class AgentSessionIndicatorComponent {
  private readonly currentProject = inject(CurrentProjectService);
  private readonly agentSessionsService = inject(AgentSessionsService);
  private readonly issuesService = inject(IssuesService);
  private readonly projectAgentSessionService = inject(ProjectAgentSessionService);
  private readonly agentStore = inject(AgentStore);
  private readonly activeAgentSessionStore = inject(ActiveAgentSessionStore);
  // The one shared "which sessions are waiting" store (#791), read by session id.
  private readonly attentionStore = inject(AttentionStore);
  private readonly router = inject(Router);

  @ViewChild('results') private readonly resultsRef?: ElementRef<HTMLElement>;
  @ViewChild('trigger') private readonly triggerRef?: ElementRef<HTMLElement>;

  // The project list itself comes from CurrentProjectService (#309), shared with
  // the header -- fetched once, not re-fetched when an agent session opens or closes;
  // AppComponent's own project-creation/deletion flows already refresh the
  // sidenav explicitly rather than relying on this widget to notice on its own.
  // Narrowed to just the current project only inside a popped-out focused window
  // (#449, `focusedProjectId`); every project otherwise, including while
  // browsing a specific project's pages in the ordinary window -- unlike the
  // header's own title, this no longer narrows off the raw route projectId.
  // Built from the service's own observables, not its signals, so this stays
  // synchronous the same way the widget's pre-#309 project fetch was -- a
  // signal-to-observable bridge only updates on the next change-detection tick.
  private readonly visibleProjects$: Observable<Project[]> = combineLatest([
    this.currentProject.projects$,
    this.currentProject.focusedProjectId$,
  ]).pipe(map(([projects, id]) => (id === null ? projects : projects.filter((project) => project.id === id))));

  private readonly visibleProjects = toSignal(this.visibleProjects$, { initialValue: [] as Project[] });

  readonly entries = toSignal(
    this.visibleProjects$.pipe(
      switchMap((projects) =>
        merge(of(null), this.agentSessionsService.onOpened, this.agentSessionsService.onClosed, this.agentSessionsService.onRenamed).pipe(
          switchMap(() => this.fetchEntries(projects)),
        ),
      ),
    ),
    { initialValue: [] as AgentSessionEntry[] },
  );

  // Headings are shown once the visible project set has more than one project,
  // regardless of how many of those projects currently have an open agent session --
  // otherwise headings would flicker in and out as agent sessions open/close elsewhere
  // while the project count stays the same (#290). Scoped to one project (#309),
  // this is never more than one, so headings never show for it.
  readonly showGroupHeadings = computed(() => this.visibleProjects().length > 1);

  readonly groups = computed<AgentSessionGroup[]>(() => {
    const projects = this.visibleProjects();
    const entries = this.entries();
    const byProject = new Map<number, AgentSessionEntry[]>();
    for (const entry of entries) {
      const list = byProject.get(entry.projectId);
      if (list) {
        list.push(entry);
      } else {
        byProject.set(entry.projectId, [entry]);
      }
    }
    let index = 0;
    const groups: AgentSessionGroup[] = [];
    for (const project of projects) {
      const projectEntries = byProject.get(project.id);
      if (!projectEntries) {
        continue;
      }
      groups.push({
        projectId: project.id,
        projectName: project.name,
        entries: projectEntries.map((entry) => ({ entry, index: index++ })),
      });
    }
    return groups;
  });

  readonly open = signal(false);
  readonly selected = signal(0);

  constructor() {
    // An agent session may close while the popup is open. Keep the selection valid, and
    // dismiss the popup once there is nothing left to show -- portstow's own
    // `open-shells.ts` does the same rather than leaving an empty modal behind.
    effect(() => {
      const count = this.entries().length;
      if (count === 0) {
        this.open.set(false);
      } else if (this.selected() >= count) {
        this.selected.set(count - 1);
      }
    });
  }

  /**
   * Whether any agent session shown here (#130) is waiting for the user's attention. The
   * store spans every project the user has; this only ever asks about the sessions
   * that also show up in `entries`, which already spans every project (#290).
   */
  hasWaitingEntry(): boolean {
    return this.entries().some((entry) => this.attentionStore.isWaiting(entry.sessionId));
  }

  toggle(): void {
    const next = !this.open();
    this.open.set(next);
    if (next) {
      this.selected.set(0);
      queueMicrotask(() => this.resultsRef?.nativeElement.focus());
    } else {
      queueMicrotask(() => this.triggerRef?.nativeElement.focus());
    }
  }

  // With exactly one agent session open, the trigger is a direct link (#215) -- jump
  // straight there instead of opening a picker with a single row in it. Now
  // measured against the total across every project (#290).
  onTriggerClick(): void {
    const entries = this.entries();
    if (entries.length === 1) {
      this.jumpTo(entries[0]);
    } else {
      this.toggle();
    }
  }

  close(): void {
    this.open.set(false);
    queueMicrotask(() => this.triggerRef?.nativeElement.focus());
  }

  // Arrow/enter/escape mirrors portstow's `open-shells.ts` `onKey`. Tab is
  // swallowed rather than left to leave the dialog: the results list is the only
  // focusable element in the popup, so trapping focus here is just keeping it put --
  // an equivalent to `cdkTrapFocus` without pulling in `@angular/cdk`.
  onKey(event: KeyboardEvent): void {
    switch (event.key) {
      case 'Escape':
        event.preventDefault();
        this.close();
        break;
      case 'ArrowDown':
        event.preventDefault();
        this.move(1);
        break;
      case 'ArrowUp':
        event.preventDefault();
        this.move(-1);
        break;
      case 'Enter':
        event.preventDefault();
        this.openSelected();
        break;
      case 'Tab':
        event.preventDefault();
        break;
    }
  }

  openSelected(): void {
    const entry = this.entries()[this.selected()];
    if (entry) {
      this.jumpTo(entry);
    }
  }

  // Navigates to the entry's own project (#290) -- not necessarily whichever
  // project happens to be selected elsewhere in the app.
  jumpTo(entry: AgentSessionEntry): void {
    this.open.set(false);
    if (entry.issueNumber !== null) {
      this.activeAgentSessionStore.set(entry.issueNumber, entry.sessionId);
      this.router.navigate(['/projects', entry.projectId, 'issues', entry.issueNumber]);
    } else {
      // 'console' is the route path segment -- a compatibility surface kept under ADR-112.
      this.router.navigate(['/projects', entry.projectId, 'console'], {
        queryParams: { session: entry.sessionId },
      });
    }
  }

  private move(delta: number): void {
    const count = this.entries().length;
    if (count > 0) {
      this.selected.set((this.selected() + delta + count) % count);
    }
  }

  // Fans the existing per-project agent sessions/issues calls out across every project
  // the user has (#290), the same forkJoin pattern sidenav.component.ts's own
  // refreshAgentSessionIndicators() already uses.
  private fetchEntries(projects: Project[]): Observable<AgentSessionEntry[]> {
    return projects.length === 0
      ? of([])
      : forkJoin(projects.map((project) => this.fetchProjectEntries(project))).pipe(map((perProject) => perProject.flat()));
  }

  private fetchProjectEntries(project: Project): Observable<AgentSessionEntry[]> {
    return forkJoin([
      this.agentSessionsService.list(project.id),
      this.issuesService.list(project.id),
      this.projectAgentSessionService.listOpen(project.id),
    ]).pipe(
      map(([ids, issues, projectAgentSessions]) => {
        const titles = new Map(issues.map((issue) => [issue.number, issue.title]));
        const issueEntries = ids
          .map((id) => this.toIssueEntry(project, id, titles))
          .filter((entry): entry is AgentSessionEntry => entry !== null);
        const projectEntries = this.toProjectEntries(project, projectAgentSessions);
        return [...issueEntries, ...projectEntries];
      }),
    );
  }

  private toIssueEntry(project: Project, sessionId: string, titles: Map<number, string>): AgentSessionEntry | null {
    const issueNumber = issueNumberFromSessionId(sessionId);
    if (issueNumber === null) {
      return null;
    }
    return {
      sessionId,
      projectId: project.id,
      issueNumber,
      title: titles.get(issueNumber) ?? `#${issueNumber}`,
    };
  }

  // Read from the exact same source the project-agent-session tab strip itself uses
  // (#449) -- labelProjectAgentSessions()'s numbering, `displayName` fetched from the
  // same listOpen() call and in the same order the tab strip gets it, and
  // tabText()'s rename lookup -- so the two titles can never drift onto separately
  // maintained computations. The title is still baked in at fetch time, so a rename
  // reaches this row only because `entries` refetches on onRenamed (#456).
  private toProjectEntries(project: Project, agentSessions: OpenProjectAgentSession[]): AgentSessionEntry[] {
    const tabs = labelProjectAgentSessions(
      agentSessions.map((c) => ({ id: c.sessionId, agent: this.agentStore.get(c.sessionId), name: c.displayName ?? null })),
    );
    return tabs.map((tab) => ({
      sessionId: tab.id,
      projectId: project.id,
      issueNumber: null,
      title: `Project - ${tabText(tab)}`,
    }));
  }
}
