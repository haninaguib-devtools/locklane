import { Component, ElementRef, ViewChild, computed, effect, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Observable, catchError, combineLatest, EMPTY, map, merge, of, switchMap } from 'rxjs';
import { AgentSessionsService } from '../../services/agent-sessions.service';
import { CurrentProjectService } from '../../services/current-project.service';
import { AttentionStore } from '../../services/attention-store';
import { AgentSessionEntriesService, AgentSessionEntry } from '../../services/agent-session-entries.service';
import { NotificationService } from '../../services/notification.service';
import { Project } from '../../models/issue.model';

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
// happened to close. Entry-building and jump-to-entry navigation live in
// {@link AgentSessionEntriesService} (#859), shared with the notification service
// rather than duplicated.
@Component({
  selector: 'app-agent-session-indicator',
  standalone: true,
  templateUrl: './agent-session-indicator.component.html',
  styleUrl: './agent-session-indicator.component.css',
})
export class AgentSessionIndicatorComponent {
  private readonly currentProject = inject(CurrentProjectService);
  private readonly agentSessionsService = inject(AgentSessionsService);
  private readonly agentSessionEntries = inject(AgentSessionEntriesService);
  // The one shared "which sessions are waiting" store (#791), read by session id.
  private readonly attentionStore = inject(AttentionStore);
  // Unused beyond construction: this component only ever mounts once signed in
  // (app.component.html's `@else` branch), which is what makes this the safe place
  // to start NotificationService (#859) watching for a bell to notify on -- eagerly
  // injecting it from AppComponent itself, the way AccentThemeStore is, would
  // construct it (and the project-fetching services it depends on) before login,
  // exactly what CurrentProjectService's own lazy-getter comment there guards against.
  private readonly notificationService = inject(NotificationService);

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
    // A failed fetch must never reach toSignal's error channel (#885): it would
    // store the error and rethrow it on every read, and this read happens in the
    // topbar template, so every change-detection pass would throw and freeze the
    // app shell until reload. The service already isolates one project's failure
    // to that project; EMPTY here keeps the last good value and the stream alive
    // for any residual whole-fetch failure, so the next trigger still refetches.
    this.visibleProjects$.pipe(
      switchMap((projects) =>
        merge(of(null), this.agentSessionsService.onOpened, this.agentSessionsService.onClosed, this.agentSessionsService.onRenamed).pipe(
          switchMap(() => this.agentSessionEntries.fetchEntries(projects).pipe(catchError(() => EMPTY))),
        ),
      ),
      catchError(() => EMPTY),
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

  jumpTo(entry: AgentSessionEntry): void {
    this.open.set(false);
    this.agentSessionEntries.jumpTo(entry);
  }

  private move(delta: number): void {
    const count = this.entries().length;
    if (count > 0) {
      this.selected.set((this.selected() + delta + count) % count);
    }
  }
}
