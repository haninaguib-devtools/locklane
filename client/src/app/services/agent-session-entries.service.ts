import { Injectable, inject } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, forkJoin, map, of } from 'rxjs';
import { AgentSessionsService, issueNumberFromSessionId } from './agent-sessions.service';
import { IssuesService } from './issues.service';
import { OpenProjectAgentSession, ProjectAgentSessionService } from './project-agent-session.service';
import { AgentStore } from './agent-store';
import { ActiveAgentSessionStore } from './active-agent-session-store';
import { Project } from '../models/issue.model';
import { labelProjectAgentSessions, tabText } from '../components/agent-session-tabs/agent-session-labels';

/**
 * An issue's own agent session (issueNumber set) or one of the project's own agent
 * sessions (#139/#177, issueNumber null -- there is no issue to jump to, and no
 * per-issue "active agent session" to remember). Carries its own project (#290) since
 * a caller spanning every project the user has needs to tell entries from different
 * projects apart. `title` is the single line a row renders (#449) -- for a project
 * agent session this already includes the "Project - " prefix and the tab's own
 * current text, read from the same source (`tabText()`) the tab strip itself uses;
 * `projectName` is the bare project name with no such formatting (#859), for a
 * consumer that needs the name on its own rather than as part of a rendered row.
 */
export interface AgentSessionEntry {
  sessionId: string;
  projectId: number;
  projectName: string;
  issueNumber: number | null;
  title: string;
}

/**
 * Builds {@link AgentSessionEntry} rows and navigates to one (#859) -- extracted out
 * of {@link AgentSessionIndicatorComponent}, which built and owned both privately
 * before this, so a second consumer (the notification service) does not duplicate
 * either. The indicator still owns *which* projects to build entries for (all of
 * them, or narrowed to one inside a popped-out focused window, #449) and its own
 * grouping/picker-UI state; this service only knows how to turn a given project list
 * into entries and how to jump to one, the same way it always worked.
 */
@Injectable({ providedIn: 'root' })
export class AgentSessionEntriesService {
  private readonly agentSessionsService = inject(AgentSessionsService);
  private readonly issuesService = inject(IssuesService);
  private readonly projectAgentSessionService = inject(ProjectAgentSessionService);
  private readonly agentStore = inject(AgentStore);
  private readonly activeAgentSessionStore = inject(ActiveAgentSessionStore);
  private readonly router = inject(Router);

  // Fans the existing per-project agent sessions/issues calls out across every given
  // project, the same forkJoin pattern sidenav.component.ts's own
  // refreshAgentSessionIndicators() already uses.
  fetchEntries(projects: Project[]): Observable<AgentSessionEntry[]> {
    return projects.length === 0
      ? of([])
      : forkJoin(projects.map((project) => this.fetchProjectEntries(project))).pipe(map((perProject) => perProject.flat()));
  }

  // Navigates to the entry's own project (#290) -- not necessarily whichever
  // project happens to be selected elsewhere in the app.
  jumpTo(entry: AgentSessionEntry): void {
    if (entry.issueNumber !== null) {
      this.activeAgentSessionStore.set(entry.issueNumber, entry.sessionId);
      this.router.navigate(['/projects', entry.projectId, 'issues', entry.issueNumber]);
    } else {
      // 'console' is the route path segment -- a compatibility surface kept under ADR-112.
      this.router.navigate(['/projects', entry.projectId, 'console'], {
        queryParams: { session: entry.sessionId },
      });
    }
  }

  private fetchProjectEntries(project: Project): Observable<AgentSessionEntry[]> {
    return forkJoin([
      this.agentSessionsService.list(project.id),
      this.issuesService.list(project.id),
      this.projectAgentSessionService.listOpen(project.id),
    ]).pipe(
      map(([ids, issues, projectAgentSessions]) => {
        const titles = new Map(issues.map((issue) => [issue.number, issue.title]));
        const issueEntries = ids
          .map((id) => this.toIssueEntry(project, id, titles))
          .filter((entry): entry is AgentSessionEntry => entry !== null);
        const projectEntries = this.toProjectEntries(project, projectAgentSessions);
        return [...issueEntries, ...projectEntries];
      }),
    );
  }

  private toIssueEntry(project: Project, sessionId: string, titles: Map<number, string>): AgentSessionEntry | null {
    const issueNumber = issueNumberFromSessionId(sessionId);
    if (issueNumber === null) {
      return null;
    }
    return {
      sessionId,
      projectId: project.id,
      projectName: project.name,
      issueNumber,
      title: titles.get(issueNumber) ?? `#${issueNumber}`,
    };
  }

  // Read from the exact same source the project-agent-session tab strip itself uses
  // (#449) -- labelProjectAgentSessions()'s numbering, `displayName` fetched from the
  // same listOpen() call and in the same order the tab strip gets it, and
  // tabText()'s rename lookup -- so the two titles can never drift onto separately
  // maintained computations. The title is still baked in at fetch time, so a rename
  // reaches this row only because a caller refetches on onRenamed (#456).
  private toProjectEntries(project: Project, agentSessions: OpenProjectAgentSession[]): AgentSessionEntry[] {
    const tabs = labelProjectAgentSessions(
      agentSessions.map((c) => ({ id: c.sessionId, agent: this.agentStore.get(c.sessionId), name: c.displayName ?? null })),
    );
    return tabs.map((tab) => ({
      sessionId: tab.id,
      projectId: project.id,
      projectName: project.name,
      issueNumber: null,
      title: `Project - ${tabText(tab)}`,
    }));
  }
}
