import { Component, Input, OnChanges, OnDestroy, SimpleChanges, inject } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Agent, AgentStore } from '../../services/agent-store';
import { DefaultAgentStore } from '../../services/default-agent-store';
import { ConsolesService } from '../../services/consoles.service';
import { IssuesService } from '../../services/issues.service';
import { OpenProjectConsole, ProjectConsoleService } from '../../services/project-console.service';
import { LastConsoleStore } from '../../services/last-console-store';
import { ConsoleTabsComponent, OpenConsoleRequest } from '../console-tabs/console-tabs.component';
import { ConsoleTab } from '../console-tabs/console-labels';
import { SessionListComponent } from '../session-list/session-list.component';
import { TerminalComponent } from '../terminal/terminal.component';
import { ResumeSession } from '../../models/issue.model';

// One open console's client-side state. `dir` comes from the engine either way;
// `agent` is only known when this browser launched the session (AgentStore).
// `resume` is the past conversation this console was opened to resume (#372),
// null for an ordinary new one.
interface OpenConsole {
  id: string;
  dir: string;
  agent: Agent | null;
  resume: string | null;
}

// The project-level console page (#140, part of #138): lets a user start a
// Claude/Codex/shell conversation -- where the /t-open skill and `gh` are available
// -- before any issue exists, so an agent can open one. Since #314 each console runs
// in its own fresh git worktree rather than sharing one checkout; `dir` below is
// whatever directory the engine reports for that session, opaque to this component.
// Since #177 a project can have several consoles open at once, so this
// page shows the same tab strip an issue's consoles get (#178) -- minus the
// Overview tab and the main/worktree choice, which only make sense for an issue.
// Since #372 it also lists the conversations that ran in this project's consoles
// and can reopen one -- the capability an issue's Overview tab has had since #103
// -- behind a disclosure under the header, since #256 means this page almost always
// opens straight into a live console, with no empty state to put the list in.
@Component({
  selector: 'app-project-console',
  standalone: true,
  imports: [ConsoleTabsComponent, SessionListComponent, TerminalComponent],
  templateUrl: './project-console.component.html',
  styleUrl: './project-console.component.css',
})
export class ProjectConsoleComponent implements OnChanges, OnDestroy {
  private readonly service = inject(ProjectConsoleService);
  private readonly consolesService = inject(ConsolesService);
  private readonly issuesService = inject(IssuesService);
  private readonly agentStore = inject(AgentStore);
  readonly defaultAgentStore = inject(DefaultAgentStore);
  private readonly lastConsoleStore = inject(LastConsoleStore);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  @Input({ required: true }) projectId!: number;

