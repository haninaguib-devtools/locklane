import { Component, HostListener, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, NavigationEnd, Router } from '@angular/router';
import { filter, map } from 'rxjs';
import { ProjectIssue, SidenavComponent } from './components/sidenav/sidenav.component';
import { MainContentComponent } from './components/main-content/main-content.component';
import { ProjectSummaryComponent } from './components/project-summary/project-summary.component';
import { SidebarResizerComponent } from './components/sidebar-resizer/sidebar-resizer.component';
import { LoginComponent } from './components/login/login.component';
import { ConsoleIndicatorComponent } from './components/console-indicator/console-indicator.component';
import { ProjectConsoleComponent } from './components/project-console/project-console.component';
import { ConsolesPageComponent } from './components/consoles-page/consoles-page.component';
import { SettingsDialogComponent } from './components/settings-dialog/settings-dialog.component';
import { AuthService } from './services/auth.service';
import { SIDEBAR_DEFAULT_WIDTH, clampSidebarWidth } from './components/sidebar-resizer/sidebar-width';

const WIDTH_STORAGE_KEY = 'locklane.sidebarWidth';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    SidenavComponent,
    MainContentComponent,
    ProjectSummaryComponent,
    SidebarResizerComponent,
    LoginComponent,
    ConsoleIndicatorComponent,
    SettingsDialogComponent,
    ProjectConsoleComponent,
    ConsolesPageComponent,
  ],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css',
})
export class AppComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  readonly isLoggedIn = this.auth.isLoggedIn;
  readonly username = this.auth.username;

  // The header's account menu (#90) and the settings dialog it opens. Both are plain
  // fields rather than signals: nothing derives from them, and the template reads
  // them directly.
  menuOpen = false;
  settingsOpen = false;

  // The selected project/issue lives in the URL
  // (`/projects/:projectId/issues/:id`), not in component state -- re-derived from
  // the route on every navigation so a direct load, a browser back/forward, or a
  // shared link all select the right project and issue.
  readonly selectedProjectId = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map(() => this.currentProjectId()),
    ),
    { initialValue: this.currentProjectId() },
  );

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

  // The consoles page (#179), same shape: no `:id` segment, told apart from the
  // other project-level routes by its literal 'consoles' segment.
  readonly onConsolesPage = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map(() => this.isConsolesPageRoute()),
    ),
    { initialValue: this.isConsolesPageRoute() },
  );

  // The sidenav shows every project at once (#44), so its selection carries a
  // project id alongside the issue number -- combined here for its [selected]
  // input, which needs both to highlight the right row in the right section.
  readonly selectedTarget = computed<ProjectIssue | null>(() => {
    const projectId = this.selectedProjectId();
    const issueNumber = this.selectedIssue();
    return projectId !== null && issueNumber !== null ? { projectId, issueNumber } : null;
  });

  sidebarWidth = loadWidth();

  // A project with no issue segment is the project's own summary page (#85) -- the
  // same URL `defaultProjectRedirect` already lands on.
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

  logout(): void {
    this.menuOpen = false;
    this.auth.logout().subscribe();
  }

  private currentIssueId(): number | null {
    const raw = this.route.snapshot.firstChild?.paramMap.get('id') ?? null;
    const id = raw !== null ? Number(raw) : NaN;
    return Number.isFinite(id) ? id : null;
  }

  private currentProjectId(): number | null {
    const raw = this.route.snapshot.firstChild?.paramMap.get('projectId') ?? null;
    const id = raw !== null ? Number(raw) : NaN;
    return Number.isFinite(id) ? id : null;
  }

  private isProjectConsoleRoute(): boolean {
    const segments = this.route.snapshot.firstChild?.url ?? [];
    return segments.some((segment) => segment.path === 'console');
  }

  private isConsolesPageRoute(): boolean {
    const segments = this.route.snapshot.firstChild?.url ?? [];
    return segments.some((segment) => segment.path === 'consoles');
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
