import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { MainContentComponent } from './main-content.component';
import { AgentStore } from '../../services/agent-store';
import { ActiveAgentSessionStore } from '../../services/active-agent-session-store';
import { ActiveTabStore } from '../../services/active-tab-store';
import { DefaultAgentStore } from '../../services/default-agent-store';
import { OpenShell } from '../../services/shells.service';
import { GhIssue, IssueDetail, Project, ResumeSession } from '../../models/issue.model';

// Session ids ("<projectId>-console[-<hex>]"), "<repo>-console-<hex>" worktree directories, the
// /console and /consoles REST paths and the 'console' route segment below keep their persisted and
// on-the-wire shape: compatibility surfaces kept under ADR-112 (#766 renamed only the identifiers).

describe('MainContentComponent', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    localStorage.removeItem('locklane.sessionAgents');
    localStorage.removeItem('locklane.activeConsoleByIssue');
    localStorage.removeItem('locklane.activeTabByIssue');
    localStorage.removeItem('locklane.defaultAgent');
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
    localStorage.removeItem('locklane.activeTabByIssue');
    localStorage.removeItem('locklane.defaultAgent');
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

  function shell(overrides: Partial<OpenShell> = {}): OpenShell {
    return {
      sessionId: '1-shell-7-aaaa0001',
      projectId: 1,
      issueNumber: 7,
      mainCheckout: false,
      workingDirectory: '/tmp/repo-7',
      createdAt: '2026-08-27T09:00:00Z',
      lastAttachedAt: '2026-08-27T09:00:00Z',
      displayName: null,
      ...overrides,
    };
  }

  function respond(number: number, agentSessionIds: string[], resumeSessions: ResumeSession[] = [], shells: OpenShell[] = []) {
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
      checks: { passing: 0, failing: 0, pending: 0, runs: [] },
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
        accentColor: null,
        template: null,
        status: 'READY',
        createdAt: '',
      },
    ];

    httpMock.expectOne(`/api/projects/1/issues/${number}`).flush(issue);
    httpMock.expectOne(`/api/projects/1/issues/${number}/detail`).flush(detail);
    httpMock.expectOne(`/api/projects/1/issues/${number}/resume-sessions`).flush(resumeSessions);
    httpMock.expectOne('/api/projects').flush(projects);
    httpMock.expectOne(`/api/projects/1/issues/${number}/worktrees`).flush(agentSessionIds);
    // The agent list above cannot validate a remembered shell tab, so the shell
    // list arriving here adopts it when it is open (#876).
    httpMock.expectOne('/api/shells').flush(shells);
  }

  it('fetches the installed-agents list on init, so the "+" button does not launch with an empty agent (#698)', () => {
    // Neither Settings nor project-summary ran in this session -- `ngOnInit` is the
    // only thing standing between a fresh load and the store's empty stored default,
    // which is what the agent-session-tabs "+" button's `[defaultAgent]` binding reads.
    const fixture = TestBed.createComponent(MainContentComponent);
    expect(TestBed.inject(DefaultAgentStore).agent()).toBe('');

    fixture.componentInstance.ngOnInit();

    httpMock
      .expectOne('/api/agents/installed')
      .flush({ installed: [{ id: 'claude', label: 'Claude' }, { id: 'codex', label: 'Codex' }] });

    expect(TestBed.inject(DefaultAgentStore).agent()).toBe('claude');
  });

  it('restores every open agent session as a tab, not just the first', () => {
    const fixture = init(7);

    respond(7, ['1-7-main-a1b2c3d4', '1-7-main-e5f6a7b8', '1-7-rename-toggle']);

    expect(fixture.componentInstance.agentSessions.map((c) => c.id)).toEqual([
      '1-7-main-a1b2c3d4',
      '1-7-main-e5f6a7b8',
      '1-7-rename-toggle',
    ]);
    expect(fixture.componentInstance.tabs.map((t) => t.label)).toEqual(['main', 'main 2', 'wtree']);
    expect(fixture.componentInstance.selectedAgentSession).toBe('1-7-main-a1b2c3d4');
  });

  it('labels restored tabs with the agent the store remembers for them', () => {
    TestBed.inject(AgentStore).set('1-7-rename-toggle', 'claude');
    const fixture = init(7);

    respond(7, ['1-7-rename-toggle']);

    expect(fixture.componentInstance.tabs[0].label).toBe('wtree · claude');
  });

  it('restores the remembered active agent session when it is still open', () => {
    TestBed.inject(ActiveAgentSessionStore).set(7, '1-7-rename-toggle');
    const fixture = init(7);

    respond(7, ['1-7-main-a1b2c3d4', '1-7-rename-toggle']);

    expect(fixture.componentInstance.selectedAgentSession).toBe('1-7-rename-toggle');
  });

  it('falls back to the first agent session when the remembered one is gone', () => {
    TestBed.inject(ActiveAgentSessionStore).set(7, '1-7-closed-session');
    const fixture = init(7);

    respond(7, ['1-7-main-a1b2c3d4', '1-7-rename-toggle']);

    expect(fixture.componentInstance.selectedAgentSession).toBe('1-7-main-a1b2c3d4');
  });

  it('switching tabs remembers the new active agent session for the issue', () => {
    const fixture = init(7);
    respond(7, ['1-7-main-a1b2c3d4', '1-7-rename-toggle']);

    fixture.componentInstance.selectAgentSession('1-7-rename-toggle');

    expect(TestBed.inject(ActiveAgentSessionStore).get(7)).toBe('1-7-rename-toggle');
  });

  it('has no selected agent session when the issue has none yet', () => {
    const fixture = init(8);

    respond(8, []);

    expect(fixture.componentInstance.selectedAgentSession).toBeNull();
  });

  it('switching tabs updates the selection without reloading the issue', () => {
    const fixture = init(7);
    respond(7, ['1-7-main-a1b2c3d4', '1-7-rename-toggle']);

    fixture.componentInstance.selectAgentSession('1-7-rename-toggle');

    expect(fixture.componentInstance.selectedAgentSession).toBe('1-7-rename-toggle');
    expect(fixture.componentInstance.issue?.number).toBe(7); // unchanged, no reload
  });

  it('opening an agent session adds its tab, selects it, and remembers its agent', () => {
    const fixture = init(8);
    respond(8, []);

    fixture.componentInstance.openAgentSession({ agent: 'codex' });
    expect(fixture.componentInstance.starting).toBeTrue();

    httpMock
      .expectOne((r) => r.url === '/api/projects/1/issues/8/worktrees' && r.method === 'POST')
      .flush({ worktreeId: '1-8-slug', workingDirectory: '/tmp/repo' });

    expect(fixture.componentInstance.starting).toBeFalse();
    expect(fixture.componentInstance.agentSessions).toEqual([
      { id: '1-8-slug', dir: '/tmp/repo', agent: 'codex', resume: null },
    ]);
    expect(fixture.componentInstance.tabs[0].label).toBe('wtree · codex');
    expect(fixture.componentInstance.selectedAgentSession).toBe('1-8-slug');
    expect(TestBed.inject(AgentStore).get('1-8-slug')).toBe('codex');
    expect(TestBed.inject(ActiveAgentSessionStore).get(8)).toBe('1-8-slug');
  });

  it('opening an agent session that reuses the existing session only re-selects its tab (#29)', () => {
    const fixture = init(8);
    respond(8, ['1-8-main-a1b2c3d4', '1-8-slug']);
    fixture.componentInstance.selectAgentSession('1-8-main-a1b2c3d4');

    fixture.componentInstance.openAgentSession({ agent: 'claude' });
    httpMock
      .expectOne((r) => r.url === '/api/projects/1/issues/8/worktrees' && r.method === 'POST')
      .flush({ worktreeId: '1-8-slug', workingDirectory: '/tmp/repo-8' });

    expect(fixture.componentInstance.agentSessions.map((c) => c.id)).toEqual(['1-8-main-a1b2c3d4', '1-8-slug']);
    expect(fixture.componentInstance.selectedAgentSession).toBe('1-8-slug');
  });

  it('a failed open reports an error and stops the spinner without touching the tabs', () => {
    const fixture = init(8);
    respond(8, []);

    fixture.componentInstance.openAgentSession({ agent: 'claude' });
    httpMock
      .expectOne((r) => r.url === '/api/projects/1/issues/8/worktrees' && r.method === 'POST')
      .error(new ProgressEvent('network error'));

    expect(fixture.componentInstance.starting).toBeFalse();
    expect(fixture.componentInstance.startError).toBeTrue();
    expect(fixture.componentInstance.agentSessions).toEqual([]);
  });

  it('reopening a past session adds a resuming agent session tab and selects it (#103)', () => {
    const fixture = init(8);
    const past: ResumeSession = {
      worktreeId: '1-8-slug',
      tool: 'claude',
      toolLabel: 'Claude',
      resumeId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
      capturedAt: '2026-08-27T10:00:00Z',
      title: null,
    };
    respond(8, [], [past]);
    expect(fixture.componentInstance.resumeSessions).toEqual([past]);

    fixture.componentInstance.reopenSession(past);
    expect(fixture.componentInstance.starting).toBeTrue();

    httpMock
      .expectOne(
        (r) =>
          r.url === '/api/projects/1/issues/8/resume-sessions/reopen' &&
          r.method === 'POST' &&
          r.params.get('from') === '1-8-slug',
      )
      .flush({ worktreeId: '1-8-resume-a1b2c3d4', workingDirectory: '/tmp/repo-8' });

    expect(fixture.componentInstance.starting).toBeFalse();
    expect(fixture.componentInstance.agentSessions).toEqual([
      {
        id: '1-8-resume-a1b2c3d4',
        dir: '/tmp/repo-8',
        agent: 'claude',
        resume: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
      },
    ]);
    expect(fixture.componentInstance.activeTab).toBe('1-8-resume-a1b2c3d4');
    expect(TestBed.inject(AgentStore).get('1-8-resume-a1b2c3d4')).toBe('claude');
  });

  it('a failed reopen reports an error and stops the spinner without touching the tabs (#103)', () => {
    const fixture = init(8);
    const past: ResumeSession = {
      worktreeId: '1-8-slug',
      tool: 'codex',
      toolLabel: 'Codex',
      resumeId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
      capturedAt: '2026-08-27T10:00:00Z',
      title: null,
    };
    respond(8, [], [past]);

    fixture.componentInstance.reopenSession(past);
    httpMock
      .expectOne((r) => r.url === '/api/projects/1/issues/8/resume-sessions/reopen')
      .error(new ProgressEvent('network error'));

    expect(fixture.componentInstance.starting).toBeFalse();
    expect(fixture.componentInstance.startError).toBeTrue();
    expect(fixture.componentInstance.agentSessions).toEqual([]);
  });

  it('closing an agent session asks the server to end it, then drops its tab', () => {
    const fixture = init(7);
    respond(7, ['1-7-main-a1b2c3d4', '1-7-rename-toggle']);

    fixture.componentInstance.closeAgentSession('1-7-main-a1b2c3d4');
    httpMock.expectOne('/api/projects/1/issues/7/worktrees/1-7-main-a1b2c3d4').flush(null);

    expect(fixture.componentInstance.agentSessions.map((c) => c.id)).toEqual(['1-7-rename-toggle']);
    expect(fixture.componentInstance.closeError).toBeFalse();
  });

  it('closing the selected agent session selects the next remaining one and remembers it', () => {
    const fixture = init(7);
    respond(7, ['1-7-main-a1b2c3d4', '1-7-rename-toggle']);

    fixture.componentInstance.closeAgentSession('1-7-main-a1b2c3d4');
    httpMock.expectOne('/api/projects/1/issues/7/worktrees/1-7-main-a1b2c3d4').flush(null);

    expect(fixture.componentInstance.selectedAgentSession).toBe('1-7-rename-toggle');
    expect(TestBed.inject(ActiveAgentSessionStore).get(7)).toBe('1-7-rename-toggle');
  });

  it('a failed close reports an error and leaves the tab in place', () => {
    const fixture = init(7);
    respond(7, ['1-7-main-a1b2c3d4']);

    fixture.componentInstance.closeAgentSession('1-7-main-a1b2c3d4');
    httpMock
      .expectOne('/api/projects/1/issues/7/worktrees/1-7-main-a1b2c3d4')
      .error(new ProgressEvent('network error'));

    expect(fixture.componentInstance.closeError).toBeTrue();
    expect(fixture.componentInstance.agentSessions.map((c) => c.id)).toEqual(['1-7-main-a1b2c3d4']);
  });

  it('revealing an agent session asks the engine to open its file manager (#441)', () => {
    const fixture = init(7);
    respond(7, ['1-7-main-a1b2c3d4']);

    fixture.componentInstance.revealAgentSession('1-7-main-a1b2c3d4');
    const req = httpMock.expectOne('/api/projects/1/consoles/1-7-main-a1b2c3d4/reveal-in-file-manager');
    expect(req.request.method).toBe('POST');
    req.flush(null);

    expect(fixture.componentInstance.revealError).toBeFalse();
  });

  it('a failed reveal reports an error (#441)', () => {
    const fixture = init(7);
    respond(7, ['1-7-main-a1b2c3d4']);

    fixture.componentInstance.revealAgentSession('1-7-main-a1b2c3d4');
    httpMock
      .expectOne('/api/projects/1/consoles/1-7-main-a1b2c3d4/reveal-in-file-manager')
      .error(new ProgressEvent('network error'));

    expect(fixture.componentInstance.revealError).toBeTrue();
  });

  it('defaults to the overview tab and derives the repo web url from the project', () => {
    const fixture = init(7);
    respond(7, []);

    expect(fixture.componentInstance.activeTab).toBe('overview');
    expect(fixture.componentInstance.repoWebUrl).toBe('https://github.com/org/repo');
  });

  it('switches to an agent session tab and back to overview', () => {
    const fixture = init(7);
    respond(7, ['1-7-main-a1b2c3d4']);

    fixture.componentInstance.selectAgentSession('1-7-main-a1b2c3d4');
    expect(fixture.componentInstance.activeTab).toBe('1-7-main-a1b2c3d4');

    fixture.componentInstance.selectOverview();
    expect(fixture.componentInstance.activeTab).toBe('overview');
  });

  it('dispatches a merged tab-strip click to selectOverview or selectAgentSession', () => {
    const fixture = init(7);
    respond(7, ['1-7-main-a1b2c3d4']);

    fixture.componentInstance.onTabSelected('1-7-main-a1b2c3d4');
    expect(fixture.componentInstance.activeTab).toBe('1-7-main-a1b2c3d4');

    fixture.componentInstance.onTabSelected('overview');
    expect(fixture.componentInstance.activeTab).toBe('overview');
  });

  it('opening an agent session switches the active tab to it', () => {
    const fixture = init(8);
    respond(8, []);

    fixture.componentInstance.openAgentSession({ agent: 'codex' });
    httpMock
      .expectOne((r) => r.url === '/api/projects/1/issues/8/worktrees' && r.method === 'POST')
      .flush({ worktreeId: '1-8-slug', workingDirectory: '/tmp/repo' });

    expect(fixture.componentInstance.activeTab).toBe('1-8-slug');
  });

  it('closing the active agent session falls back to the next remaining tab', () => {
    const fixture = init(7);
    respond(7, ['1-7-main-a1b2c3d4', '1-7-rename-toggle']);
    fixture.componentInstance.selectAgentSession('1-7-main-a1b2c3d4');

    fixture.componentInstance.closeAgentSession('1-7-main-a1b2c3d4');
    httpMock.expectOne('/api/projects/1/issues/7/worktrees/1-7-main-a1b2c3d4').flush(null);

    expect(fixture.componentInstance.activeTab).toBe('1-7-rename-toggle');
  });

  it('closing the only active agent session falls back to overview', () => {
    const fixture = init(7);
    respond(7, ['1-7-main-a1b2c3d4']);
    fixture.componentInstance.selectAgentSession('1-7-main-a1b2c3d4');

    fixture.componentInstance.closeAgentSession('1-7-main-a1b2c3d4');
    httpMock.expectOne('/api/projects/1/issues/7/worktrees/1-7-main-a1b2c3d4').flush(null);

    expect(fixture.componentInstance.activeTab).toBe('overview');
  });

  it('closing an agent session that is not the active tab leaves the active tab alone', () => {
    const fixture = init(7);
    respond(7, ['1-7-main-a1b2c3d4', '1-7-rename-toggle']);
    fixture.componentInstance.selectAgentSession('1-7-rename-toggle');

    fixture.componentInstance.closeAgentSession('1-7-main-a1b2c3d4');
    httpMock.expectOne('/api/projects/1/issues/7/worktrees/1-7-main-a1b2c3d4').flush(null);

    expect(fixture.componentInstance.activeTab).toBe('1-7-rename-toggle');
  });

  it('restores the remembered tab for an issue that was visited before', () => {
    TestBed.inject(ActiveTabStore).set(7, '1-7-rename-toggle');
    const fixture = init(7);

    respond(7, ['1-7-main-a1b2c3d4', '1-7-rename-toggle']);

    expect(fixture.componentInstance.activeTab).toBe('1-7-rename-toggle');
  });

  it('keeps each issue’s remembered tab independent', () => {
    TestBed.inject(ActiveTabStore).set(7, '1-7-rename-toggle');
    TestBed.inject(ActiveTabStore).set(8, 'overview');
    const fixtureA = init(7);
    respond(7, ['1-7-main-a1b2c3d4', '1-7-rename-toggle']);
    const fixtureB = init(8);
    respond(8, ['1-8-main-a1b2c3d4']);

    expect(fixtureA.componentInstance.activeTab).toBe('1-7-rename-toggle');
    expect(fixtureB.componentInstance.activeTab).toBe('overview');
  });

  it('opens on overview for an issue never visited before', () => {
    const fixture = init(7);

    respond(7, ['1-7-main-a1b2c3d4']);

    expect(fixture.componentInstance.activeTab).toBe('overview');
  });

  it('falls back to overview when the remembered tab no longer exists', () => {
    TestBed.inject(ActiveTabStore).set(7, '1-7-closed-session');
    const fixture = init(7);

    respond(7, ['1-7-main-a1b2c3d4']);

    expect(fixture.componentInstance.activeTab).toBe('overview');
  });

  it('remembers a switch back to overview so a later visit restores it', () => {
    const fixture = init(7);
    respond(7, ['1-7-main-a1b2c3d4']);
    fixture.componentInstance.selectAgentSession('1-7-main-a1b2c3d4');

    fixture.componentInstance.selectOverview();

    expect(TestBed.inject(ActiveTabStore).get(7)).toBe('overview');
  });

  it('remembers the fallback tab left after closing the active agent session', () => {
    const fixture = init(7);
    respond(7, ['1-7-main-a1b2c3d4', '1-7-rename-toggle']);
    fixture.componentInstance.selectAgentSession('1-7-main-a1b2c3d4');

    fixture.componentInstance.closeAgentSession('1-7-main-a1b2c3d4');
    httpMock.expectOne('/api/projects/1/issues/7/worktrees/1-7-main-a1b2c3d4').flush(null);

    expect(TestBed.inject(ActiveTabStore).get(7)).toBe('1-7-rename-toggle');
  });
});

