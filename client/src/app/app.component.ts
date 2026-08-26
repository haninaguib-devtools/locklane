import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, NavigationEnd, Router } from '@angular/router';
import { filter, map } from 'rxjs';
import { ProjectIssue, SidenavComponent } from './components/sidenav/sidenav.component';
import { MainContentComponent } from './components/main-content/main-content.component';
import { ProjectSummaryComponent } from './components/project-summary/project-summary.component';
import { SidebarResizerComponent } from './components/sidebar-resizer/sidebar-resizer.component';
import { LoginComponent } from './components/login/login.component';
import { ConsoleIndicatorComponent } from './components/console-indicator/console-indicator.component';
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
  ],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css',
})
export class AppComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  readonly isLoggedIn = this.auth.isLoggedIn;

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

  // The sidenav shows every project at once (#44), so its selection carries a
  // project id alongside the issue number -- combined here for its [selected]
  // input, which needs both to highlight the right row in the right section.
  readonly selectedTarget = computed<ProjectIssue | null>(() => {
    const projectId = this.selectedProjectId();
    const issueNumber = this.selectedIssue();
    return projectId !== null && issueNumber !== null ? { projectId, issueNumber } : null;
  });

  sidebarWidth = loadWidth();

  select(target: ProjectIssue): void {
    this.router.navigate(['/projects', target.projectId, 'issues', target.issueNumber]);
  }

  // A project with no issue segment is the project's own summary page (#85) -- the
  // same URL `defaultProjectRedirect` already lands on.
  selectProject(projectId: number): void {
    this.router.navigate(['/projects', projectId, 'issues']);
  }

  setSidebarWidth(width: number): void {
    this.sidebarWidth = width;
    saveWidth(width);
  }

  logout(): void {
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
