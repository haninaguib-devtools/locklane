import { Component, HostListener, Injector, ViewChild, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Location } from '@angular/common';
import { ActivatedRoute, NavigationEnd, Router, RouterLink } from '@angular/router';
import { filter, map } from 'rxjs';
import { ProjectIssue, SidenavComponent } from './components/sidenav/sidenav.component';
import { MainContentComponent } from './components/main-content/main-content.component';
import { ProjectSummaryComponent } from './components/project-summary/project-summary.component';
import { OverviewComponent } from './components/overview/overview.component';
import { SidebarResizerComponent } from './components/sidebar-resizer/sidebar-resizer.component';
import { LoginComponent } from './components/login/login.component';
import { AgentSessionIndicatorComponent } from './components/agent-session-indicator/agent-session-indicator.component';
import { ProjectAgentSessionComponent } from './components/project-agent-session/project-agent-session.component';
import { ShellsWindowComponent } from './components/shells-window/shells-window.component';
import { SettingsDialogComponent } from './components/settings-dialog/settings-dialog.component';
import { AboutDialogComponent } from './components/about-dialog/about-dialog.component';
import { AdminUsersComponent } from './components/admin-users/admin-users.component';
import { GithubAccountsComponent } from './components/github-accounts/github-accounts.component';
import { AddProjectPopupComponent } from './components/add-project-popup/add-project-popup.component';
import { UpdateBannerComponent } from './components/update-banner/update-banner.component';
import { ReleaseBannerComponent } from './components/release-banner/release-banner.component';
import { AccentThemeStore } from './services/accent-theme-store';
import { AuthService } from './services/auth.service';
import { CurrentProjectService } from './services/current-project.service';
import { deriveProjectBackgroundTint } from './services/project-accent-tint';
import { WindowChromeDirective } from './window-chrome.directive';
import { SIDEBAR_DEFAULT_WIDTH, clampSidebarWidth } from './components/sidebar-resizer/sidebar-width';
import { Project } from './models/issue.model';

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
    AgentSessionIndicatorComponent,
    SettingsDialogComponent,
    AboutDialogComponent,
    AdminUsersComponent,
    GithubAccountsComponent,
    ProjectAgentSessionComponent,
    ShellsWindowComponent,
    AddProjectPopupComponent,
    UpdateBannerComponent,
    ReleaseBannerComponent,
    WindowChromeDirective,
  ],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css',
})
export class AppComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly location = inject(Location);
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
  // `projectName` below are both `computed()`, so they don't force this
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

  // The header's account menu (#90) and the settings/admin-users/github-accounts
  // dialogs it opens. All plain fields rather than signals: nothing derives from
  // them, and the template reads them directly.
  menuOpen = false;
  settingsOpen = false;
  aboutOpen = false;
  adminUsersOpen = false;
  githubAccountsOpen = false;

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
  // and the agent sessions widget, rather than re-derived here privately -- wrapped in
  // `computed()` (rather than assigned straight to its signal) so reading it is
  // what triggers the lazy `currentProject` getter above, not this field's own
  // initialization.
  readonly selectedProjectId = computed(() => this.currentProject.projectId());

  // The selected project's name, shown as its own centered header element
  // (#586) rather than fused into the "LockLane - {project}" brand link it
  // used to be -- `null` with no project open in this window (#309), so the
  // template can omit the element instead of rendering empty text.
  readonly projectName = computed(() => this.currentProject.current()?.name ?? null);

  // The background wash behind the persistent header bar (#555), derived from
  // the selected project's accent color -- `null` for a project with none set
  // (every pre-existing project, since the backend column is nullable) or with
  // no project selected at all, which leaves `.topbar` at its plain CSS
  // background, no visual regression. Applies whenever a project is selected,
  // including while viewing that project's own agent session page -- unlike the
  // full-page tint this replaced (#428/#433), the header is always visible so
  // there is no agent-session-page carve-out to make. Never affects `.project-pages`
  // or anything under it, which always show their plain default background now.
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

  // The project-level agent session route (#140) has no `:id` segment of its own --
  // distinguished from the project summary route by its literal 'agent session' path
  // segment instead, since both otherwise carry just a `:projectId`.
  readonly onProjectAgentSession = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map(() => this.isProjectAgentSessionRoute()),
    ),
    { initialValue: this.isProjectAgentSessionRoute() },
  );

  // The Shells window routes (#446) render their own minimal shell -- no
  // topbar/sidebar -- so the template branches on this before the authed layout,
  // the same way the project-agent-session route is detected below.
  readonly onShellsWindow = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map(() => this.isShellsRoute()),
    ),
    { initialValue: this.isShellsRoute() },
  );

  // The sidenav shows every project at once (#44), so its selection carries a
  // project id alongside the issue number -- combined here for its [selected]
  // input, which needs both to highlight the right row in the right section.
  readonly selectedTarget = computed<ProjectIssue | null>(() => {
    const projectId = this.selectedProjectId();
    const issueNumber = this.selectedIssue();
    return projectId !== null && issueNumber !== null ? { projectId, issueNumber } : null;
  });

  // A single-project focused window (#286), now shared via CurrentProjectService
  // (#449) so the agent sessions widget can narrow by the same state this sidenav
  // already does, rather than a second private computation of it here.
  readonly focusMode = computed(() => this.currentProject.focusMode());

  readonly focusedProjectId = computed<number | null>(() => this.currentProject.focusedProjectId());

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

  // Walks the app's own history exactly as a browser's back/forward buttons do (#775)
  // -- the same chords a browser already binds, so nothing new has to be learned:
  // Alt+Left / Alt+Right on Windows and Linux, Cmd+[ / Cmd+] and Cmd+Left / Cmd+Right
  // on macOS. preventDefault() only fires on a chord this handler actually acts on, so
  // a PWA/browser tab that also honours the chord natively never double-navigates.
  @HostListener('document:keydown', ['$event'])
  onHistoryNavKeydown(event: KeyboardEvent): void {
    const isMac = this.isMacPlatform();
    const direction = historyNavDirection(event, isMac);
    if (direction === null) {
      return;
    }
    // A focused terminal keeps Alt+Left/Right as its own word-jump keys (the agent session
    // forwards them to the shell) -- excluded only on non-mac, since xterm.js never
    // consumes the Cmd chords in the first place, so Cmd+[ still navigates back with a
    // terminal focused. An editable field always keeps its own Left/Right meaning,
    // on either platform.
    if (isEditableTarget(event.target) || (!isMac && isInsideTerminal(event.target))) {
      return;
    }
    event.preventDefault();
    if (direction === 'back') {
      this.location.back();
    } else {
      this.location.forward();
    }
  }

  /** `navigator.platform`/`userAgent` containing "Mac" selects the Cmd chords above; a method (not a free function) so tests can stub it. */
  protected isMacPlatform(): boolean {
    return /Mac/.test(navigator.platform || navigator.userAgent);
  }

  openSettings(): void {
    this.menuOpen = false;
    this.settingsOpen = true;
  }

  closeSettings(): void {
    this.settingsOpen = false;
  }

  openAbout(): void {
    this.menuOpen = false;
    this.aboutOpen = true;
  }

  closeAbout(): void {
    this.aboutOpen = false;
  }

  openAdminUsers(): void {
    this.menuOpen = false;
    this.adminUsersOpen = true;
  }

  closeAdminUsers(): void {
    this.adminUsersOpen = false;
  }

  openGithubAccounts(): void {
    this.menuOpen = false;
    this.githubAccountsOpen = true;
  }

  closeGithubAccounts(): void {
    this.githubAccountsOpen = false;
  }

  openAddProject(): void {
    this.showAddProject = true;
  }

  // Both the sidenav and the overview (#197) fetch the project list independently
  // (#44), so a project created from the header or the overview's zero-state needs
  // both refreshed in place rather than relying on either one's own next reload.
  // The sidenav reveal (#717) expands the new row, scrolls it into view, and
  // highlights it briefly -- the same refresh the old code did, plus the flash.
  onProjectCreated(project: Project): void {
    this.showAddProject = false;
    this.sidenav?.revealProject(project.id);
    this.overview?.refresh();
  }

  // An import keeps its dialog open -- locked, timer running -- until the sidebar
  // actually has the new row (#717): the reveal's `done` closes the dialog, so
  // there is no dead gap between the dialog closing and the row appearing. A
  // failed reload still closes it (the reveal never traps the waiter).
  onProjectImported(project: Project): void {
    this.overview?.refresh();
    this.sidenav?.revealProject(project.id, () => {
      this.showAddProject = false;
    });
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

  private isProjectAgentSessionRoute(): boolean {
    const segments = this.route.snapshot.firstChild?.url ?? [];
    // 'console' is the route path segment -- a compatibility surface kept under ADR-112.
    return segments.some((segment) => segment.path === 'console');
  }

  private isShellsRoute(): boolean {
    const segments = this.route.snapshot.firstChild?.url ?? [];
    return segments[0]?.path === 'shells';
  }
}