describe('MainContentComponent shells (#876)', () => {
  let httpMock: HttpTestingController;

  function shell(overrides: Partial<OpenShell> = {}): OpenShell {
    return {
      sessionId: '1-shell-7-aaaa0001',
      projectId: 1,
      issueNumber: 7,
      mainCheckout: false,
      workingDirectory: '/tmp/repo-7',
      createdAt: '2026-08-27T09:00:00Z',
      lastAttachedAt: '2026-08-27T09:00:00Z',
      displayName: null,
      ...overrides,
    };
  }

  beforeEach(() => {
    localStorage.removeItem('locklane.sessionAgents');
    localStorage.removeItem('locklane.activeConsoleByIssue');
    localStorage.removeItem('locklane.activeTabByIssue');
    localStorage.removeItem('locklane.defaultAgent');
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
    localStorage.removeItem('locklane.activeTabByIssue');
    localStorage.removeItem('locklane.defaultAgent');
  });

  function init(number: number): ReturnType<typeof TestBed.createComponent<MainContentComponent>> {
    const fixture = TestBed.createComponent(MainContentComponent);
    fixture.componentInstance.projectId = 1;
    fixture.componentInstance.issueNumber = number;
    fixture.componentInstance.ngOnChanges({
      projectId: { currentValue: 1, previousValue: null, firstChange: true, isFirstChange: () => true },
      issueNumber: { currentValue: number, previousValue: null, firstChange: true, isFirstChange: () => true },
    });
    // Like the real lifecycle, ngOnInit subscribes the live shell reload (#876),
    // so open/close notifies re-read the shells below.
    fixture.componentInstance.ngOnInit();
    httpMock.expectOne('/api/agents/installed').flush({ installed: [] });
    return fixture;
  }

  function respond(number: number, agentSessionIds: string[], shells: OpenShell[] = []) {
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
      checks: { passing: 0, failing: 0, pending: 0, runs: [] },
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
        accentColor: null,
        template: null,
        status: 'READY',
        createdAt: '',
      },
    ];

    httpMock.expectOne(`/api/projects/1/issues/${number}`).flush(issue);
    httpMock.expectOne(`/api/projects/1/issues/${number}/detail`).flush(detail);
    httpMock.expectOne(`/api/projects/1/issues/${number}/resume-sessions`).flush([]);
    httpMock.expectOne('/api/projects').flush(projects);
    httpMock.expectOne(`/api/projects/1/issues/${number}/worktrees`).flush(agentSessionIds);
    httpMock.expectOne('/api/shells').flush(shells);
  }

  it('lists this issue’s shells as shell tabs beside the agent tabs, filtering out other issues', () => {
    const fixture = init(7);
    respond(7, ['1-7-main-a1b2c3d4'], [
      shell({ sessionId: '1-shell-7-aaaa0001' }),
      shell({ sessionId: '1-shell-7-bbbb0002' }),
      shell({ sessionId: '1-shell-8-cccc0003', projectId: 1, issueNumber: 8 }),
      shell({ sessionId: '1-shell-main-dddd0004', issueNumber: null, mainCheckout: true }),
    ]);

    expect(fixture.componentInstance.shells.map((s) => s.id)).toEqual([
      '1-shell-7-aaaa0001',
      '1-shell-7-bbbb0002',
    ]);
    expect(fixture.componentInstance.tabs.map((t) => t.label)).toEqual(['main', 'shell', 'shell 2']);
  });

  it('adopts the remembered tab when it is an open shell', () => {
    TestBed.inject(ActiveTabStore).set(7, '1-shell-7-aaaa0001');
    const fixture = init(7);
    respond(7, ['1-7-main-a1b2c3d4'], [shell({ sessionId: '1-shell-7-aaaa0001' })]);

    expect(fixture.componentInstance.activeTab).toBe('1-shell-7-aaaa0001');
  });

  it('opening a shell mints one at the live agent session’s directory and selects it', () => {
    const fixture = init(8);
    respond(8, []);
    fixture.componentInstance.openAgentSession({ agent: 'codex' });
    httpMock
      .expectOne((r) => r.url === '/api/projects/1/issues/8/worktrees' && r.method === 'POST')
      .flush({ worktreeId: '1-8-slug', workingDirectory: '/tmp/repo-8' });
    httpMock.expectOne('/api/shells').flush([]);

    fixture.componentInstance.openShell();
    const post = httpMock.expectOne('/api/projects/1/shells');
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({ issueNumber: 8, workingDirectory: '/tmp/repo-8' });
    post.flush({ sessionId: '1-shell-8-aaaa0001', workingDirectory: '/tmp/repo-8' });
    // The mint notifies, and the page re-reads its shells on it -- the server
    // persisted the shell at mint time, so it lists it straight away.
    httpMock.expectOne('/api/shells').flush([
      shell({ sessionId: '1-shell-8-aaaa0001', issueNumber: 8, workingDirectory: '/tmp/repo-8' }),
    ]);
    fixture.detectChanges();

    expect(fixture.componentInstance.shells.map((s) => s.id)).toEqual(['1-shell-8-aaaa0001']);
    expect(fixture.componentInstance.activeTab).toBe('1-shell-8-aaaa0001');
    expect(fixture.componentInstance.tabs.map((t) => t.label)).toEqual(['wtree · codex', 'shell']);
  });

  it('opening a shell with no live agent session resolves the directory from the worktree list', () => {
    const fixture = init(7);
    respond(7, ['1-7-main-a1b2c3d4']);

    fixture.componentInstance.openShell();
    httpMock.expectOne('/api/projects/1/worktrees').flush([
      { worktreeId: '1-7-main-a1b2c3d4', issueNumber: 7, workingDirectory: '/tmp/repo-7', clean: true, sessionAttached: true },
    ]);
    const post = httpMock.expectOne('/api/projects/1/shells');
    expect(post.request.body).toEqual({ issueNumber: 7, workingDirectory: '/tmp/repo-7' });
    post.flush({ sessionId: '1-shell-7-aaaa0001', workingDirectory: '/tmp/repo-7' });
    httpMock.expectOne('/api/shells').flush([]);
  });

  it('a failed mint reports a shell error and stops the spinner without touching the tabs', () => {
    const fixture = init(7);
    respond(7, ['1-7-main-a1b2c3d4']);

    fixture.componentInstance.openShell();
    httpMock.expectOne('/api/projects/1/worktrees').flush([
      { worktreeId: '1-7-main-a1b2c3d4', issueNumber: 7, workingDirectory: '/tmp/repo-7', clean: true, sessionAttached: true },
    ]);
    httpMock.expectOne('/api/projects/1/shells').flush(null, { status: 500, statusText: 'Server Error' });

    expect(fixture.componentInstance.starting).toBeFalse();
    expect(fixture.componentInstance.shellError).toBe('could not open a shell — try again');
    expect(fixture.componentInstance.shells).toEqual([]);
  });

  it('closing a shell tab ends it and drops its tab', () => {
    const fixture = init(7);
    respond(7, ['1-7-main-a1b2c3d4'], [shell({ sessionId: '1-shell-7-aaaa0001' })]);
    fixture.componentInstance.onTabSelected('1-shell-7-aaaa0001');
    expect(fixture.componentInstance.activeTab).toBe('1-shell-7-aaaa0001');

    fixture.componentInstance.closeTab('1-shell-7-aaaa0001');
    httpMock.expectOne('/api/projects/1/shells/1-shell-7-aaaa0001').flush(null);
    httpMock.expectOne('/api/shells').flush([]);

    expect(fixture.componentInstance.shells).toEqual([]);
    expect(fixture.componentInstance.activeTab).toBe('1-7-main-a1b2c3d4');
  });

  it('closing the agent session ends its shells with it (#876: shells die with the worktree)', () => {
    const fixture = init(7);
    respond(7, ['1-7-main-a1b2c3d4'], [shell({ sessionId: '1-shell-7-aaaa0001' }), shell({ sessionId: '1-shell-7-bbbb0002' })]);

    fixture.componentInstance.closeAgentSession('1-7-main-a1b2c3d4');
    httpMock.expectOne('/api/projects/1/issues/7/worktrees/1-7-main-a1b2c3d4').flush(null);
    // The close notifies before the fan-out DELETEs answer, so its reload lands first.
    httpMock.expectOne('/api/shells').flush([]);

    const closes = httpMock.match('/api/projects/1/shells/1-shell-7-aaaa0001');
    expect(closes.length).toBe(1);
    const closes2 = httpMock.match('/api/projects/1/shells/1-shell-7-bbbb0002');
    expect(closes2.length).toBe(1);
    closes[0].flush(null);
    closes2[0].flush(null);
    // The fan-out completing notifies again -- one more reload.
    httpMock.expectOne('/api/shells').flush([]);

    expect(fixture.componentInstance.shells).toEqual([]);
    expect(fixture.componentInstance.tabs).toEqual([]);
    expect(fixture.componentInstance.activeTab).toBe('overview');
  });

  it('a shell that refuses to die with the worktree surfaces a shell error', () => {
    const fixture = init(7);
    respond(7, ['1-7-main-a1b2c3d4'], [shell({ sessionId: '1-shell-7-aaaa0001' })]);

    fixture.componentInstance.closeAgentSession('1-7-main-a1b2c3d4');
    httpMock.expectOne('/api/projects/1/issues/7/worktrees/1-7-main-a1b2c3d4').flush(null);
    // The close notifies before the fan-out DELETE answers, so its reload lands first.
    httpMock.expectOne('/api/shells').flush([]);
    httpMock.expectOne('/api/projects/1/shells/1-shell-7-aaaa0001').flush(null, { status: 500, statusText: 'Server Error' });
    // The fan-out completing notifies again -- one more reload.
    httpMock.expectOne('/api/shells').flush([]);

    expect(fixture.componentInstance.shellError).toBe('could not close a shell — try again');
  });
});
