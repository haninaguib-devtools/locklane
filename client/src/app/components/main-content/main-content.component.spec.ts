import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { MainContentComponent } from './main-content.component';
import { AgentStore } from '../../services/agent-store';
import { ActiveConsoleStore } from '../../services/active-console-store';
import { GhIssue, IssueDetail, Project } from '../../models/issue.model';

describe('MainContentComponent', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    localStorage.removeItem('locklane.sessionAgents');
    localStorage.removeItem('locklane.activeConsoleByIssue');
    TestBed.configureTestingModule({
      imports: [MainContentComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.removeItem('locklane.sessionAgents');
    localStorage.removeItem('locklane.activeConsoleByIssue');
  });

  function init(number: number): ReturnType<typeof TestBed.createComponent<MainContentComponent>> {
    const fixture = TestBed.createComponent(MainContentComponent);
    fixture.componentInstance.projectId = 1;
    fixture.componentInstance.issueNumber = number;
    fixture.componentInstance.ngOnChanges({
      projectId: { currentValue: 1, previousValue: null, firstChange: true, isFirstChange: () => true },
      issueNumber: { currentValue: number, previousValue: null, firstChange: true, isFirstChange: () => true },
    });
    return fixture;
  }

  function respond(number: number, consoleIds: string[]) {
    const issue: GhIssue = {
      number,
      title: 'T',
      state: 'OPEN',
      labels: [],
      body: '',
      createdAt: '',
      updatedAt: '',
    };
    const detail: IssueDetail = {
      number,
      recordPath: null,
      checks: { passing: 0, failing: 0, pending: 0 },
      branch: null,
      prNumber: null,
      prState: null,
      prDraft: false,
      flowSteps: [{ name: 'open', done: true }],
    };
    const projects: Project[] = [
      {
        id: 1,
        name: 'repo',
        gitUrl: 'https://github.com/org/repo.git',
        workareaPath: '/tmp/repo',
        defaultBranch: 'main',
        status: 'READY',
        createdAt: '',
      },
    ];

    httpMock.expectOne(`/api/projects/1/issues/${number}`).flush(issue);
    httpMock.expectOne(`/api/projects/1/issues/${number}/detail`).flush(detail);
    httpMock.expectOne('/api/projects').flush(projects);
    httpMock.expectOne(`/api/projects/1/issues/${number}/worktrees`).flush(consoleIds);
  }

  it('restores every open console as a tab, not just the first', () => {
    const fixture = init(7);

    respond(7, ['1-7-main-a1b2c3d4', '1-7-main-e5f6a7b8', '1-7-rename-toggle']);

    expect(fixture.componentInstance.consoles.map((c) => c.id)).toEqual([
      '1-7-main-a1b2c3d4',
      '1-7-main-e5f6a7b8',
      '1-7-rename-toggle',
    ]);
    expect(fixture.componentInstance.tabs.map((t) => t.label)).toEqual(['main', 'main 2', 'wtree']);
    expect(fixture.componentInstance.selectedConsole).toBe('1-7-main-a1b2c3d4');
  });

  it('labels restored tabs with the agent the store remembers for them', () => {
    TestBed.inject(AgentStore).set('1-7-rename-toggle', 'claude');
    const fixture = init(7);

    respond(7, ['1-7-rename-toggle']);

    expect(fixture.componentInstance.tabs[0].label).toBe('wtree · claude');
  });

  it('restores the remembered active console when it is still open', () => {
    TestBed.inject(ActiveConsoleStore).set(7, '1-7-rename-toggle');
    const fixture = init(7);

    respond(7, ['1-7-main-a1b2c3d4', '1-7-rename-toggle']);

    expect(fixture.componentInstance.selectedConsole).toBe('1-7-rename-toggle');
  });

  it('falls back to the first console when the remembered one is gone', () => {
    TestBed.inject(ActiveConsoleStore).set(7, '1-7-closed-session');
    const fixture = init(7);

    respond(7, ['1-7-main-a1b2c3d4', '1-7-rename-toggle']);

    expect(fixture.componentInstance.selectedConsole).toBe('1-7-main-a1b2c3d4');
  });

  it('switching tabs remembers the new active console for the issue', () => {
    const fixture = init(7);
    respond(7, ['1-7-main-a1b2c3d4', '1-7-rename-toggle']);

    fixture.componentInstance.selectConsole('1-7-rename-toggle');

    expect(TestBed.inject(ActiveConsoleStore).get(7)).toBe('1-7-rename-toggle');
  });

  it('has no selected console when the issue has none yet', () => {
    const fixture = init(8);

    respond(8, []);

    expect(fixture.componentInstance.selectedConsole).toBeNull();
  });

  it('switching tabs updates the selection without reloading the issue', () => {
    const fixture = init(7);
    respond(7, ['1-7-main-a1b2c3d4', '1-7-rename-toggle']);

    fixture.componentInstance.selectConsole('1-7-rename-toggle');

    expect(fixture.componentInstance.selectedConsole).toBe('1-7-rename-toggle');
    expect(fixture.componentInstance.issue?.number).toBe(7); // unchanged, no reload
  });

  it('opening a console adds its tab, selects it, and remembers its agent', () => {
    const fixture = init(8);
    respond(8, []);

    fixture.componentInstance.openConsole({ worktree: false, agent: 'codex' });
    expect(fixture.componentInstance.starting).toBeTrue();

    httpMock
      .expectOne((r) => r.url === '/api/projects/1/issues/8/worktrees' && r.method === 'POST')
      .flush({ worktreeId: '1-8-main-a1b2c3d4', workingDirectory: '/tmp/repo' });

    expect(fixture.componentInstance.starting).toBeFalse();
    expect(fixture.componentInstance.consoles).toEqual([
      { id: '1-8-main-a1b2c3d4', dir: '/tmp/repo', agent: 'codex' },
    ]);
    expect(fixture.componentInstance.tabs[0].label).toBe('main · codex');
    expect(fixture.componentInstance.selectedConsole).toBe('1-8-main-a1b2c3d4');
    expect(TestBed.inject(AgentStore).get('1-8-main-a1b2c3d4')).toBe('codex');
    expect(TestBed.inject(ActiveConsoleStore).get(8)).toBe('1-8-main-a1b2c3d4');
  });

  it('a worktree request that reuses the existing session only re-selects its tab', () => {
    const fixture = init(8);
    respond(8, ['1-8-main-a1b2c3d4', '1-8-slug']);
    fixture.componentInstance.selectConsole('1-8-main-a1b2c3d4');

    fixture.componentInstance.openConsole({ worktree: true, agent: 'claude' });
    httpMock
      .expectOne((r) => r.url === '/api/projects/1/issues/8/worktrees' && r.method === 'POST')
      .flush({ worktreeId: '1-8-slug', workingDirectory: '/tmp/repo-8' });

    expect(fixture.componentInstance.consoles.map((c) => c.id)).toEqual(['1-8-main-a1b2c3d4', '1-8-slug']);
    expect(fixture.componentInstance.selectedConsole).toBe('1-8-slug');
  });

  it('a failed open reports an error and stops the spinner without touching the tabs', () => {
    const fixture = init(8);
    respond(8, []);

    fixture.componentInstance.openConsole({ worktree: true, agent: 'claude' });
    httpMock
      .expectOne((r) => r.url === '/api/projects/1/issues/8/worktrees' && r.method === 'POST')
      .error(new ProgressEvent('network error'));

    expect(fixture.componentInstance.starting).toBeFalse();
    expect(fixture.componentInstance.startError).toBeTrue();
    expect(fixture.componentInstance.consoles).toEqual([]);
  });

  it('closing a console asks the server to end it, then drops its tab', () => {
    const fixture = init(7);
    respond(7, ['1-7-main-a1b2c3d4', '1-7-rename-toggle']);

    fixture.componentInstance.closeConsole('1-7-main-a1b2c3d4');
    httpMock.expectOne('/api/projects/1/issues/7/worktrees/1-7-main-a1b2c3d4').flush(null);

    expect(fixture.componentInstance.consoles.map((c) => c.id)).toEqual(['1-7-rename-toggle']);
    expect(fixture.componentInstance.closeError).toBeFalse();
  });

  it('closing the selected console selects the next remaining one and remembers it', () => {
    const fixture = init(7);
    respond(7, ['1-7-main-a1b2c3d4', '1-7-rename-toggle']);

    fixture.componentInstance.closeConsole('1-7-main-a1b2c3d4');
    httpMock.expectOne('/api/projects/1/issues/7/worktrees/1-7-main-a1b2c3d4').flush(null);

    expect(fixture.componentInstance.selectedConsole).toBe('1-7-rename-toggle');
    expect(TestBed.inject(ActiveConsoleStore).get(7)).toBe('1-7-rename-toggle');
  });

  it('a failed close reports an error and leaves the tab in place', () => {
    const fixture = init(7);
    respond(7, ['1-7-main-a1b2c3d4']);

    fixture.componentInstance.closeConsole('1-7-main-a1b2c3d4');
    httpMock
      .expectOne('/api/projects/1/issues/7/worktrees/1-7-main-a1b2c3d4')
      .error(new ProgressEvent('network error'));

    expect(fixture.componentInstance.closeError).toBeTrue();
    expect(fixture.componentInstance.consoles.map((c) => c.id)).toEqual(['1-7-main-a1b2c3d4']);
  });

  it('defaults to the overview tab and derives the repo web url from the project', () => {
    const fixture = init(7);
    respond(7, []);

    expect(fixture.componentInstance.activeTab).toBe('overview');
    expect(fixture.componentInstance.repoWebUrl).toBe('https://github.com/org/repo');
  });

  it('switches to a console tab and back to overview', () => {
    const fixture = init(7);
    respond(7, ['1-7-main-a1b2c3d4']);

    fixture.componentInstance.selectConsole('1-7-main-a1b2c3d4');
    expect(fixture.componentInstance.activeTab).toBe('1-7-main-a1b2c3d4');

    fixture.componentInstance.selectOverview();
    expect(fixture.componentInstance.activeTab).toBe('overview');
  });

  it('dispatches a merged tab-strip click to selectOverview or selectConsole', () => {
    const fixture = init(7);
    respond(7, ['1-7-main-a1b2c3d4']);

    fixture.componentInstance.onTabSelected('1-7-main-a1b2c3d4');
    expect(fixture.componentInstance.activeTab).toBe('1-7-main-a1b2c3d4');

    fixture.componentInstance.onTabSelected('overview');
    expect(fixture.componentInstance.activeTab).toBe('overview');
  });

  it('opening a console switches the active tab to it', () => {
    const fixture = init(8);
    respond(8, []);

    fixture.componentInstance.openConsole({ worktree: false, agent: 'codex' });
    httpMock
      .expectOne((r) => r.url === '/api/projects/1/issues/8/worktrees' && r.method === 'POST')
      .flush({ worktreeId: '1-8-main-a1b2c3d4', workingDirectory: '/tmp/repo' });

    expect(fixture.componentInstance.activeTab).toBe('1-8-main-a1b2c3d4');
  });

  it('closing the active console falls back to the next remaining tab', () => {
    const fixture = init(7);
    respond(7, ['1-7-main-a1b2c3d4', '1-7-rename-toggle']);
    fixture.componentInstance.selectConsole('1-7-main-a1b2c3d4');

    fixture.componentInstance.closeConsole('1-7-main-a1b2c3d4');
    httpMock.expectOne('/api/projects/1/issues/7/worktrees/1-7-main-a1b2c3d4').flush(null);

    expect(fixture.componentInstance.activeTab).toBe('1-7-rename-toggle');
  });

  it('closing the only active console falls back to overview', () => {
    const fixture = init(7);
    respond(7, ['1-7-main-a1b2c3d4']);
    fixture.componentInstance.selectConsole('1-7-main-a1b2c3d4');

    fixture.componentInstance.closeConsole('1-7-main-a1b2c3d4');
    httpMock.expectOne('/api/projects/1/issues/7/worktrees/1-7-main-a1b2c3d4').flush(null);

    expect(fixture.componentInstance.activeTab).toBe('overview');
  });

  it('closing a console that is not the active tab leaves the active tab alone', () => {
    const fixture = init(7);
    respond(7, ['1-7-main-a1b2c3d4', '1-7-rename-toggle']);
    fixture.componentInstance.selectConsole('1-7-rename-toggle');

    fixture.componentInstance.closeConsole('1-7-main-a1b2c3d4');
    httpMock.expectOne('/api/projects/1/issues/7/worktrees/1-7-main-a1b2c3d4').flush(null);

    expect(fixture.componentInstance.activeTab).toBe('1-7-rename-toggle');
  });
});
