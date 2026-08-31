import { Injectable, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, NavigationEnd, Router } from '@angular/router';
import { Observable, ReplaySubject, combineLatest, distinctUntilChanged, filter, map, startWith } from 'rxjs';
import { Project } from '../models/issue.model';
import { ProjectsService } from './projects.service';

export interface CurrentProject {
  id: number;
  name: string;
  accentColor: string | null;
}

/**
 * The project open in this browser window/tab (#309): read from the route the
 * same way AppComponent used to derive `selectedProjectId` privately, now
 * shared so the header and the consoles widget (#32, #301) both narrow to the
 * same project instead of each re-deriving it -- and share the one
 * `/api/projects` fetch needed to turn the id into a name.
 *
 * Exposes both signals (for consumers that just read a value, e.g. the header
 * title) and the underlying observables (for a consumer like the consoles
 * widget that needs to re-derive its own entries stream whenever the project
 * or the selection changes, the same synchronous way it did before this id
 * moved out to a shared service).
 */
@Injectable({ providedIn: 'root' })
export class CurrentProjectService {
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly projectsService = inject(ProjectsService);

  private readonly projectsSubject = new ReplaySubject<Project[]>(1);

  readonly projects$: Observable<Project[]> = this.projectsSubject.asObservable();
  readonly projects = toSignal(this.projects$, { initialValue: [] as Project[] });

  // distinctUntilChanged so navigating within the same project (e.g. project
  // summary -> an issue) doesn't look like a project change to a consumer like
  // the consoles widget, which re-fetches its entries whenever this changes.
  readonly projectId$: Observable<number | null> = this.router.events.pipe(
    filter((e): e is NavigationEnd => e instanceof NavigationEnd),
    map(() => this.currentProjectId()),
    startWith(this.currentProjectId()),
    distinctUntilChanged(),
  );
  readonly projectId = toSignal(this.projectId$, { initialValue: this.currentProjectId() });

  readonly current = computed<CurrentProject | null>(() => {
    const id = this.projectId();
    if (id === null) {
      return null;
    }
    const project = this.projects().find((p) => p.id === id);
    return project ? { id, name: project.name, accentColor: project.accentColor } : null;
  });

  // A single-project focused window (#286): opened by the sidenav's pop-out
  // control via `window.open()`, carrying `focus=1` in the URL rather than any
  // shared service, so -- like `projectId` above -- this is re-derived from the
  // route on every navigation instead of persisted anywhere. Moved here from
  // AppComponent (#449) so the consoles widget can narrow by the same focused-
  // window state the sidenav already does, instead of a second private copy.
  readonly focusMode$: Observable<boolean> = this.router.events.pipe(
    filter((e): e is NavigationEnd => e instanceof NavigationEnd),
    map(() => this.isFocusMode()),
    startWith(this.isFocusMode()),
    distinctUntilChanged(),
  );
  readonly focusMode = toSignal(this.focusMode$, { initialValue: this.isFocusMode() });

  // The project to narrow to for a consumer that should only narrow inside a
  // popped-out focused window (#449) -- unlike `projectId`/`current` above,
  // which the header keeps reading regardless of focus (#309) so its "LockLane -
  // {project}" text is unaffected by this.
  readonly focusedProjectId$: Observable<number | null> = combineLatest([this.projectId$, this.focusMode$]).pipe(
    map(([id, focused]) => (focused ? id : null)),
    distinctUntilChanged(),
  );
  readonly focusedProjectId = toSignal(this.focusedProjectId$, { initialValue: null as number | null });

  constructor() {
    // A one-shot call, same as ConsoleIndicatorComponent's own former fetch --
    // completes on its own once the response lands, nothing to unsubscribe.
    // This service is only ever constructed once something actually reads its
    // data (AppComponent injects it lazily -- see its own `currentProject`
    // getter -- and ConsoleIndicatorComponent only mounts once signed in), so
    // there is no unauthenticated fetch on the login screen to guard against.
    this.refresh();
  }

  /**
   * Re-fetches the project list (#428): a project's own page can change a field
   * on it -- the accent color picker, so far -- with no other way to tell this
   * service's cached copy, since `current()` otherwise only changes on
   * navigation.
   */
  refresh(): void {
    this.projectsService.list().subscribe((projects) => this.projectsSubject.next(projects));
  }

  private currentProjectId(): number | null {
    const raw = this.route.snapshot.firstChild?.paramMap.get('projectId') ?? null;
    const id = raw !== null ? Number(raw) : NaN;
    return Number.isFinite(id) ? id : null;
  }

  private isFocusMode(): boolean {
    return this.route.snapshot.queryParamMap.get('focus') === '1';
  }
}