type HistoryNavDirection = 'back' | 'forward';

// A chord with Shift or Ctrl held is always left alone -- checked before the
// platform-specific chords below so neither one needs to repeat it.
function historyNavDirection(event: KeyboardEvent, isMac: boolean): HistoryNavDirection | null {
  if (event.shiftKey || event.ctrlKey) {
    return null;
  }
  if (isMac) {
    // A bare Alt+Arrow on macOS is an Option chord with editing meaning, not this
    // shortcut -- only Cmd navigates here.
    if (event.altKey || !event.metaKey) {
      return null;
    }
    if (event.key === '[' || event.key === 'ArrowLeft') {
      return 'back';
    }
    if (event.key === ']' || event.key === 'ArrowRight') {
      return 'forward';
    }
    return null;
  }
  if (event.metaKey || !event.altKey) {
    return null;
  }
  if (event.key === 'ArrowLeft') {
    return 'back';
  }
  if (event.key === 'ArrowRight') {
    return 'forward';
  }
  return null;
}

function isEditableTarget(target: EventTarget | null): boolean {
  return target instanceof Element && target.closest('input, textarea, [contenteditable]') !== null;
}

function isInsideTerminal(target: EventTarget | null): boolean {
  return target instanceof Element && target.closest('.xterm, app-terminal') !== null;
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