  loading = true;
  consoles: OpenConsole[] = [];
  tabs: ConsoleTab[] = [];
  selected: string | null = null;
  starting = false;
  startError = false;
  closeError = false;
  /** Past conversations captured in this project's consoles (#372), newest first. */
  pastSessions: ResumeSession[] = [];
  pastOpen = false;

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['projectId']) {
      this.load(this.projectId);
    }
  }

  // Leaving this page -- however the navigation happened -- never closes the
  // sessions (they keep running server-side for the next reattach, same as an
  // issue's own consoles); what it does need is telling the sidenav its cached
  // view of this project's issue list may be stale, since the agent may have just
  // opened one via `gh` before the engine's own 30s poll would notice (#140).
  ngOnDestroy(): void {
    this.issuesService.notifyProjectStale(this.projectId);
  }

  private load(projectId: number): void {
    this.loading = true;
    this.consoles = [];
    this.tabs = [];
    this.selected = null;
    this.starting = false;
    this.startError = false;
    this.closeError = false;
    this.pastSessions = [];
    this.pastOpen = false;
    this.loadPastSessions(projectId);
    this.service.listOpen(projectId).subscribe({
      next: (sessions) => {
        this.loading = false;
        if (sessions.length === 0) {
          // #256: landing here with nothing open starts one immediately, using
          // the same default-agent source the sidenav "+" uses -- no picker, no
          // separate start button.
          this.startDefault();
          return;
        }
        this.consoles = sessions.map((s) => ({
          id: s.sessionId,
          dir: s.workingDirectory,
          agent: this.agentStore.get(s.sessionId),
          resume: null,
        }));
        // The consoles page (#179) hands off with ?session=<id> naming the tab
        // to activate; otherwise reattach where the user left off -- the most
        // recently attached console, which is what this page showed before it
        // had tabs. (Routing is component-less, so the query param is read off
        // the root route.)
        const requested = this.route.snapshot.queryParamMap.get('session');
        this.selectConsole(
          requested && sessions.some((s) => s.sessionId === requested)
            ? requested
            : sessions.reduce(
                (latest: OpenProjectConsole | null, s) =>
                  !latest || Date.parse(s.lastAttachedAt) > Date.parse(latest.lastAttachedAt) ? s : latest,
                null,
              )?.sessionId ?? null,
        );
        this.relabel();
      },
      error: () => {
        this.loading = false;
      },
    });
  }

  // A conversation outlives the console it ran in (#101), so this list is read
  // independently of the open-console list; a failure leaves it simply empty rather
  // than blocking the page.
  private loadPastSessions(projectId: number): void {
    this.service.resumeSessions(projectId).subscribe({
      next: (sessions) => {
        this.pastSessions = sessions;
      },
      error: () => {
        this.pastSessions = [];
      },
    });
  }

  togglePast(): void {
    this.pastOpen = !this.pastOpen;
  }

  /**
   * Reopens a past conversation (#372): the engine mints a brand-new session in the
   * original console's working directory, and the first attach launches the tool's
   * own resume command -- the same handoff `main-content.component.ts` makes for an
   * issue's conversations.
   */
  reopenSession(session: ResumeSession): void {
    this.starting = true;
    this.startError = false;
    this.service.reopenSession(this.projectId, session.worktreeId).subscribe({
      next: (started) => {
        this.agentStore.set(started.sessionId, session.tool);
        this.consoles = [
          ...this.consoles,
          {
            id: started.sessionId,
            dir: started.workingDirectory,
            agent: session.tool,
            resume: session.resumeId,
          },
        ];
        this.relabel();
        this.selectConsole(started.sessionId);
        this.starting = false;
        this.pastOpen = false;
        this.consolesService.notifyOpened();
      },
      error: () => {
        this.starting = false;
        this.startError = true;
      },
    });
  }

  /** Retries the empty-state auto-start after a failure -- the only "start" affordance left. */
  retryStart(): void {
    this.startDefault();
  }

  private startDefault(): void {
    this.start(this.defaultAgentStore.agent());
  }

  /** The tab strip's "+" (the location choice is hidden -- only the agent matters). */
  openFromTabs(request: OpenConsoleRequest): void {
    this.start(request.agent);
  }

  /** The tab strip's own selection change -- also the entry points' recency signal (#221). */
  selectTab(id: string): void {
    this.selectConsole(id);
  }

  // Recorded in {@link LastConsoleStore} so the sidenav "+" and the project
  // summary's console button can jump back into the console the user was last
  // looking at, rather than always landing on the server's most-recently-attached
  // one (#221).
  private selectConsole(id: string | null): void {
    this.selected = id;
    if (id) {
      this.lastConsoleStore.set(this.projectId, id);
    }
  }

  private start(agent: Agent): void {
    this.starting = true;
    this.startError = false;
    this.service.start(this.projectId).subscribe({
      next: (session) => {
        this.agentStore.set(session.sessionId, agent);
        this.consoles = [
          ...this.consoles,
          { id: session.sessionId, dir: session.workingDirectory, agent, resume: null },
        ];
        this.relabel();
        this.selectConsole(session.sessionId);
        this.starting = false;
        this.consolesService.notifyOpened();
      },
      error: () => {
        this.starting = false;
        this.startError = true;
      },
    });
  }

  closeConsole(id: string): void {
    this.closeError = false;
    this.service.close(this.projectId, id).subscribe({
      next: () => {
        this.consoles = this.consoles.filter((c) => c.id !== id);
        this.relabel();
        if (this.selected === id) {
          this.selectConsole(this.consoles[0]?.id ?? null);
        }
        this.consolesService.notifyClosed();
        // Closing a console is exactly when its conversation becomes a past one, so
        // the list is re-read rather than left stale until the next visit.
        this.loadPastSessions(this.projectId);
        if (this.consoles.length === 0) {
          // #265: closing the last console leaves the console view rather than
          // auto-starting a new one -- back to the project page, where the "+"
          // affordance lives if they want another later. Landing here directly
          // with zero sessions is unaffected (#256's load()-time auto-start).
          this.back();
        }
      },
      error: () => {
        this.closeError = true;
      },
    });
  }

  back(): void {
    this.router.navigate(['/projects', this.projectId, 'issues']);
  }

  // Every console here runs in the project's own checkout, so the issue pages'
  // main/wtree labelling (console-labels.ts) carries no information -- tabs are
  // just "console", "console 2", ..., plus the agent when known.
  private relabel(): void {
    this.tabs = this.consoles.map((c, i) => ({
      id: c.id,
      agent: c.agent,
      label: `console${i > 0 ? ` ${i + 1}` : ''}${c.agent ? ` · ${c.agent}` : ''}`,
    }));
  }
}
