import { Component } from '@angular/core';
import { SidenavComponent } from './components/sidenav/sidenav.component';
import { MainContentComponent } from './components/main-content/main-content.component';
import { SidebarResizerComponent } from './components/sidebar-resizer/sidebar-resizer.component';
import { SIDEBAR_DEFAULT_WIDTH, clampSidebarWidth } from './components/sidebar-resizer/sidebar-width';

const WIDTH_STORAGE_KEY = 'locklane.sidebarWidth';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [SidenavComponent, MainContentComponent, SidebarResizerComponent],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css',
})
export class AppComponent {
  selectedIssue: number | null = null;
  sidebarWidth = loadWidth();

  select(issueNumber: number): void {
    this.selectedIssue = issueNumber;
  }

  setSidebarWidth(width: number): void {
    this.sidebarWidth = width;
    saveWidth(width);
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
