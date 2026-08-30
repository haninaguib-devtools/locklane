import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { ProjectConsoleComponent } from './project-console.component';
import { AgentStore } from '../../services/agent-store';
import { ConsolesService } from '../../services/consoles.service';
import { DefaultAgentStore } from '../../services/default-agent-store';
import { IssuesService } from '../../services/issues.service';
import { LastConsoleStore } from '../../services/last-console-store';
import { TerminalComponent } from '../terminal/terminal.component';

describe('ProjectConsoleComponent', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    localStorage.removeItem('locklane.sessionAgents');
    localStorage.removeItem('locklane.lastConsole');
    localStorage.removeItem('locklane.defaultAgent');
    TestBed.configureTestingModule({
      imports: [ProjectConsoleComponent],
      // The wildcard route lets tests navigate to a URL carrying the ?session
      // handoff (#179) that the component reads off the root route's snapshot.
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: '**', children: [] }]),
      ],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    // #372's past-conversation read fires on every load (and again after a close);
    // a test that doesn't care about the list simply has it answered empty here,
    // so it stays out of every other expectation.
    httpMock
      .match((request) => request.url.endsWith('/console/resume-sessions'))
      .forEach((request) => request.flush([]));
    httpMock.verify();
    localStorage.removeItem('locklane.sessionAgents');
    localStorage.removeItem('locklane.lastConsole');
    localStorage.removeItem('locklane.defaultAgent');
  });

  function init(projectId = 1): ReturnType<typeof TestBed.createComponent<ProjectConsoleComponent>> {
    const fixture = TestBed.createComponent(ProjectConsoleComponent);
    fixture.componentRef.setInput('projectId', projectId);
    fixture.detectChanges();
    return fixture;
  }

  function row(sessionId: string, lastAttachedAt = '2026-08-27T10:00:00Z') {
    return { sessionId, workingDirectory: '/repo', createdAt: '2026-08-27T09:00:00Z', lastAttachedAt };
  }

  /** Clicks the confirm button of the app-styled confirm dialog opened by a tab close (#231). */
  function confirmCloseDialog(compiled: HTMLElement): void {
    const buttons = compiled.querySelectorAll<HTMLButtonElement>('.dialog-actions button');
    const confirmButton = Array.from(buttons).find((b) => b.textContent?.trim() === 'Close');
    confirmButton!.click();
  }

  it('attaches straight to an existing session, skipping the agent picker', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([row('1-console-a1b2c3d4')]);
    fixture.detectChanges();

    expect(fixture.componentInstance.selected).toBe('1-console-a1b2c3d4');
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-terminal')).toBeTruthy();
    expect(compiled.querySelector('app-agent-picker')).toBeFalsy();
  });

  it('shows a tab per open console and selects the most recently attached one', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([
      row('1-console-a1b2c3d4', '2026-08-27T10:00:00Z'),
      row('1-console-e5f6a7b8', '2026-08-27T11:00:00Z'),
    ]);
    fixture.detectChanges();

    expect(fixture.componentInstance.selected).toBe('1-console-e5f6a7b8');
    const compiled = fixture.nativeElement as HTMLElement;
    const tabs = Array.from(compiled.querySelectorAll<HTMLButtonElement>('.tab')).map((b) =>
      b.textContent!.trim(),
    );
    expect(tabs).toEqual(['console', 'console 2']);
  });

  it('activates the console named by the consoles page\'s ?session handoff (#179)', fakeAsync(() => {
    TestBed.inject(Router).navigateByUrl('/projects/1/console?session=1-console-a1b2c3d4');
    tick();

    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([
      row('1-console-a1b2c3d4', '2026-08-27T10:00:00Z'),
      row('1-console-e5f6a7b8', '2026-08-27T11:00:00Z'),
    ]);
    fixture.detectChanges();

    expect(fixture.componentInstance.selected).toBe('1-console-a1b2c3d4');
  }));

  it('falls back to the most recently attached console when ?session names a closed one', fakeAsync(() => {
    TestBed.inject(Router).navigateByUrl('/projects/1/console?session=1-console-gone0000');
    tick();

    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([
      row('1-console-a1b2c3d4', '2026-08-27T10:00:00Z'),
      row('1-console-e5f6a7b8', '2026-08-27T11:00:00Z'),
    ]);
    fixture.detectChanges();

    expect(fixture.componentInstance.selected).toBe('1-console-e5f6a7b8');
  }));

  it('shows no Overview tab, and its "+" opens no popover (#256)', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([row('1-console-a1b2c3d4')]);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).not.toContain('Overview');
    compiled.querySelector<HTMLButtonElement>('.plus')!.click();
    fixture.detectChanges();

    // No picker of any kind -- the "+" starts a console with the default agent
    // directly (#256).
    expect(compiled.querySelector('.picker')).toBeFalsy();
    httpMock.expectOne('/api/projects/1/console').flush({ sessionId: '1-console-e5f6a7b8', workingDirectory: '/repo' });
  });

  it('keeps every console mounted, hiding all but the selected tab', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([
      row('1-console-a1b2c3d4', '2026-08-27T11:00:00Z'),
      row('1-console-e5f6a7b8', '2026-08-27T10:00:00Z'),
    ]);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const tabs = compiled.querySelectorAll<HTMLButtonElement>('.tab');
    tabs[1].click();
    fixture.detectChanges();

    expect(fixture.componentInstance.selected).toBe('1-console-e5f6a7b8');
    const terminals = compiled.querySelectorAll('app-terminal');
    expect(terminals.length).toBe(2);
    expect(terminals[0].classList.contains('tab-hidden')).toBeTrue();
    expect(terminals[1].classList.contains('tab-hidden')).toBeFalse();
  });

  it('records the selected tab as this project\'s most recently interacted-with console (#221)', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([
      row('1-console-a1b2c3d4', '2026-08-27T11:00:00Z'),
      row('1-console-e5f6a7b8', '2026-08-27T10:00:00Z'),
    ]);
    fixture.detectChanges();
    // Loading itself selects one -- via ?session or lastAttachedAt -- so that
    // counts as an interaction too.
    expect(TestBed.inject(LastConsoleStore).get(1)).toBe('1-console-a1b2c3d4');

    const compiled = fixture.nativeElement as HTMLElement;
    compiled.querySelectorAll<HTMLButtonElement>('.tab')[1].click();
    fixture.detectChanges();

    expect(TestBed.inject(LastConsoleStore).get(1)).toBe('1-console-e5f6a7b8');
  });

  it('labels a tab with the agent this browser launched it with', () => {
    TestBed.inject(AgentStore).set('1-console-a1b2c3d4', 'codex');
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([row('1-console-a1b2c3d4')]);
    fixture.detectChanges();

    const tab = (fixture.nativeElement as HTMLElement).querySelector('.tab')!;
    expect(tab.textContent!.trim()).toBe('console · codex');
  });

  it('starts a console with no picker of any kind when the project has no open console (#256)', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([]);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-agent-picker')).toBeFalsy();
    expect(compiled.querySelector('.start')).toBeFalsy();
    expect(compiled.querySelector('app-terminal')).toBeFalsy();

    httpMock.expectOne('/api/projects/1/console').flush({ sessionId: '1-console-a1b2c3d4', workingDirectory: '/repo' });
  });

  it('starts a session with the Settings default agent and then shows the terminal (#256)', () => {
    TestBed.inject(DefaultAgentStore).set('codex');
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([]);
    fixture.detectChanges();
    const opened = jasmine.createSpy('onOpened');
    TestBed.inject(ConsolesService).onOpened.subscribe(opened);

    const req = httpMock.expectOne('/api/projects/1/console');
    expect(req.request.method).toBe('POST');
    req.flush({ sessionId: '1-console-a1b2c3d4', workingDirectory: '/repo' });
    fixture.detectChanges();

    expect(fixture.componentInstance.selected).toBe('1-console-a1b2c3d4');
    const terminal = fixture.debugElement.query(By.directive(TerminalComponent));
    expect(terminal.componentInstance.cmd).toBe('codex');
    expect(TestBed.inject(AgentStore).get('1-console-a1b2c3d4')).toBe('codex');
    expect(TestBed.inject(LastConsoleStore).get(1)).toBe('1-console-a1b2c3d4');
    // #194: the header consoles widget must learn about it.
    expect(opened).toHaveBeenCalled();
  });

  it('opens another console from the tab strip\'s "+" and selects it', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([row('1-console-a1b2c3d4')]);
    fixture.detectChanges();

    fixture.componentInstance.openFromTabs({ agent: 'claude' });
    const req = httpMock.expectOne('/api/projects/1/console');
    expect(req.request.method).toBe('POST');
    req.flush({ sessionId: '1-console-e5f6a7b8', workingDirectory: '/repo-console-e5f6a7b8' });
    fixture.detectChanges();

    expect(fixture.componentInstance.selected).toBe('1-console-e5f6a7b8');
    const terminals = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('app-terminal'),
    );
    expect(terminals.length).toBe(2);
  });

  it('gives each console its own working directory from the engine — never a shared checkout (#314)', () => {
    const fixture = init();
    httpMock
      .expectOne('/api/projects/1/console/sessions')
      .flush([{ ...row('1-console-a1b2c3d4'), workingDirectory: '/repo-console-a1b2c3d4' }]);
    fixture.detectChanges();

    fixture.componentInstance.openFromTabs({ agent: 'claude' });
    httpMock
      .expectOne('/api/projects/1/console')
      .flush({ sessionId: '1-console-e5f6a7b8', workingDirectory: '/repo-console-e5f6a7b8' });
    fixture.detectChanges();

    const terminals = fixture.debugElement.queryAll(By.directive(TerminalComponent));
    expect(terminals.map((t) => t.componentInstance.dir)).toEqual([
      '/repo-console-a1b2c3d4',
      '/repo-console-e5f6a7b8',
    ]);
  });

  it('closes a console from its tab and falls back to the first remaining one', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([
      row('1-console-a1b2c3d4', '2026-08-27T10:00:00Z'),
      row('1-console-e5f6a7b8', '2026-08-27T11:00:00Z'),
    ]);
    fixture.detectChanges();
    const closed = jasmine.createSpy('onClosed');
    TestBed.inject(ConsolesService).onClosed.subscribe(closed);

    const compiled = fixture.nativeElement as HTMLElement;
    compiled.querySelectorAll<HTMLButtonElement>('.tab-close')[1].click();
    fixture.detectChanges();
    confirmCloseDialog(compiled);
    fixture.detectChanges();
    const req = httpMock.expectOne('/api/projects/1/console/1-console-e5f6a7b8');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
    fixture.detectChanges();

    expect(fixture.componentInstance.selected).toBe('1-console-a1b2c3d4');
    expect(compiled.querySelectorAll('app-terminal').length).toBe(1);
    // #194: the header consoles widget must learn about it.
    expect(closed).toHaveBeenCalled();
  });

  it('navigates to the project page once the last console is closed, without auto-starting one (#265)', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([row('1-console-a1b2c3d4')]);
    fixture.detectChanges();
    const router = TestBed.inject(Router);
    spyOn(router, 'navigate');

    const compiled = fixture.nativeElement as HTMLElement;
    compiled.querySelector<HTMLButtonElement>('.tab-close')!.click();
    fixture.detectChanges();
    confirmCloseDialog(compiled);
    fixture.detectChanges();
    httpMock.expectOne('/api/projects/1/console/1-console-a1b2c3d4').flush(null);
    fixture.detectChanges();

    expect(router.navigate).toHaveBeenCalledWith(['/projects', 1, 'issues']);
  });

  it('shows an error and keeps the tab when closing fails', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([row('1-console-a1b2c3d4')]);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    compiled.querySelector<HTMLButtonElement>('.tab-close')!.click();
    fixture.detectChanges();
    confirmCloseDialog(compiled);
    fixture.detectChanges();
    httpMock
      .expectOne('/api/projects/1/console/1-console-a1b2c3d4')
      .flush(null, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(compiled.textContent).toContain('could not close that console');
    expect(compiled.querySelectorAll('app-terminal').length).toBe(1);
  });

  it('shows an error and lets the user retry when the auto-start fails', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([]);
    httpMock.expectOne('/api/projects/1/console').flush(null, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(fixture.componentInstance.startError).toBeTrue();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('could not start a console');

    compiled.querySelector<HTMLButtonElement>('.retry')!.click();
    httpMock.expectOne('/api/projects/1/console').flush({ sessionId: '1-console-a1b2c3d4', workingDirectory: '/repo' });
    fixture.detectChanges();

    expect(fixture.componentInstance.selected).toBe('1-console-a1b2c3d4');
  });

  it('navigates back to the project\'s issues page', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([row('1-console-a1b2c3d4')]);
    fixture.detectChanges();
    const router = TestBed.inject(Router);
    spyOn(router, 'navigate');

    fixture.componentInstance.back();

    expect(router.navigate).toHaveBeenCalledWith(['/projects', 1, 'issues']);
  });

  it('notifies the issue list to bust its cache when the console is left', fakeAsync(() => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([row('1-console-a1b2c3d4')]);
    fixture.detectChanges();

    let notified: number | undefined;
    TestBed.inject(IssuesService).onProjectStale.subscribe((projectId) => (notified = projectId));

    fixture.destroy();
    tick();

    expect(notified).toBe(1);
  }));

  it('reloads when the project id changes', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([row('1-console-a1b2c3d4')]);
    fixture.detectChanges();

    fixture.componentRef.setInput('projectId', 2);
    fixture.detectChanges();
    httpMock.expectOne('/api/projects/2/console/sessions').flush([]);
    fixture.detectChanges();

    // The new project has no open console either, so it auto-starts one there too.
    httpMock.expectOne('/api/projects/2/console').flush({ sessionId: '2-console-a1b2c3d4', workingDirectory: '/repo' });
    fixture.detectChanges();

    expect(fixture.componentInstance.selected).toBe('2-console-a1b2c3d4');
  });

  it('lists the past conversations captured in this project\u2019s consoles and reopens one (#372)', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([row('1-console-a1b2c3d4')]);
    httpMock.expectOne('/api/projects/1/console/resume-sessions').flush([
      {
        worktreeId: '1-console-a1b2c3d4',
        tool: 'claude',
        resumeId: '11111111-1111-1111-1111-111111111111',
        capturedAt: '2026-08-27T09:30:00Z',
      },
    ]);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const toggle = compiled.querySelector<HTMLButtonElement>('.past-toggle')!;
    expect(toggle.textContent).toContain('past sessions (1)');
    // Collapsed until asked for -- the page opens straight into a live console.
    expect(compiled.querySelector('app-session-list')).toBeFalsy();

    toggle.click();
    fixture.detectChanges();
    compiled.querySelector<HTMLButtonElement>('app-session-list .reopen')!.click();

    const reopen = httpMock.expectOne(
      (request) =>
        request.url === '/api/projects/1/console/resume-sessions/reopen' &&
        request.params.get('from') === '1-console-a1b2c3d4',
    );
    reopen.flush({ sessionId: '1-console-a1b2c3d4-resume-99887766', workingDirectory: '/repo-console-a1b2c3d4' });
    fixture.detectChanges();

    expect(fixture.componentInstance.selected).toBe('1-console-a1b2c3d4-resume-99887766');
    const terminals = fixture.debugElement.queryAll(By.directive(TerminalComponent));
    const reopened = terminals.find((t) => t.componentInstance.sessionId === '1-console-a1b2c3d4-resume-99887766')!;
    // The resume id and the tool both reach the terminal, so its first attach
    // launches `claude --resume <id>` rather than a fresh conversation.
    expect(reopened.componentInstance.resume).toBe('11111111-1111-1111-1111-111111111111');
    expect(reopened.componentInstance.cmd).toBe('claude');
    expect(reopened.componentInstance.dir).toBe('/repo-console-a1b2c3d4');
  });

  it('shows no past-sessions disclosure when the project has none (#372)', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([row('1-console-a1b2c3d4')]);
    httpMock.expectOne('/api/projects/1/console/resume-sessions').flush([]);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelector('.past-toggle')).toBeFalsy();
  });
});
