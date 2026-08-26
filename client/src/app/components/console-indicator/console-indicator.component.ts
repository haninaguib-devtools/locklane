import { Component, ElementRef, Input, OnChanges, ViewChild, effect, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { Observable, ReplaySubject, forkJoin, map, merge, of, switchMap } from 'rxjs';
import { ConsolesService } from '../../services/consoles.service';
import { IssuesService } from '../../services/issues.service';
import { AgentStore } from '../../services/agent-store';
import { ActiveConsoleStore } from '../../services/active-console-store';
import { labelConsoles } from '../console-tabs/console-labels';

export interface ConsoleEntry {
  sessionId: string;
  issueNumber: number;
  issueTitle: string;
  label: string;
}

// The "Open Shells"-style header badge (#32): shows how many consoles are open
// across every issue in the current project (#43), and a picker that jumps
// straight to one. Redesigned in #105 to match portstow's `open-shells` modal
// (scrim, focus trap, arrow/enter/escape) and to read `entries` off a reactive
// stream -- `onOpened`/`onClosed` (#108) plus a fresh `projectId` -- instead of a
// cached field only `refresh()` ever touched, which is what let the badge miss an
// opened console until something else happened to close.
@Component({
  selector: 'app-console-indicator',
  standalone: true,
  templateUrl: './console-indicator.component.html',
  styleUrl: './console-indicator.component.css',
})
export class ConsoleIndicatorComponent implements OnChanges {
  private readonly consolesService = inject(ConsolesService);
  private readonly issuesService = inject(IssuesService);
  private readonly agentStore = inject(AgentStore);
  private readonly activeConsoleStore = inject(ActiveConsoleStore);
  private readonly router = inject(Router);

  @Input({ required: true }) projectId!: number;

  @ViewChild('results') private readonly resultsRef?: ElementRef<HTMLElement>;
  @ViewChild('trigger') private readonly triggerRef?: ElementRef<HTMLElement>;

  private readonly projectId$ = new ReplaySubject<number>(1);

  readonly entries = toSignal(
    this.projectId$.pipe(
      switchMap((projectId) =>
        merge(of(null), this.consolesService.onOpened, this.consolesService.onClosed).pipe(
          switchMap(() => this.fetchEntries(projectId)),
        ),
      ),
    ),
    { initialValue: [] as ConsoleEntry[] },
  );

  readonly open = signal(false);
  readonly selected = signal(0);

  constructor() {
    // A console may close while the popup is open. Keep the selection valid, and
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

  ngOnChanges(): void {
    this.projectId$.next(this.projectId);
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

  jumpTo(entry: ConsoleEntry): void {
    this.activeConsoleStore.set(entry.issueNumber, entry.sessionId);
    this.open.set(false);
    this.router.navigate(['/projects', this.projectId, 'issues', entry.issueNumber]);
  }

  private move(delta: number): void {
    const count = this.entries().length;
    if (count > 0) {
      this.selected.set((this.selected() + delta + count) % count);
    }
  }

  private fetchEntries(projectId: number): Observable<ConsoleEntry[]> {
    return forkJoin([this.consolesService.list(projectId), this.issuesService.list(projectId)]).pipe(
      map(([ids, issues]) => {
        const titles = new Map(issues.map((issue) => [issue.number, issue.title]));
        return ids
          .map((id) => this.toEntry(id, titles))
          .filter((entry): entry is ConsoleEntry => entry !== null);
      }),
    );
  }

  private toEntry(sessionId: string, titles: Map<number, string>): ConsoleEntry | null {
    // Session ids are shaped "<projectId>-<issueNumber>-<slug>" (#43) -- the second
    // numeric segment is the issue number.
    const match = /^\d+-(\d+)-/.exec(sessionId);
    if (!match) {
      return null;
    }
    const issueNumber = Number(match[1]);
    const [{ label }] = labelConsoles([{ id: sessionId, agent: this.agentStore.get(sessionId) }]);
    return { sessionId, issueNumber, issueTitle: titles.get(issueNumber) ?? `#${issueNumber}`, label };
  }
}
