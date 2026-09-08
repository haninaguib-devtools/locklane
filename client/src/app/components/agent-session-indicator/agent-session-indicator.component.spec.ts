import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { AgentSessionIndicatorComponent } from './agent-session-indicator.component';
import { ActiveAgentSessionStore } from '../../services/active-agent-session-store';
import { AgentStore } from '../../services/agent-store';
import { AgentSessionsService } from '../../services/agent-sessions.service';
import { EventsService } from '../../services/events.service';
import { OpenProjectAgentSession } from '../../services/project-agent-session.service';
import { GhIssue, Project } from '../../models/issue.model';
import { routes } from '../../app.routes';

// Session ids ("<projectId>-console[-<hex>]"), "<repo>-console-<hex>" worktree directories, the
// /console and /consoles REST paths and the 'console' route segment below keep their persisted and
// on-the-wire shape: compatibility surfaces kept under ADR-112 (#766 renamed only the identifiers).

describe('AgentSessionIndicatorComponent', () => {
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
    localStorage.removeItem('locklane.sessionAgents');
    TestBed.configureTestingModule({
      imports: [AgentSessionIndicatorComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.removeItem('locklane.activeConsoleByIssue');
    localStorage.removeItem('locklane.sessionAgents');
  });

  function issue(number: number, title: string): GhIssue {
    return { number, title, state: 'OPEN', labels: [], body: '', createdAt: '', updatedAt: '' };
  }

  function projectAgentSession(sessionId: string, displayName: string | null = null): OpenProjectAgentSession {
    return { sessionId, workingDirectory: '/tmp', createdAt: '', lastAttachedAt: '', displayName };
  }

  function init(): ReturnType<typeof TestBed.createComponent<AgentSessionIndicatorComponent>> {
    return TestBed.createComponent(AgentSessionIndicatorComponent);
  }

  /** The widget's own project list (#290) -- fetched once per mount, not re-fetched on refresh. */
  function flushProjects(projects: Project[] = [PROJECT_A]): void {
    httpMock.expectOne('/api/projects').flush(projects);
  }

  /** One project's agent sessions+issues+project agent-session listing calls, fired on every fetch cycle. */
  function flushProjectEntries(
    projectId: number,
    ids: string[],
    issues: GhIssue[],
    projectAgentSessions: OpenProjectAgentSession[] = [],
  ): void {
    httpMock.expectOne(`/api/projects/${projectId}/consoles`).flush(ids);
    httpMock.expectOne(`/api/projects/${projectId}/issues`).flush(issues);
    httpMock.expectOne(`/api/projects/${projectId}/console/sessions`).flush(projectAgentSessions);
  }

  /** Mounts, resolves the project list with just PROJECT_A, then that one project's entries. */
  function initWithEntries(
    ids: string[],
    issues: GhIssue[],
    projectAgentSessions: OpenProjectAgentSession[] = [],
  ): ReturnType<typeof TestBed.createComponent<AgentSessionIndicatorComponent>> {
    const fixture = init();
    flushProjects();
    flushProjectEntries(1, ids, issues, projectAgentSessions);
    return fixture;
  }

  it('builds an entry per agent session, with its issue title', () => {
    const fixture = initWithEntries(['1-7-main-a1b2c3d4', '1-8-rename-toggle'], [issue(7, 'Seven'), issue(8, 'Eight')]);

    expect(fixture.componentInstance.entries()).toEqual([
      { sessionId: '1-7-main-a1b2c3d4', projectId: 1, issueNumber: 7, title: 'Seven' },
      { sessionId: '1-8-rename-toggle', projectId: 1, issueNumber: 8, title: 'Eight' },
    ]);
  });

  it('falls back to "#N" when the issue title is not known', () => {
    const fixture = initWithEntries(['1-9-slug'], []);

    expect(fixture.componentInstance.entries()[0].title).toBe('#9');
  });

  it('excludes an agent session id with no project/issue-number prefix', () => {
    const fixture = initWithEntries(['main', '1-7-rename-toggle'], [issue(7, 'Seven')]);

    expect(fixture.componentInstance.entries().map((e) => e.sessionId)).toEqual(['1-7-rename-toggle']);
  });

  it('builds an entry for a project agent session (#194), issue entries first, without an agent suffix (#456)', () => {
    TestBed.inject(AgentStore).set('1-console-a1b2c3d4', 'codex');
    const fixture = initWithEntries(['1-7-rename-toggle'], [issue(7, 'Seven')], [projectAgentSession('1-console-a1b2c3d4')]);

    expect(fixture.componentInstance.entries()).toEqual([
      { sessionId: '1-7-rename-toggle', projectId: 1, issueNumber: 7, title: 'Seven' },
      { sessionId: '1-console-a1b2c3d4', projectId: 1, issueNumber: null, title: 'Project - agent' },
    ]);
  });

  it("reads a project agent session's title from the same rename the tab strip shows (#449)", () => {
    const fixture = initWithEntries([], [], [projectAgentSession('1-console-a1b2c3d4', 'release notes')]);

    expect(fixture.componentInstance.entries()[0].title).toBe('Project - release notes');
  });

  it('numbers several project agent sessions the same way the tab strip does, in listOpen() order (#449)', () => {
    const fixture = initWithEntries(
      [],
      [],
      [projectAgentSession('1-console-a1b2c3d4'), projectAgentSession('1-console-e5f6a7b8')],
    );

    expect(fixture.componentInstance.entries().map((e) => e.title)).toEqual(['Project - agent', 'Project - agent 2']);
  });

  it("refetches its rows on a tab rename, so a project agent session's row updates without a reload (#456)", () => {
    const fixture = initWithEntries(
      [],
      [],
      [projectAgentSession('1-console-a1b2c3d4'), projectAgentSession('1-console-e5f6a7b8')],
    );
    expect(fixture.componentInstance.entries().map((e) => e.title)).toEqual(['Project - agent', 'Project - agent 2']);

    TestBed.inject(AgentSessionsService).notifyRenamed();

    flushProjectEntries(1, [], [], [projectAgentSession('1-console-a1b2c3d4', 'release notes'), projectAgentSession('1-console-e5f6a7b8')]);
    expect(fixture.componentInstance.entries().map((e) => e.title)).toEqual([
      'Project - release notes',
      'Project - agent 2',
    ]);
  });

  it('a rename reverted after a failed save refetches again, putting the old row text back (#456)', () => {
    const fixture = initWithEntries([], [], [projectAgentSession('1-console-a1b2c3d4', 'release notes')]);
    expect(fixture.componentInstance.entries()[0].title).toBe('Project - release notes');

    // The revert path fires the same notification; the refetch reads the name the
    // server still has.
    TestBed.inject(AgentSessionsService).notifyRenamed();

    flushProjectEntries(1, [], [], [projectAgentSession('1-console-a1b2c3d4', 'release notes')]);
    expect(fixture.componentInstance.entries()[0].title).toBe('Project - release notes');
  });

  it('jumping to a project agent session entry navigates to the project agent session page with its session id', () => {
    const fixture = initWithEntries([], [], [projectAgentSession('1-console-a1b2c3d4')]);
    const router = TestBed.inject(Router);
    const navigateSpy = spyOn(router, 'navigate');

    fixture.componentInstance.jumpTo(fixture.componentInstance.entries()[0]);

    expect(navigateSpy).toHaveBeenCalledWith(['/projects', 1, 'console'], {
      queryParams: { session: '1-console-a1b2c3d4' },
    });
  });

  it('updates the count as soon as an agent session opens elsewhere, without needing a close first', () => {
    const fixture = initWithEntries([], []);
    expect(fixture.componentInstance.entries().length).toBe(0);

    TestBed.inject(AgentSessionsService).notifyOpened();

    flushProjectEntries(1, ['1-7-rename-toggle'], [issue(7, 'Seven')]);
    expect(fixture.componentInstance.entries().length).toBe(1);
  });

  it('refreshes the count when an agent session is closed elsewhere (#75)', () => {
    const fixture = initWithEntries(['1-7-rename-toggle'], [issue(7, 'Seven')]);
    expect(fixture.componentInstance.entries().length).toBe(1);

    TestBed.inject(AgentSessionsService).notifyClosed();

    flushProjectEntries(1, [], []);
    expect(fixture.componentInstance.entries().length).toBe(0);
  });

  it('shows "agent" with no count when exactly one agent session is open (#215)', () => {
    const fixture = initWithEntries(['1-7-rename-toggle'], [issue(7, 'Seven')]);
    fixture.detectChanges();

    const button: HTMLButtonElement = fixture.nativeElement.querySelector('.badge');
    expect(button.textContent?.trim()).toBe('agent');
  });

  it('shows "agents (N)" when two or more agent sessions are open', () => {
    const fixture = initWithEntries(['1-7-rename-toggle', '1-8-rename-toggle'], [issue(7, 'Seven'), issue(8, 'Eight')]);
    fixture.detectChanges();

    const button: HTMLButtonElement = fixture.nativeElement.querySelector('.badge');
    expect(button.textContent?.trim()).toBe('agents (2)');
  });

  it('clicking the trigger with exactly one agent session navigates directly instead of opening the picker (#215)', () => {
    const fixture = initWithEntries(['1-7-rename-toggle'], [issue(7, 'Seven')]);
    const router = TestBed.inject(Router);
    const navigateSpy = spyOn(router, 'navigate');

    fixture.componentInstance.onTriggerClick();

    expect(navigateSpy).toHaveBeenCalledWith(['/projects', 1, 'issues', 7]);
    expect(fixture.componentInstance.open()).toBeFalse();
  });

  it('clicking the trigger with two or more agent sessions still opens the picker (#215)', () => {
    const fixture = initWithEntries(['1-7-rename-toggle', '1-8-rename-toggle'], [issue(7, 'Seven'), issue(8, 'Eight')]);
    const router = TestBed.inject(Router);
    const navigateSpy = spyOn(router, 'navigate');

    fixture.componentInstance.onTriggerClick();

    expect(navigateSpy).not.toHaveBeenCalled();
    expect(fixture.componentInstance.open()).toBeTrue();
  });

  it("jumping to an entry remembers it as the issue's active agent session, closes the picker, and navigates there", () => {
    const fixture = initWithEntries(['1-7-rename-toggle'], [issue(7, 'Seven')]);
    fixture.componentInstance.toggle();
    expect(fixture.componentInstance.open()).toBeTrue();
    const router = TestBed.inject(Router);
    const navigateSpy = spyOn(router, 'navigate');

    fixture.componentInstance.jumpTo(fixture.componentInstance.entries()[0]);

    expect(TestBed.inject(ActiveAgentSessionStore).get(7)).toBe('1-7-rename-toggle');
    expect(fixture.componentInstance.open()).toBeFalse();
    expect(navigateSpy).toHaveBeenCalledWith(['/projects', 1, 'issues', 7]);
  });

  it('closes the picker and clamps selection when the last entry disappears while open', () => {
    const fixture = initWithEntries(['1-7-rename-toggle'], [issue(7, 'Seven')]);
    fixture.componentInstance.toggle();
    expect(fixture.componentInstance.open()).toBeTrue();

    TestBed.inject(AgentSessionsService).notifyClosed();
    flushProjectEntries(1, [], []);
    fixture.detectChanges();

    expect(fixture.componentInstance.entries().length).toBe(0);
    expect(fixture.componentInstance.open()).toBeFalse();
  });

  it('navigates the selection with arrow keys and jumps on enter', () => {
    const fixture = initWithEntries(['1-7-rename-toggle', '1-8-rename-toggle'], [issue(7, 'Seven'), issue(8, 'Eight')]);
    const router = TestBed.inject(Router);
    const navigateSpy = spyOn(router, 'navigate');

    fixture.componentInstance.onKey({ key: 'ArrowDown', preventDefault: () => {} } as KeyboardEvent);
    expect(fixture.componentInstance.selected()).toBe(1);

    fixture.componentInstance.onKey({ key: 'ArrowDown', preventDefault: () => {} } as KeyboardEvent);
    expect(fixture.componentInstance.selected()).toBe(0);

    fixture.componentInstance.onKey({ key: 'Enter', preventDefault: () => {} } as KeyboardEvent);
    expect(navigateSpy).toHaveBeenCalledWith(['/projects', 1, 'issues', 7]);
  });

  it('closes on escape', () => {
    const fixture = initWithEntries(['1-7-rename-toggle'], [issue(7, 'Seven')]);
    fixture.componentInstance.toggle();
    expect(fixture.componentInstance.open()).toBeTrue();

    fixture.componentInstance.onKey({ key: 'Escape', preventDefault: () => {} } as KeyboardEvent);

    expect(fixture.componentInstance.open()).toBeFalse();
  });

  /** Reaches past EventsService's public API (#129) -- there is no other way to fake an incoming socket message. */
  function emitAppEvent(event: unknown): void {
    (TestBed.inject(EventsService) as unknown as { eventsSubject: { next: (e: unknown) => void } }).eventsSubject.next(
      event,
    );
  }

  it('pulses once one of its own entries is waiting for attention (#130)', () => {
    const fixture = initWithEntries(['1-7-rename-toggle'], [issue(7, 'Seven')]);
    expect(fixture.componentInstance.hasWaitingEntry()).toBeFalse();

    emitAppEvent({ type: 'consoleAttention', sessionId: '1-7-rename-toggle', state: 'waiting' });

    expect(fixture.componentInstance.hasWaitingEntry()).toBeTrue();
  });

  it('clears once an `active` event arrives for the waiting session', () => {
    const fixture = initWithEntries(['1-7-rename-toggle'], [issue(7, 'Seven')]);
    emitAppEvent({ type: 'consoleAttention', sessionId: '1-7-rename-toggle', state: 'waiting' });

    emitAppEvent({ type: 'consoleAttention', sessionId: '1-7-rename-toggle', state: 'active' });

    expect(fixture.componentInstance.hasWaitingEntry()).toBeFalse();
  });

  it("ignores a waiting session that is not one of this project's entries", () => {
    const fixture = initWithEntries(['1-7-rename-toggle'], [issue(7, 'Seven')]);

    emitAppEvent({ type: 'consoleAttention', sessionId: '2-9-other-project', state: 'waiting' });

    expect(fixture.componentInstance.hasWaitingEntry()).toBeFalse();
  });

  // #290: the widget now spans every project the user has, grouping the picker
  // dialog by project except when there is only one.

  it('omits group headings when the user has exactly one project', () => {
    const fixture = initWithEntries(['1-7-rename-toggle'], [issue(7, 'Seven')]);

    expect(fixture.componentInstance.showGroupHeadings()).toBeFalse();
  });

  it('counts and groups agent sessions across every project, headed by each project\'s name', () => {
    const fixture = init();
    flushProjects([PROJECT_A, PROJECT_B]);
    flushProjectEntries(1, ['1-7-rename-toggle'], [issue(7, 'Seven')]);
    flushProjectEntries(2, ['2-9-rename-toggle'], [issue(9, 'Nine')]);

    expect(fixture.componentInstance.entries().length).toBe(2);
    expect(fixture.componentInstance.showGroupHeadings()).toBeTrue();
    expect(fixture.componentInstance.groups().map((g) => ({ name: g.projectName, sessionIds: g.entries.map((e) => e.entry.sessionId) }))).toEqual([
      { name: 'Alpha', sessionIds: ['1-7-rename-toggle'] },
      { name: 'Beta', sessionIds: ['2-9-rename-toggle'] },
    ]);
  });

  it('still shows headings when only one of several projects currently has an open agent session', () => {
    const fixture = init();
    flushProjects([PROJECT_A, PROJECT_B]);
    flushProjectEntries(1, ['1-7-rename-toggle'], [issue(7, 'Seven')]);
    flushProjectEntries(2, [], []);

    expect(fixture.componentInstance.showGroupHeadings()).toBeTrue();
    expect(fixture.componentInstance.groups().map((g) => g.projectName)).toEqual(['Alpha']);
  });

  it("jumping to an entry navigates to that entry's own project, not necessarily the first one", () => {
    const fixture = init();
    flushProjects([PROJECT_A, PROJECT_B]);
    flushProjectEntries(1, [], []);
    flushProjectEntries(2, ['2-9-rename-toggle'], [issue(9, 'Nine')]);
    const router = TestBed.inject(Router);
    const navigateSpy = spyOn(router, 'navigate');

    fixture.componentInstance.jumpTo(fixture.componentInstance.entries()[0]);

    expect(navigateSpy).toHaveBeenCalledWith(['/projects', 2, 'issues', 9]);
  });

  it('renders nothing pending when the user has no projects at all', () => {
    const fixture = init();
    flushProjects([]);

    expect(fixture.componentInstance.entries()).toEqual([]);
    expect(fixture.componentInstance.showGroupHeadings()).toBeFalse();
  });
});

// #309/#449: narrowed to just one project's agent sessions only inside a popped-out,
// single-project focused window (focus=1, #286, via the shared
// CurrentProjectService) -- needs a real route (unlike the suite above, which
// never navigates and so is always in the all-projects case
// CurrentProjectService reports for `null`) to exercise the narrowed case.
describe('AgentSessionIndicatorComponent, inside a focused project window (#286, #449)', () => {
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
    TestBed.configureTestingModule({
      imports: [AgentSessionIndicatorComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter(routes)],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  function issue(number: number, title: string): GhIssue {
    return { number, title, state: 'OPEN', labels: [], body: '', createdAt: '', updatedAt: '' };
  }

  /** One project's agent sessions+issues+project agent-session listing calls. */
  function flushProjectEntries(projectId: number, ids: string[], issues: GhIssue[]): void {
    httpMock.expectOne(`/api/projects/${projectId}/consoles`).flush(ids);
    httpMock.expectOne(`/api/projects/${projectId}/issues`).flush(issues);
    httpMock.expectOne(`/api/projects/${projectId}/console/sessions`).flush([]);
  }

  it("lists only the open project's entries inside a popped-out focus=1 window", fakeAsync(() => {
    TestBed.inject(Router).navigateByUrl('/projects/1/issues?focus=1');
    tick();

    const fixture = TestBed.createComponent(AgentSessionIndicatorComponent);
    httpMock.expectOne('/api/projects').flush([PROJECT_A, PROJECT_B]);
    flushProjectEntries(1, ['1-7-rename-toggle'], [issue(7, 'Seven')]);

    expect(fixture.componentInstance.entries().map((e) => e.sessionId)).toEqual(['1-7-rename-toggle']);
    // Project 2's own agent sessions/issues/sessions are never requested -- httpMock.verify()
    // in afterEach would fail if they were left outstanding, and fail
    // differently (a stray request) if they were fetched at all.
  }));

  it('never shows group headings when narrowed to one project inside a focus=1 window', fakeAsync(() => {
    TestBed.inject(Router).navigateByUrl('/projects/1/issues?focus=1');
    tick();

    const fixture = TestBed.createComponent(AgentSessionIndicatorComponent);
    httpMock.expectOne('/api/projects').flush([PROJECT_A, PROJECT_B]);
    flushProjectEntries(1, ['1-7-rename-toggle'], [issue(7, 'Seven')]);

    expect(fixture.componentInstance.showGroupHeadings()).toBeFalse();
    expect(fixture.componentInstance.groups().map((g) => g.projectName)).toEqual(['Alpha']);
  }));

  it('shows every project grouped while browsing a project\'s pages in the ordinary window, without focus=1 (#449)', fakeAsync(() => {
    TestBed.inject(Router).navigateByUrl('/projects/1/issues');
    tick();

    const fixture = TestBed.createComponent(AgentSessionIndicatorComponent);
    httpMock.expectOne('/api/projects').flush([PROJECT_A, PROJECT_B]);
    flushProjectEntries(1, ['1-7-rename-toggle'], [issue(7, 'Seven')]);
    flushProjectEntries(2, [], []);

    expect(fixture.componentInstance.entries().map((e) => e.projectId)).toEqual([1]);
    expect(fixture.componentInstance.showGroupHeadings()).toBeTrue();
  }));

  it('falls back to every project (#290) once the window has no project open', fakeAsync(() => {
    TestBed.inject(Router).navigateByUrl('/');
    tick();

    const fixture = TestBed.createComponent(AgentSessionIndicatorComponent);
    httpMock.expectOne('/api/projects').flush([PROJECT_A, PROJECT_B]);
    flushProjectEntries(1, ['1-7-rename-toggle'], [issue(7, 'Seven')]);
    flushProjectEntries(2, [], []);

    expect(fixture.componentInstance.entries().map((e) => e.projectId)).toEqual([1]);
    expect(fixture.componentInstance.showGroupHeadings()).toBeTrue();
  }));
});
