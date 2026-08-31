import { Component, HostListener, Injector, ViewChild, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, NavigationEnd, Router, RouterLink } from '@angular/router';
import { filter, map } from 'rxjs';
import { ProjectIssue, SidenavComponent } from './components/sidenav/sidenav.component';
import { MainContentComponent } from './components/main-content/main-content.component';
import { ProjectSummaryComponent } from './components/project-summary/project-summary.component';
import { OverviewComponent } from './components/overview/overview.component';
import { SidebarResizerComponent } from './components/sidebar-resizer/sidebar-resizer.component';
import { LoginComponent } from './components/login/login.component';
import { ConsoleIndicatorComponent } from './components/console-indicator/console-indicator.component';
import { ProjectConsoleComponent } from './components/project-console/project-console.component';
import { SettingsDialogComponent } from './components/settings-dialog/settings-dialog.component';
import { AdminUsersComponent } from './components/admin-users/admin-users.component';
import { AddProjectPopupComponent } from './components/add-project-popup/add-project-popup.component';
import { UpdateBannerComponent } from './components/update-banner/update-banner.component';
import { ReleaseBannerComponent } from './components/release-banner/release-banner.component';
import { AccentThemeStore } from './services/accent-theme-store';
import { AuthService } from './services/auth.service';
import { CurrentProjectService } from './services/current-project.service';
import { deriveProjectBackgroundTint } from './services/project-accent-tint';
import { SIDEBAR_DEFAULT_WIDTH, clampSidebarWidth } from './components/sidebar-resizer/sidebar-width';

