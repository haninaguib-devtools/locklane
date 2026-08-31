import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { ActivatedRoute, NavigationEnd, Router } from '@angular/router';
import { Subscription, filter, merge } from 'rxjs';
import { ConfirmDialogComponent } from '../confirm-dialog/confirm-dialog.component';
import { ShellsSidenavComponent } from '../shells-sidenav/shells-sidenav.component';
import { TerminalComponent } from '../terminal/terminal.component';
import { EventsService, isConsolesChangedEvent } from '../../services/events.service';
import { OpenShell, ShellsService } from '../../services/shells.service';
import { ProjectsService } from '../../services/projects.service';
import { Project } from '../../models/issue.model';

/**
 * The Shells window (#446, part of #444): the page behind the singleton popup a
 * creation trigger opens with `window.open(url, 'locklane-shells')`. Rendered by
 * AppComponent for the `/shells[/:id]` routes with none of the main app's
 * topbar/sidebar — a sidenav of every open shell the caller may see (grouped by
 * project, then issue/main) and the selected shell's terminal in the content area,
 * attached over the same WebSocket pipeline every console uses (`cmd=shell`).
 *
 * Every listed shell's terminal stays mounted, hidden with CSS when not selected —
 * the same keep-alive pattern the console tab strips use (#30) — so switching
 * shells never drops a connection or its scrollback. The sidenav follows the
 * app-wide `consolesChanged` event (plus the events socket's own reconnect
 * catch-up), so a shell opened or closed anywhere — another browser tab, or this
 * window itself — appears and disappears without a reload. Closing a shell here
 * asks the same confirmation closing a console tab does, and never closes this
 * window, even when the last shell goes.
 */
@Component({
  selector: 'app-shells-window',
  standalone: true,
  imports: [ConfirmDialogComponent, ShellsSidenavComponent, TerminalComponent],
  templateUrl: './shells-window.component.html',
  styleUrl: './shells-window.component.css',
})
export class ShellsWindowComponent implements OnInit, OnDestroy {
  private readonly shellsService = inject(ShellsService);
  private readonly projectsService = inject(ProjectsService);
  private readonly eventsService = inject(EventsService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  shells: OpenShell[] = [];
  projects: Project[] = [];
  loading = true;
  /** The selected shell's session id — the `:id` route segment. */
  selected: string | null = null;
  /** The shell whose close awaits confirmation in the dialog, if any. */
  pendingClose: OpenShell | null = null;
  closeError = false;

  private readonly subscriptions = new Subscription();

  ngOnInit(): void {
    this.selected = this.routeShellId();
    this.subscriptions.add(
      this.router.events
        .pipe(filter((event): event is NavigationEnd => event instanceof NavigationEnd))
        .subscribe(() => (this.selected = this.routeShellId())),
    );
    // A shell opened or closed anywhere reaches this window as consolesChanged
    // (#195; the shell endpoints broadcast it too, #445/#460); a reconnect
    // re-fetches in case a change was missed while the socket was down.
    this.subscriptions.add(
      merge(this.eventsService.events$.pipe(filter(isConsolesChangedEvent)), this.eventsService.reconnected$)
        .subscribe(() => this.reload()),
    );
    this.projectsService.list().subscribe({
      next: (projects) => (this.projects = projects),
      error: () => {
        // Group headers fall back to "project <id>" — the shells themselves
        // still render.
      },
    });
    this.reload();
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
  }

  select(shell: OpenShell): void {
    this.selected = shell.sessionId;
    this.router.navigate(['/shells', shell.sessionId]);
  }

  requestClose(shell: OpenShell): void {
    this.pendingClose = shell;
  }

  cancelClose(): void {
    this.pendingClose = null;
  }

  confirmClose(): void {
    const shell = this.pendingClose;
    this.pendingClose = null;
    if (!shell) {
      return;
    }
    this.closeError = false;
    this.shellsService.close(shell.projectId, shell.sessionId).subscribe({
      next: () => {
        if (this.selected === shell.sessionId) {
          this.selected = null;
          this.router.navigate(['/shells']);
        }
        // The engine broadcasts consolesChanged for this close too, but this
        // window should not depend on its own socket round-trip to update.
        this.reload();
      },
      error: () => (this.closeError = true),
    });
  }

  private reload(): void {
    this.shellsService.list().subscribe({
      next: (shells) => {
        this.shells = shells;
        this.loading = false;
      },
      error: () => (this.loading = false),
    });
  }

  private routeShellId(): string | null {
    return this.route.snapshot.paramMap.get('id') ?? this.route.snapshot.firstChild?.paramMap.get('id') ?? null;
  }
}
