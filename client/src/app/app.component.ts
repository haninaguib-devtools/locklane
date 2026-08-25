import { Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, NavigationEnd, Router } from '@angular/router';
import { filter, map } from 'rxjs';
import { SidenavComponent } from './components/sidenav/sidenav.component';
import { MainContentComponent } from './components/main-content/main-content.component';
import { SidebarResizerComponent } from './components/sidebar-resizer/sidebar-resizer.component';
import { LoginComponent } from './components/login/login.component';
import { AuthService } from './services/auth.service';
import { SIDEBAR_DEFAULT_WIDTH, clampSidebarWidth } from './components/sidebar-resizer/sidebar-width';

const WIDTH_STORAGE_KEY = 'locklane.sidebarWidth';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [SidenavComponent, MainContentComponent, SidebarResizerComponent, LoginComponent],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css',
})
export class AppComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  readonly isLoggedIn = this.auth.isLoggedIn;

  // The selected issue lives in the URL (`/issues/:id`), not in component
  // state -- re-derived from the route on every navigation so a direct load
  // of `/issues/:id`, a browser back/forward, or a shared link all select the
  // right issue.
  readonly selectedIssue = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map(() => this.currentIssueId()),
    ),
    { initialValue: this.currentIssueId() },
  );

  sidebarWidth = loadWidth();

  select(issueNumber: number): void {
    this.router.navigate(['/issues', issueNumber]);
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