const WIDTH_STORAGE_KEY = 'locklane.sidebarWidth';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    RouterLink,
    SidenavComponent,
    MainContentComponent,
    ProjectSummaryComponent,
    OverviewComponent,
    SidebarResizerComponent,
    LoginComponent,
    ConsoleIndicatorComponent,
    SettingsDialogComponent,
    AdminUsersComponent,
    ProjectConsoleComponent,
    AddProjectPopupComponent,
    UpdateBannerComponent,
    ReleaseBannerComponent,
  ],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css',
})
export class AppComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  // Unused beyond construction: injecting it here (rather than only where the settings
  // dialog reads it) is what makes the stored accent choice (#387) apply to `:root`
  // before the dialog is ever opened, since an Angular `providedIn: 'root'` service is
  // otherwise constructed lazily on first injection.
  private readonly accentTheme = inject(AccentThemeStore);
  private readonly injector = inject(Injector);

  // Injected lazily, on first read rather than as an eager field: this
  // service fetches the project list as soon as it exists (#309), and eagerly
  // injecting it here would construct it -- and fire that fetch,
  // unauthenticated -- the moment AppComponent itself does, before the authed
  // shell (and its login check) has rendered at all. `selectedProjectId` and
  // `headerTitle` below are both `computed()`, so they don't force this
  // getter to run until the template actually reads them, which control flow
  // only does once `isLoggedIn()` is true.
  private get currentProject(): CurrentProjectService {
    return this.injector.get(CurrentProjectService);
  }

  readonly isLoggedIn = this.auth.isLoggedIn;
  readonly username = this.auth.username;
  // Gates the account menu's "Manage users" item and the panel it opens (#240) --
  // purely a display decision, since every /api/admin/** request is independently
  // enforced server-side regardless of what this signal says.
  readonly isAdmin = this.auth.isAdmin;

  // The header's account menu (#90) and the settings/admin-users dialogs it opens.
  // All plain fields rather than signals: nothing derives from them, and the
  // template reads them directly.
  menuOpen = false;
  settingsOpen = false;
  adminUsersOpen = false;

  // The add-project popup (#227) can be opened from the header button or from the
  // overview's zero-project CTA, so its state lives here rather than in either opener.
  showAddProject = false;

  @ViewChild(SidenavComponent) private readonly sidenav?: SidenavComponent;
  @ViewChild(OverviewComponent) private readonly overview?: OverviewComponent;

  // The selected project/issue lives in the URL
  // (`/projects/:projectId/issues/:id`), not in component state -- re-derived from
  // the route on every navigation so a direct load, a browser back/forward, or a
  // shared link all select the right project and issue. The project id itself
  // comes from CurrentProjectService (#309), shared with the header title below
  // and the consoles widget, rather than re-derived here privately -- wrapped in
  // `computed()` (rather than assigned straight to its signal) so reading it is
  // what triggers the lazy `currentProject` getter above, not this field's own
  // initialization.
  readonly selectedProjectId = computed(() => this.currentProject.projectId());

  // "LockLane - {project}" once a project is open in this window, so someone
  // with several project windows open can tell them apart at a glance (#309);
  // plain "LockLane" otherwise.
  readonly headerTitle = computed(() => {
    const project = this.currentProject.current();
    return project ? `LockLane - ${project.name}` : 'LockLane';
  });

  // The background wash behind a project's own pages (#428), derived from its
  // accent color -- `null` for a project with none set (every pre-existing
  // project, since the backend column is nullable), which leaves the
  // `.project-pages` wrapper at its plain CSS background, no visual regression.
  // Never affects `.topbar`/`.sidebar`, which sit outside this wrapper entirely.
  readonly projectBackgroundTint = computed(() => {
    const project = this.currentProject.current();
    return project ? deriveProjectBackgroundTint(project.accentColor) : null;
  });

  readonly selectedIssue = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map(() => this.currentIssueId()),
    ),
    { initialValue: this.currentIssueId() },
  );

  // The project-level console route (#140) has no `:id` segment of its own --
  // distinguished from the project summary route by its literal 'console' path
  // segment instead, since both otherwise carry just a `:projectId`.
  readonly onProjectConsole = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map(() => this.isProjectConsoleRoute()),
    ),
    { initialValue: this.isProjectConsoleRoute() },
  );

  // The sidenav shows every project at once (#44), so its selection carries a
  // project id alongside the issue number -- combined here for its [selected]
  // input, which needs both to highlight the right row in the right section.
  readonly selectedTarget = computed<ProjectIssue | null>(() => {
    const projectId = this.selectedProjectId();
    const issueNumber = this.selectedIssue();
    return projectId !== null && issueNumber !== null ? { projectId, issueNumber } : null;
  });

  // A single-project focused window (#286): opened by the sidenav's pop-out control
  // via `window.open()`, carrying `focus=1` in the URL rather than any shared
  // service -- so this, like every other selection signal here, is re-derived from
  // the route on every navigation instead of persisted anywhere.
  readonly focusMode = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map(() => this.isFocusMode()),
    ),
    { initialValue: this.isFocusMode() },
  );

  readonly focusedProjectId = computed<number | null>(() =>
    this.focusMode() ? this.selectedProjectId() : null,
  );

  sidebarWidth = loadWidth();

  // A project with no issue segment is the project's own summary page (#85).
  selectProject(projectId: number): void {
    this.router.navigate(['/projects', projectId, 'issues']);
  }

  setSidebarWidth(width: number): void {
    this.sidebarWidth = width;
    saveWidth(width);
  }

  // The avatar shows the first letter of the signed-in username; '?' stands in until
  // the session check has answered, which is the only window where it is unknown.
  readonly avatarInitial = computed(() => this.username()?.trim().charAt(0) || '?');

  toggleMenu(event: Event): void {
    // Without this the document listener below sees this same click and closes the
    // menu in the same tick it was opened.
    event.stopPropagation();
    this.menuOpen = !this.menuOpen;
  }

  // Bound to `document` so a click anywhere else on the page dismisses the menu.
  @HostListener('document:click')
  closeMenu(): void {
    this.menuOpen = false;
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.menuOpen = false;
  }

  openSettings(): void {
    this.menuOpen = false;
    this.settingsOpen = true;
  }

  closeSettings(): void {
    this.settingsOpen = false;
  }

  openAdminUsers(): void {
    this.menuOpen = false;
    this.adminUsersOpen = true;
  }

  closeAdminUsers(): void {
    this.adminUsersOpen = false;
  }

  openAddProject(): void {
    this.showAddProject = true;
  }

  // Both the sidenav and the overview (#197) fetch the project list independently
  // (#44), so a project created from the header or the overview's zero-state needs
  // both refreshed in place rather than relying on either one's own next reload.
  onProjectCreated(): void {
    this.showAddProject = false;
    this.sidenav?.refresh();
    this.overview?.refresh();
  }

  onAddProjectClosed(): void {
    this.showAddProject = false;
  }

  // The project summary page's own delete button (#249) has no other way to tell
  // the sidenav its project is gone -- the sidenav owns that list privately (#44),
  // the same reason onProjectCreated() above refreshes it in place.
  onProjectDeleted(): void {
    this.sidenav?.refresh();
  }

  logout(): void {
    this.menuOpen = false;
    this.auth.logout().subscribe();
  }

  private currentIssueId(): number | null {
    const raw = this.route.snapshot.firstChild?.paramMap.get('id') ?? null;
    const id = raw !== null ? Number(raw) : NaN;
    return Number.isFinite(id) ? id : null;
  }

  private isProjectConsoleRoute(): boolean {
    const segments = this.route.snapshot.firstChild?.url ?? [];
    return segments.some((segment) => segment.path === 'console');
  }

  private isFocusMode(): boolean {
    return this.route.snapshot.queryParamMap.get('focus') === '1';
  }
}

function loadWidth(): number {
  try {
    const raw = localStorage.getItem(WIDTH_STORAGE_KEY);
    const parsed = raw ? Number(raw) : NaN;
    return Number.isFinite(parsed) ? clampSidebarWidth(parsed) : SIDEBAR_DEFAULT_WIDTH;
  } catch {
    return SIDEBAR_DEFAULT_WIDTH;
  }
}

function saveWidth(width: number): void {
  try {
    localStorage.setItem(WIDTH_STORAGE_KEY, String(width));
  } catch {
    // Storage unavailable -- resizing still works for this session.
  }
}
