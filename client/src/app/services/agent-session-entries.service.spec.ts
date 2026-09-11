import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AgentSessionEntriesService } from './agent-session-entries.service';
import { ActiveAgentSessionStore } from './active-agent-session-store';
import { OpenProjectAgentSession } from './project-agent-session.service';
import { GhIssue, Project } from '../models/issue.model';

// Session ids ("<projectId>-console[-<hex>]"), "<repo>-console-<hex>" worktree directories, the
// /console and /consoles REST paths and the 'console' route segment below keep their persisted and
// on-the-wire shape: compatibility surfaces kept under ADR-112 (#766 renamed only the identifiers).

describe('AgentSessionEntriesService (#859)', () => {
  let service: AgentSessionEntriesService;
  let httpMock: HttpTestingController;

  const PROJECT_A: Project = {
    id: 1,
    name: 'Alpha',
    gitUrl: 'url-a',
    workareaPath: '/tmp/a',
    defaultBranch: 'main',
    accentColor: null,
    template: null,
    status: 'READY',
    createdAt: '',
  };
  const PROJECT_B: Project = { ...PROJECT_A, id: 2, name: 'Beta', gitUrl: 'url-b', workareaPath: '/tmp/b' };

  beforeEach(() => {
    localStorage.removeItem('locklane.activeConsoleByIssue');
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    service = TestBed.inject(AgentSessionEntriesService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.removeItem('locklane.activeConsoleByIssue');
  });

  function issue(number: number, title: string): GhIssue {
    return { number, title, state: 'OPEN', labels: [], body: '', createdAt: '', updatedAt: '' };
  }

  function flushOneProject(
    ids: string[],
    issues: GhIssue[],
    projectAgentSessions: OpenProjectAgentSession[] = [],
  ): void {
    httpMock.expectOne('/api/projects/1/consoles').flush(ids);
    httpMock.expectOne('/api/projects/1/issues').flush(issues);
    httpMock.expectOne('/api/projects/1/console/sessions').flush(projectAgentSessions);
  }

  it('resolves to an empty array with no projects, issuing no requests', async () => {
    const result = await firstValueFrom(service.fetchEntries([]));

    expect(result).toEqual([]);
  });

  it("builds an entry per agent session, with its issue title and its project's name", async () => {
    const promise = firstValueFrom(service.fetchEntries([PROJECT_A]));
    flushOneProject(['1-7-main-a1b2c3d4'], [issue(7, 'Seven')]);

    expect(await promise).toEqual([
      { sessionId: '1-7-main-a1b2c3d4', projectId: 1, projectName: 'Alpha', issueNumber: 7, title: 'Seven' },
    ]);
  });

  it('falls back to "#N" when the issue title is not known', async () => {
    const promise = firstValueFrom(service.fetchEntries([PROJECT_A]));
    flushOneProject(['1-9-slug'], []);

    expect((await promise)[0].title).toBe('#9');
  });

  it('excludes an agent session id with no project/issue-number prefix', async () => {
    const promise = firstValueFrom(service.fetchEntries([PROJECT_A]));
    flushOneProject(['main', '1-7-rename-toggle'], [issue(7, 'Seven')]);

    expect((await promise).map((e) => e.sessionId)).toEqual(['1-7-rename-toggle']);
  });

  it('builds an entry for a project agent session, with the bare project name (#194)', async () => {
    const promise = firstValueFrom(service.fetchEntries([PROJECT_A]));
    flushOneProject([], [], [{ sessionId: '1-console-a1b2c3d4', workingDirectory: '/tmp', createdAt: '', lastAttachedAt: '', displayName: null }]);

    expect(await promise).toEqual([
      { sessionId: '1-console-a1b2c3d4', projectId: 1, projectName: 'Alpha', issueNumber: null, title: 'Project - agent' },
    ]);
  });

  it('fans requests out across every given project', async () => {
    const promise = firstValueFrom(service.fetchEntries([PROJECT_A, PROJECT_B]));
    flushOneProject(['1-7-rename-toggle'], [issue(7, 'Seven')]);
    httpMock.expectOne('/api/projects/2/consoles').flush(['2-9-rename-toggle']);
    httpMock.expectOne('/api/projects/2/issues').flush([issue(9, 'Nine')]);
    httpMock.expectOne('/api/projects/2/console/sessions').flush([]);

    expect((await promise).map((e) => e.sessionId)).toEqual(['1-7-rename-toggle', '2-9-rename-toggle']);
  });

  it('a failed fetch for one project keeps the other projects entries instead of erroring (#885)', async () => {
    const promise = firstValueFrom(service.fetchEntries([PROJECT_A, PROJECT_B]));
    flushOneProject(['1-7-rename-toggle'], [issue(7, 'Seven')]);
    // Fail the last of the three requests so nothing is left outstanding: an
    // earlier failure would cancel the later ones, which HttpTestingController
    // still reports as open.
    httpMock.expectOne('/api/projects/2/consoles').flush(['2-9-rename-toggle']);
    httpMock.expectOne('/api/projects/2/issues').flush([issue(9, 'Nine')]);
    httpMock.expectOne('/api/projects/2/console/sessions').flush('gone', { status: 404, statusText: 'Not Found' });

    expect((await promise).map((e) => e.sessionId)).toEqual(['1-7-rename-toggle']);
  });

  it("jumping to an issue's entry remembers it as the issue's active agent session and navigates there", () => {
    const router = TestBed.inject(Router);
    const navigateSpy = spyOn(router, 'navigate');

    service.jumpTo({ sessionId: '1-7-rename-toggle', projectId: 1, projectName: 'Alpha', issueNumber: 7, title: 'Seven' });

    expect(TestBed.inject(ActiveAgentSessionStore).get(7)).toBe('1-7-rename-toggle');
    expect(navigateSpy).toHaveBeenCalledWith(['/projects', 1, 'issues', 7]);
  });

  it('jumping to a project agent session entry navigates to the project agent session page with its session id', () => {
    const router = TestBed.inject(Router);
    const navigateSpy = spyOn(router, 'navigate');

    service.jumpTo({ sessionId: '1-console-a1b2c3d4', projectId: 1, projectName: 'Alpha', issueNumber: null, title: 'Project - agent' });

    expect(navigateSpy).toHaveBeenCalledWith(['/projects', 1, 'console'], {
      queryParams: { session: '1-console-a1b2c3d4' },
    });
  });
});
