import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { MainContentComponent } from './main-content.component';
import { AgentStore } from '../../services/agent-store';
import { GhIssue, IssueDetail } from '../../models/issue.model';

describe('MainContentComponent', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    localStorage.removeItem('locklane.sessionAgents');
    TestBed.configureTestingModule({
      imports: [MainContentComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.removeItem('locklane.sessionAgents');
  });

  function init(number: number): ReturnType<typeof TestBed.createComponent<MainContentComponent>> {
    const fixture = TestBed.createComponent(MainContentComponent);
    fixture.componentInstance.issueNumber = number;
    fixture.componentInstance.ngOnChanges({
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
    httpMock.expectOne(`/api/issues/${number}`).flush(issue);
    httpMock.expectOne(`/api/issues/${number}/detail`).flush(detail);
    httpMock.expectOne(`/api/issues/${number}/worktrees`).flush(consoleIds);
  }

  it('restores every open console as a tab, not just the first', () => {
    const fixture = init(7);

    respond(7, ['7-main-a1b2c3d4', '7-main-e5f6a7b8', '7-rename-toggle']);

    expect(fixture.componentInstance.consoles.map((c) => c.id)).toEqual([
      '7-main-a1b2c3d4',
      '7-main-e5f6a7b8',
      '7-rename-toggle',
    ]);
    expect(fixture.componentInstance.tabs.map((t) => t.label)).toEqual(['main', 'main 2', 'wtree']);
    expect(fixture.componentInstance.selectedConsole).toBe('7-main-a1b2c3d4');
  });

  it('labels restored tabs with the agent the store remembers for them', () => {
    TestBed.inject(AgentStore).set('7-rename-toggle', 'claude');
    const fixture = init(7);

    respond(7, ['7-rename-toggle']);

    expect(fixture.componentInstance.tabs[0].label).toBe('wtree · claude');
  });

  it('has no selected console when the issue has none yet', () => {
    const fixture = init(8);

    respond(8, []);

    expect(fixture.componentInstance.selectedConsole).toBeNull();
  });

  it('switching tabs updates the selection without reloading the issue', () => {
    const fixture = init(7);
    respond(7, ['7-main-a1b2c3d4', '7-rename-toggle']);

    fixture.componentInstance.selectConsole('7-rename-toggle');

    expect(fixture.componentInstance.selectedConsole).toBe('7-rename-toggle');
    expect(fixture.componentInstance.issue?.number).toBe(7); // unchanged, no reload
  });

  it('opening a console adds its tab, selects it, and remembers its agent', () => {
    const fixture = init(8);
    respond(8, []);

    fixture.componentInstance.openConsole({ worktree: false, agent: 'codex' });
    expect(fixture.componentInstance.starting).toBeTrue();

    httpMock
      .expectOne((r) => r.url === '/api/issues/8/worktrees' && r.method === 'POST')
      .flush({ worktreeId: '8-main-a1b2c3d4', workingDirectory: '/tmp/repo' });

    expect(fixture.componentInstance.starting).toBeFalse();
    expect(fixture.componentInstance.consoles).toEqual([
      { id: '8-main-a1b2c3d4', dir: '/tmp/repo', agent: 'codex' },
    ]);
    expect(fixture.componentInstance.tabs[0].label).toBe('main · codex');
    expect(fixture.componentInstance.selectedConsole).toBe('8-main-a1b2c3d4');
    expect(TestBed.inject(AgentStore).get('8-main-a1b2c3d4')).toBe('codex');
  });

  it('a worktree request that reuses the existing session only re-selects its tab', () => {
    const fixture = init(8);
    respond(8, ['8-main-a1b2c3d4', '8-slug']);
    fixture.componentInstance.selectConsole('8-main-a1b2c3d4');

    fixture.componentInstance.openConsole({ worktree: true, agent: 'claude' });
    httpMock
      .expectOne((r) => r.url === '/api/issues/8/worktrees' && r.method === 'POST')
      .flush({ worktreeId: '8-slug', workingDirectory: '/tmp/repo-8' });

    expect(fixture.componentInstance.consoles.map((c) => c.id)).toEqual(['8-main-a1b2c3d4', '8-slug']);
    expect(fixture.componentInstance.selectedConsole).toBe('8-slug');
  });

  it('a failed open reports an error and stops the spinner without touching the tabs', () => {
    const fixture = init(8);
    respond(8, []);

    fixture.componentInstance.openConsole({ worktree: true, agent: 'claude' });
    httpMock
      .expectOne((r) => r.url === '/api/issues/8/worktrees' && r.method === 'POST')
      .error(new ProgressEvent('network error'));

    expect(fixture.componentInstance.starting).toBeFalse();
    expect(fixture.componentInstance.startError).toBeTrue();
    expect(fixture.componentInstance.consoles).toEqual([]);
  });
});
