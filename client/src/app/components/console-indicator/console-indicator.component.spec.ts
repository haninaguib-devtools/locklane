import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { ConsoleIndicatorComponent } from './console-indicator.component';
import { ActiveConsoleStore } from '../../services/active-console-store';
import { AgentStore } from '../../services/agent-store';
import { ConsolesService } from '../../services/consoles.service';
import { EventsService } from '../../services/events.service';
import { GhIssue, Project } from '../../models/issue.model';
import { routes } from '../../app.routes';

describe('ConsoleIndicatorComponent', () => {
  let httpMock: HttpTestingController;

  const PROJECT_A: Project = {
    id: 1,
    name: 'Alpha',
    gitUrl: 'url-a',
    workareaPath: '/tmp/a',
    defaultBranch: 'main',
    status: 'READY',
    createdAt: '',
  };
  const PROJECT_B: Project = { ...PROJECT_A, id: 2, name: 'Beta', gitUrl: 'url-b', workareaPath: '/tmp/b' };

  beforeEach(() => {
    localStorage.removeItem('locklane.activeConsoleByIssue');
    localStorage.removeItem('locklane.sessionAgents');
    TestBed.configureTestingModule({
      imports: [ConsoleIndicatorComponent],
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

  function init(): ReturnType<typeof TestBed.createComponent<ConsoleIndicatorComponent>> {
    return TestBed.createComponent(ConsoleIndicatorComponent);
  }

  /** The widget's own project list (#290) -- fetched once per mount, not re-fetched on refresh. */
  function flushProjects(projects: Project[] = [PROJECT_A]): void {
    httpMock.expectOne('/api/projects').flush(projects);
  }

  /** One project's consoles+issues calls, fired for every project on every fetch cycle. */
  function flushProjectEntries(projectId: number, ids: string[], issues: GhIssue[]): void {
    httpMock.expectOne(`/api/projects/${projectId}/consoles`).flush(ids);
    httpMock.expectOne(`/api/projects/${projectId}/issues`).flush(issues);
  }

  /** Mounts, resolves the project list with just PROJECT_A, then that one project's entries. */
  function initWithEntries(
    ids: string[],
    issues: GhIssue[],
  ): ReturnType<typeof TestBed.createComponent<ConsoleIndicatorComponent>> {
    const fixture = init();
    flushProjects();
    flushProjectEntries(1, ids, issues);
    return fixture;
  }

  it('builds an entry per console, with issue title and label', () => {
    TestBed.inject(AgentStore).set('1-7-main-a1b2c3d4', 'claude');
    const fixture = initWithEntries(['1-7-main-a1b2c3d4', '1-8-rename-toggle'], [issue(7, 'Seven'), issue(8, 'Eight')]);

    expect(fixture.componentInstance.entries()).toEqual([
      { sessionId: '1-7-main-a1b2c3d4', projectId: 1, issueNumber: 7, issueTitle: 'Seven', label: 'main · claude' },
      { sessionId: '1-8-rename-toggle', projectId: 1, issueNumber: 8, issueTitle: 'Eight', label: 'wtree' },
    ]);
  });

  it('falls back to "#N" when the issue title is not known', () => {
    const fixture = initWithEntries(['1-9-slug'], []);

    expect(fixture.componentInstance.entries()[0].issueTitle).toBe('#9');
  });

  it('excludes a console id with no project/issue-number prefix', () => {
    const fixture = initWithEntries(['main', '1-7-rename-toggle'], [issue(7, 'Seven')]);

    expect(fixture.componentInstance.entries().map((e) => e.sessionId)).toEqual(['1-7-rename-toggle']);
  });

  it('builds an entry for a project console (#194), issue entries first', () => {
    TestBed.inject(AgentStore).set('1-console-a1b2c3d4', 'codex');
    const fixture = initWithEntries(['1-7-rename-toggle', '1-console-a1b2c3d4'], [issue(7, 'Seven')]);

    expect(fixture.componentInstance.entries()).toEqual([
      { sessionId: '1-7-rename-toggle', projectId: 1, issueNumber: 7, issueTitle: 'Seven', label: 'wtree' },
      {
        sessionId: '1-console-a1b2c3d4',
        projectId: 1,
        issueNumber: null,
        issueTitle: 'Project console',
        label: 'console · codex',
      },
    ]);
  });

  it('recognizes the legacy bare "<projectId>-console" project console id', () => {
    const fixture = initWithEntries(['1-console'], []);

    expect(fixture.componentInstance.entries()).toEqual([
      { sessionId: '1-console', projectId: 1, issueNumber: null, issueTitle: 'Project console', label: 'console' },
    ]);
  });

  it('jumping to a project console entry navigates to the project console page with its session id', () => {
    const fixture = initWithEntries(['1-console-a1b2c3d4'], []);
    const router = TestBed.inject(Router);
    const navigateSpy = spyOn(router, 'navigate');

    fixture.componentInstance.jumpTo(fixture.componentInstance.entries()[0]);

    expect(navigateSpy).toHaveBeenCalledWith(['/projects', 1, 'console'], {
      queryParams: { session: '1-console-a1b2c3d4' },
    });
  });

  it('updates the count as soon as a console opens elsewhere, without needing a close first', () => {
    const fixture = initWithEntries([], []);
    expect(fixture.componentInstance.entries().length).toBe(0);

    TestBed.inject(ConsolesService).notifyOpened();

    flushProjectEntries(1, ['1-7-rename-toggle'], [issue(7, 'Seven')]);
    expect(fixture.componentInstance.entries().length).toBe(1);
  });

  it('refreshes the count when a console is closed elsewhere (#75)', () => {
    const fixture = initWithEntries(['1-7-rename-toggle'], [issue(7, 'Seven')]);
    expect(fixture.componentInstance.entries().length).toBe(1);

    TestBed.inject(ConsolesService).notifyClosed();

    flushProjectEntries(1, [], []);
    expect(fixture.componentInstance.entries().length).toBe(0);
  });

  it('shows "console" with no count when exactly one console is open (#215)', () => {
    const fixture = initWithEntries(['1-7-rename-toggle'], [issue(7, 'Seven')]);
    fixture.detectChanges();

    const button: HTMLButtonElement = fixture.nativeElement.querySelector('.badge');
    expect(button.textContent?.trim()).toBe('console');
  });

  it('shows "consoles (N)" when two or more consoles are open', () => {
    const fixture = initWithEntries(['1-7-rename-toggle', '1-8-rename-toggle'], [issue(7, 'Seven'), issue(8, 'Eight')]);
    fixture.detectChanges();

    const button: HTMLButtonElement = fixture.nativeElement.querySelector('.badge');
    expect(button.textContent?.trim()).toBe('consoles (2)');
  });

  it('clicking the trigger with exactly one console navigates directly instead of opening the picker (#215)', () => {
    const fixture = initWithEntries(['1-7-rename-toggle'], [issue(7, 'Seven')]);
    const router = TestBed.inject(Router);
    const navigateSpy = spyOn(router, 'navigate');

    fixture.componentInstance.onTriggerClick();

    expect(navigateSpy).toHaveBeenCalledWith(['/projects', 1, 'issues', 7]);
    expect(fixture.componentInstance.open()).toBeFalse();
  });

  it('clicking the trigger with two or more consoles still opens the picker (#215)', () => {
    const fixture = initWithEntries(['1-7-rename-toggle', '1-8-rename-toggle'], [issue(7, 'Seven'), issue(8, 'Eight')]);
    const router = TestBed.inject(Router);
    const navigateSpy = spyOn(router, 'navigate');

    fixture.componentInstance.onTriggerClick();

    expect(navigateSpy).not.toHaveBeenCalled();
    expect(fixture.componentInstance.open()).toBeTrue();
  });

  it("jumping to an entry remembers it as the issue's active console, closes the picker, and navigates there", () => {
    const fixture = initWithEntries(['1-7-rename-toggle'], [issue(7, 'Seven')]);
    fixture.componentInstance.toggle();
    expect(fixture.componentInstance.open()).toBeTrue();
    const router = TestBed.inject(Router);
    const navigateSpy = spyOn(router, 'navigate');

    fixture.componentInstance.jumpTo(fixture.componentInstance.entries()[0]);

    expect(TestBed.inject(ActiveConsoleStore).get(7)).toBe('1-7-rename-toggle');
    expect(fixture.componentInstance.open()).toBeFalse();
    expect(navigateSpy).toHaveBeenCalledWith(['/projects', 1, 'issues', 7]);
  });

  it('closes the picker and clamps selection when the last entry disappears while open', () => {
    const fixture = initWithEntries(['1-7-rename-toggle'], [issue(7, 'Seven')]);
    fixture.componentInstance.toggle();
    expect(fixture.componentInstance.open()).toBeTrue();

    TestBed.inject(ConsolesService).notifyClosed();
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

  it('counts and groups consoles across every project, headed by each project\'s name', () => {
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

  it('still shows headings when only one of several projects currently has an open console', () => {
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

// #309: narrowed to the project open in this window when the route carries a
// projectId, via the shared CurrentProjectService -- needs a real route (unlike
// the suite above, which never navigates and so is always in the all-projects
// case CurrentProjectService reports for `null`) to exercise the scoped case.
describe('ConsoleIndicatorComponent, scoped to a project (#309)', () => {
  let httpMock: HttpTestingController;

  const PROJECT_A: Project = {
    id: 1,
    name: 'Alpha',
    gitUrl: 'url-a',
    workareaPath: '/tmp/a',
    defaultBranch: 'main',
    status: 'READY',
    createdAt: '',
  };
  const PROJECT_B: Project = { ...PROJECT_A, id: 2, name: 'Beta', gitUrl: 'url-b', workareaPath: '/tmp/b' };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ConsoleIndicatorComponent],
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

  it("lists only the open project's entries when the route carries a projectId", fakeAsync(() => {
    TestBed.inject(Router).navigateByUrl('/projects/1/issues');
    tick();

    const fixture = TestBed.createComponent(ConsoleIndicatorComponent);
    httpMock.expectOne('/api/projects').flush([PROJECT_A, PROJECT_B]);
    httpMock.expectOne('/api/projects/1/consoles').flush(['1-7-rename-toggle']);
    httpMock.expectOne('/api/projects/1/issues').flush([issue(7, 'Seven')]);

    expect(fixture.componentInstance.entries().map((e) => e.sessionId)).toEqual(['1-7-rename-toggle']);
    // Project 2's own consoles/issues are never requested -- httpMock.verify()
    // in afterEach would fail if they were left outstanding, and fail
    // differently (a stray request) if they were fetched at all.
  }));

  it('never shows group headings when scoped to one project, even with several projects total', fakeAsync(() => {
    TestBed.inject(Router).navigateByUrl('/projects/1/issues');
    tick();

    const fixture = TestBed.createComponent(ConsoleIndicatorComponent);
    httpMock.expectOne('/api/projects').flush([PROJECT_A, PROJECT_B]);
    httpMock.expectOne('/api/projects/1/consoles').flush(['1-7-rename-toggle']);
    httpMock.expectOne('/api/projects/1/issues').flush([issue(7, 'Seven')]);

    expect(fixture.componentInstance.showGroupHeadings()).toBeFalse();
    expect(fixture.componentInstance.groups().map((g) => g.projectName)).toEqual(['Alpha']);
  }));

  it('falls back to every project (#290) once the window has no project open', fakeAsync(() => {
    TestBed.inject(Router).navigateByUrl('/');
    tick();

    const fixture = TestBed.createComponent(ConsoleIndicatorComponent);
    httpMock.expectOne('/api/projects').flush([PROJECT_A, PROJECT_B]);
    httpMock.expectOne('/api/projects/1/consoles').flush(['1-7-rename-toggle']);
    httpMock.expectOne('/api/projects/1/issues').flush([issue(7, 'Seven')]);
    httpMock.expectOne('/api/projects/2/consoles').flush([]);
    httpMock.expectOne('/api/projects/2/issues').flush([]);

    expect(fixture.componentInstance.entries().map((e) => e.projectId)).toEqual([1]);
    expect(fixture.componentInstance.showGroupHeadings()).toBeTrue();
  }));
});
