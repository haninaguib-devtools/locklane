import { Injectable, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, ActivatedRouteSnapshot, NavigationEnd, Router, UrlCreationOptions, UrlTree } from '@angular/router';
import { Observable, ReplaySubject, combineLatest, distinctUntilChanged, filter, map, startWith } from 'rxjs';
import { Project } from '../models/issue.model';
import { ProjectsService } from './projects.service';
import { EventsService, isProjectCreatedEvent, isProjectDeletedEvent } from './events.service';

export interface CurrentProject {
  id: number;
  name: string;
  accentColor: string | null;
}

/** The query param that marks a single-project focused window (#286): `focus=1`. */
export const FOCUS_QUERY_PARAM = 'focus';

/** Whether the URL this route snapshot came from names a focused window (#286). */
export function isFocusedRoute(snapshot: ActivatedRouteSnapshot): boolean {
  return snapshot.queryParamMap.get(FOCUS_QUERY_PARAM) === '1';
}

/**
 * The app's Router (#803): the one the app config provides in place of Angular's own,
 * so that a focused window (#286) stays focused across every in-app navigation.
 *
 * Focus mode lives in the URL alone -- `focus=1` -- and is re-derived from it on every
 * navigation (see {@link CurrentProjectService#focusMode} below), so it survives only
 * as long as each navigation carries it forward. Nothing used to: every `routerLink`
 * and every `router.navigate(...)` built a URL without it, so the first click inside
 * a popped-out window silently turned it back into an ordinary one. Every one of
 * those navigations builds its URL through `createUrlTree` -- a link's rendered
 * `href` and its click alike, and `navigate()` itself -- so this is the one place to
 * carry it: when the URL this window is showing is focused, the one being built is
 * too, unless the caller set `focus` itself (`focus: null` still drops it).
 *
 * Only `focus` is carried. Angular's own router-wide default for this,
 * `withRouterConfig({ defaultQueryParamsHandling: 'merge' })`, would carry every
 * param from page to page -- and the others are one-shot handoffs the agent session page
 * deliberately drops from the URL once acted on (`new`, #370; `dir`/`resume`/`tool`,
 * #752/#795), which a merge default would defeat: the drop navigates without the
 * param, and a merge puts the current URL's copy straight back, so a reload would
 * mint another agent session or relaunch a resume. `session` would likewise follow the user
 * onto an issue page and back.
 */
@Injectable()
export class FocusPreservingRouter extends Router {
  override createUrlTree(commands: unknown[], navigationExtras: UrlCreationOptions = {}): UrlTree {
    const queryParams = navigationExtras.queryParams ?? {};
    if (!isFocusedRoute(this.routerState.snapshot.root) || queryParams[FOCUS_QUERY_PARAM] !== undefined) {
      return super.createUrlTree(commands, navigationExtras);
    }
    return super.createUrlTree(commands, {
      ...navigationExtras,
      queryParams: { ...queryParams, [FOCUS_QUERY_PARAM]: '1' },
    });
  }
}

/**
 * The project open in this browser window/tab (#309): read from the route the
 * same way AppComponent used to derive `selectedProjectId` privately, now
 * shared so the header and the agent sessions widget (#32, #301) both narrow to the
 * same project instead of each re-deriving it -- and share the one
 * `/api/projects` fetch needed to turn the id into a name.
 *
 * Exposes both signals (for consumers that just read a value, e.g. the header
 * title) and the underlying observables (for a consumer like the agent sessions
 * widget that needs to re-derive its own entries stream whenever the project
 * or the selection changes, the same synchronous way it did before this id
 * moved out to a shared service).
 */
@Injectable({ providedIn: 'root' })
export class CurrentProjectService {
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly projectsService = inject(ProjectsService);
  private readonly eventsService = inject(EventsService);

  private readonly projectsSubject = new ReplaySubject<Project[]>(1);

  readonly projects$: Observable<Project[]> = this.projectsSubject.asObservable();
  readonly projects = toSignal(this.projects$, { initialValue: [] as Project[] });

  // distinctUntilChanged so navigating within the same project (e.g. project
  // summary -> an issue) doesn't look like a project change to a consumer like
  // the agent sessions widget, which re-fetches its entries whenever this changes.
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
  // AppComponent (#449) so the agent sessions widget can narrow by the same focused-
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
    // A one-shot call, same as AgentSessionIndicatorComponent's own former fetch --
    // completes on its own once the response lands, nothing to unsubscribe.
    // This service is only ever constructed once something actually reads its
    // data (AppComponent injects it lazily -- see its own `currentProject`
    // getter -- and AgentSessionIndicatorComponent only mounts once signed in), so
    // there is no unauthenticated fetch on the login screen to guard against.
    this.refresh();
    // A deleted project must drop out of projects$ without a reload (#885):
    // otherwise the badge keeps requesting its /issues (a 404) on every trigger.
    // projectCreated keeps the list current the same way; a reconnect re-fetches
    // in case anything was missed while the socket was down.
    this.eventsService.events$
      .pipe(filter((event) => isProjectCreatedEvent(event) || isProjectDeletedEvent(event)))
      .subscribe(() => this.refresh());
    this.eventsService.reconnected$.subscribe(() => this.refresh());
  }

  /**
   * Re-fetches the project list (#428): a project's own page can change a field
   * on it -- the accent color picker, so far -- with no other way to tell this
   * service's cached copy, since `current()` otherwise only changes on
   * navigation.
   */
  refresh(): void {
    // A failed list fetch keeps the last good value (#885) rather than surfacing
    // an error no caller handles: projects$ simply re-emits on the next refresh.
    this.projectsService.list().subscribe({
      next: (projects) => this.projectsSubject.next(projects),
      error: () => {},
    });
  }

  private currentProjectId(): number | null {
    const raw = this.route.snapshot.firstChild?.paramMap.get('projectId') ?? null;
    const id = raw !== null ? Number(raw) : NaN;
    return Number.isFinite(id) ? id : null;
  }

  private isFocusMode(): boolean {
    return isFocusedRoute(this.route.snapshot);
  }
}
