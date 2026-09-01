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
import { Project } from '../../models/issue.model';

describe('ProjectConsoleComponent', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    localStorage.removeItem('locklane.sessionAgents');
    localStorage.removeItem('locklane.lastConsole');
    localStorage.removeItem('locklane.defaultAgent');
    TestBed.configureTestingModule({
      imports: [ProjectConsoleComponent],
      // Mirrors app.routes.ts's real `:projectId` route (#439: CurrentProjectService
      // reads it off the snapshot) with a wildcard fallback for any other URL a test
      // navigates to, carrying e.g. the ?session handoff (#179) read off the root
      // route's snapshot.
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'projects/:projectId/console', children: [] }, { path: '**', children: [] }]),
      ],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.removeItem('locklane.sessionAgents');
    localStorage.removeItem('locklane.lastConsole');
    localStorage.removeItem('locklane.defaultAgent');
  });

  /** A project row as `/api/projects` returns it; READY with no template unless overridden (#537). */
  function project(overrides: Partial<Project> & { id: number }): Project {
    return {
      name: 'proj',
      gitUrl: 'url',
      workareaPath: '/repo',
      defaultBranch: 'main',
      status: 'READY',
      createdAt: '',
      accentColor: null,
      template: null,
      templateSeededAt: null,
      ...overrides,
    };
  }

  // Since #537 the page reads the project before its consoles. `projects` is what that
  // read answers; the default (an empty list, the project unknown) leaves every
  // pre-#537 behaviour exactly as it was, which is what the older specs below rely on.
  function init(
    projectId = 1,
    projects: Project[] = [],
  ): ReturnType<typeof TestBed.createComponent<ProjectConsoleComponent>> {
    const fixture = TestBed.createComponent(ProjectConsoleComponent);
    fixture.componentRef.setInput('projectId', projectId);
    fixture.detectChanges();
    flushProjects(projects);
    return fixture;
  }

  function flushProjects(projects: Project[]): void {
    const req = httpMock.expectOne('/api/projects');
    expect(req.request.method).toBe('GET');
    req.flush(projects);
  }

  function row(sessionId: string, lastAttachedAt = '2026-08-27T10:00:00Z', displayName: string | null = null) {
    return {
      sessionId,
      workingDirectory: '/repo',
      createdAt: '2026-08-27T09:00:00Z',
      lastAttachedAt,
      displayName,
    };
  }

  /** Double-clicks the nth tab to open its inline rename field (#393). */
  function renameField(fixture: ReturnType<typeof TestBed.createComponent<ProjectConsoleComponent>>, index = 0) {
    const compiled = fixture.nativeElement as HTMLElement;
    compiled.querySelectorAll<HTMLButtonElement>('.tab')[index].dispatchEvent(new MouseEvent('dblclick'));
    fixture.detectChanges();
    return compiled.querySelector<HTMLInputElement>('.tab-name')!;
  }

  function typeAndCommit(fixture: ReturnType<typeof TestBed.createComponent<ProjectConsoleComponent>>,
      field: HTMLInputElement, value: string): void {
    field.value = value;
    field.dispatchEvent(new Event('input'));
    field.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    fixture.detectChanges();
  }

  /** Clicks the confirm button of the app-styled confirm dialog opened by a tab close (#231). */
  function confirmCloseDialog(compiled: HTMLElement): void {
    const buttons = compiled.querySelectorAll<HTMLButtonElement>('.dialog-actions button');
    const confirmButton = Array.from(buttons).find((b) => b.textContent?.trim() === 'Close');
    confirmButton!.click();
  }

  /** Opens the nth tab's overflow menu (#480), exposing its Shell/Folder/Close items. */
  function openTabMenu(fixture: ReturnType<typeof TestBed.createComponent<ProjectConsoleComponent>>, index = 0): void {
    const compiled = fixture.nativeElement as HTMLElement;
    compiled.querySelectorAll<HTMLButtonElement>('.tab-menu-trigger')[index].click();
    fixture.detectChanges();
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

  it('labels a tab without an agent suffix, even when the launching agent is known (#456)', () => {
    TestBed.inject(AgentStore).set('1-console-a1b2c3d4', 'codex');
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([row('1-console-a1b2c3d4')]);
    fixture.detectChanges();

    const tab = (fixture.nativeElement as HTMLElement).querySelector('.tab')!;
    expect(tab.textContent!.trim()).toBe('console');
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
    openTabMenu(fixture, 1);
    compiled.querySelectorAll<HTMLButtonElement>('.tab-close')[0].click();
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
    openTabMenu(fixture);
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
    openTabMenu(fixture);
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

  it('reveals a console\'s worktree in the file manager from its tab (#441)', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([row('1-console-a1b2c3d4')]);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    openTabMenu(fixture);
    compiled.querySelector<HTMLButtonElement>('.tab-reveal')!.click();
    fixture.detectChanges();

    const req = httpMock.expectOne('/api/projects/1/consoles/1-console-a1b2c3d4/reveal-in-file-manager');
    expect(req.request.method).toBe('POST');
    req.flush(null);

    expect(fixture.componentInstance.revealError).toBeFalse();
  });

  it('shows an error when revealing a console fails (#441)', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([row('1-console-a1b2c3d4')]);
    fixture.detectChanges();

    fixture.componentInstance.revealConsole('1-console-a1b2c3d4');
    httpMock
      .expectOne('/api/projects/1/consoles/1-console-a1b2c3d4/reveal-in-file-manager')
      .error(new ProgressEvent('network error'));
    fixture.detectChanges();

    expect(fixture.componentInstance.revealError).toBeTrue();
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

  // #370: the sidenav's "+" used to mint the session itself and hand it over as
  // ?session=<id>. The engine only lists a console as open once something has
  // attached to it, so that id was never in the list, the page discarded it, and
  // the user landed in an existing console while the new one's worktree was left
  // behind. The "+" now asks with ?new and this page does the minting.
  it('starts a brand-new console for a ?new request, alongside the consoles already open (#370)', fakeAsync(() => {
    TestBed.inject(Router).navigateByUrl('/projects/1/console?new=1');
    tick();

    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([
      row('1-console-a1b2c3d4', '2026-08-27T10:00:00Z'),
      row('1-console-e5f6a7b8', '2026-08-27T11:00:00Z'),
    ]);
    fixture.detectChanges();
    tick();

    const req = httpMock.expectOne('/api/projects/1/console');
    expect(req.request.method).toBe('POST');
    req.flush({ sessionId: '1-console-c9d0e1f2', workingDirectory: '/repo-console-c9d0e1f2' });
    fixture.detectChanges();

    expect(fixture.componentInstance.selected).toBe('1-console-c9d0e1f2');
    // The two that were already open are still here, with the new one alongside.
    const tabs = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('.tab'),
    ).map((b) => b.textContent!.trim());
    // Labels carry no agent suffix (#456), so the freshly-minted one looks the
    // same as the two reattached ones this browser did not launch.
    expect(tabs).toEqual(['console', 'console 2', 'console 3']);
  }));

  it('gives a ?new console the Settings default agent, with no picker (#219, #370)', fakeAsync(() => {
    TestBed.inject(DefaultAgentStore).set('codex');
    TestBed.inject(Router).navigateByUrl('/projects/1/console?new=1');
    tick();

    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([row('1-console-a1b2c3d4')]);
    fixture.detectChanges();
    tick();
    httpMock
      .expectOne('/api/projects/1/console')
      .flush({ sessionId: '1-console-e5f6a7b8', workingDirectory: '/repo' });
    fixture.detectChanges();

    expect(TestBed.inject(AgentStore).get('1-console-e5f6a7b8')).toBe('codex');
    expect((fixture.nativeElement as HTMLElement).querySelector('app-agent-picker')).toBeFalsy();
  }));

  it('drops ?new once it has been acted on, so a reload does not mint another (#370)', fakeAsync(() => {
    const router = TestBed.inject(Router);
    router.navigateByUrl('/projects/1/console?new=1&focus=1');
    tick();

    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([row('1-console-a1b2c3d4')]);
    fixture.detectChanges();
    tick();
    httpMock
      .expectOne('/api/projects/1/console')
      .flush({ sessionId: '1-console-e5f6a7b8', workingDirectory: '/repo' });
    fixture.detectChanges();
    tick();

    expect(router.url).not.toContain('new=1');
    // Every other param the URL was carrying survives (#286's focused window).
    expect(router.url).toContain('focus=1');
  }));

  it('starts another console when ?new arrives while this page is already showing (#370)', fakeAsync(() => {
    const router = TestBed.inject(Router);
    router.navigateByUrl('/projects/1/console');
    tick();

    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([row('1-console-a1b2c3d4')]);
    fixture.detectChanges();

    // The project id never changes, so nothing but the query param tells the page
    // the sidenav's "+" was clicked again.
    router.navigate(['/projects', 1, 'console'], { queryParams: { new: 1 } });
    tick();
    httpMock
      .expectOne('/api/projects/1/console')
      .flush({ sessionId: '1-console-e5f6a7b8', workingDirectory: '/repo' });
    fixture.detectChanges();
    tick();

    expect(fixture.componentInstance.selected).toBe('1-console-e5f6a7b8');
    expect((fixture.nativeElement as HTMLElement).querySelectorAll('app-terminal').length).toBe(2);
  }));

  it('mints the new console under the newly-clicked project, not the one still rendered (#439)', fakeAsync(() => {
    const router = TestBed.inject(Router);
    router.navigateByUrl('/projects/1/console');
    tick();

    const fixture = init(1);
    httpMock.expectOne('/api/projects/1/console/sessions').flush([row('1-console-a1b2c3d4')]);
    fixture.detectChanges();

    // The click's navigation lands its query param -- and, in the real app, its route
    // projectId -- before Angular has propagated the new projectId to this
    // component's input (#439's race): the input is only updated below, after this
    // navigation and its query-param subscription have already run.
    router.navigate(['/projects', 2, 'console'], { queryParams: { new: 1 } });
    tick();
    httpMock.expectNone('/api/projects/1/console');

    fixture.componentRef.setInput('projectId', 2);
    fixture.detectChanges();
    flushProjects([]);
    httpMock.expectOne('/api/projects/2/console/sessions').flush([]);
    fixture.detectChanges();
    httpMock
      .expectOne('/api/projects/2/console')
      .flush({ sessionId: '2-console-a1b2c3d4', workingDirectory: '/repo-2' });
    fixture.detectChanges();
    tick();

    expect(fixture.componentInstance.selected).toBe('2-console-a1b2c3d4');
    expect(router.url).toContain('/projects/2/console');
    expect(router.url).not.toContain('new=1');
  }));

  it('ignores a further ?new while one console is still being started (#180, #370)', fakeAsync(() => {
    const router = TestBed.inject(Router);
    router.navigateByUrl('/projects/1/console');
    tick();

    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([row('1-console-a1b2c3d4')]);
    fixture.detectChanges();

    router.navigate(['/projects', 1, 'console'], { queryParams: { new: 1 } });
    tick();
    router.navigate(['/projects', 1, 'console'], { queryParams: { new: 1 } });
    tick();

    // Only one mint in flight: the second "+" click was a no-op.
    const requests = httpMock.match('/api/projects/1/console');
    expect(requests.length).toBe(1);
    requests[0].flush({ sessionId: '1-console-e5f6a7b8', workingDirectory: '/repo' });
    fixture.detectChanges();
    tick();
  }));

  it('re-arms after a failed ?new start instead of staying stuck (#180, #370)', fakeAsync(() => {
    const router = TestBed.inject(Router);
    router.navigateByUrl('/projects/1/console');
    tick();

    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([row('1-console-a1b2c3d4')]);
    fixture.detectChanges();

    router.navigate(['/projects', 1, 'console'], { queryParams: { new: 1 } });
    tick();
    httpMock.expectOne('/api/projects/1/console').flush(null, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();
    expect(fixture.componentInstance.startError).toBeTrue();

    router.navigate(['/projects', 1, 'console'], { queryParams: { new: 1 } });
    tick();
    httpMock
      .expectOne('/api/projects/1/console')
      .flush({ sessionId: '1-console-e5f6a7b8', workingDirectory: '/repo' });
    fixture.detectChanges();
    tick();

    expect(fixture.componentInstance.selected).toBe('1-console-e5f6a7b8');
  }));

  // #537: a project created from a template gets its one seeded console opened by
  // this page, without a click, once it is READY -- and never again after the engine
  // has recorded the launch.

  describe('seeded console for a templated project (#537)', () => {
    const TEMPLATED = { id: 1, template: 'springboot-angular', templateSeededAt: null };

    it('waits while the project is CLONING, then opens the seeded console once it turns READY', fakeAsync(() => {
      TestBed.inject(DefaultAgentStore).set('codex');
      const fixture = init(1, [project({ ...TEMPLATED, status: 'CLONING' })]);
      fixture.detectChanges();

      const compiled = fixture.nativeElement as HTMLElement;
      expect(fixture.componentInstance.cloning).toBeTrue();
      expect(compiled.querySelector('.cloning')?.textContent).toContain('creating the project');
      expect(compiled.querySelector('app-terminal')).toBeFalsy();
      // Nothing is asked of the console endpoints while the project is not READY.
      httpMock.expectNone('/api/projects/1/console/sessions');
      httpMock.expectNone('/api/projects/1/console');

      // Still cloning on the next read: keep waiting.
      tick(3000);
      flushProjects([project({ ...TEMPLATED, status: 'CLONING' })]);
      fixture.detectChanges();
      expect(fixture.componentInstance.cloning).toBeTrue();
      httpMock.expectNone('/api/projects/1/console/sessions');

      // READY now: the open-console list is read, then the seeded console is minted.
      tick(3000);
      flushProjects([project(TEMPLATED)]);
      httpMock.expectOne('/api/projects/1/console/sessions').flush([]);
      fixture.detectChanges();
      const start = httpMock.expectOne('/api/projects/1/console');
      expect(start.request.method).toBe('POST');
      start.flush({ sessionId: '1-console-a1b2c3d4', workingDirectory: '/repo-console-a1b2c3d4' });
      fixture.detectChanges();

      expect(fixture.componentInstance.cloning).toBeFalse();
      expect(fixture.componentInstance.selected).toBe('1-console-a1b2c3d4');
      const terminal = fixture.debugElement.query(By.directive(TerminalComponent));
      expect(terminal.componentInstance.cmd).toBe('codex');
      expect(terminal.componentInstance.seed).toBe('template');
      expect(terminal.componentInstance.dir).toBe('/repo-console-a1b2c3d4');
    }));

    it('opens the seeded console on a later visit while the project still owes it, alongside consoles already open', () => {
      const fixture = init(1, [project(TEMPLATED)]);
      httpMock.expectOne('/api/projects/1/console/sessions').flush([row('1-console-11111111')]);
      fixture.detectChanges();

      httpMock
        .expectOne('/api/projects/1/console')
        .flush({ sessionId: '1-console-22222222', workingDirectory: '/repo-console-22222222' });
      fixture.detectChanges();

      expect(fixture.componentInstance.consoles.map((c) => [c.id, c.seed])).toEqual([
        ['1-console-11111111', null],
        ['1-console-22222222', 'template'],
      ]);
      expect(fixture.componentInstance.selected).toBe('1-console-22222222');
    });

    it('opens it at most once per page instance, even if the project is re-read before the engine recorded the launch', () => {
      const fixture = init(1, [project(TEMPLATED)]);
      httpMock.expectOne('/api/projects/1/console/sessions').flush([]);
      fixture.detectChanges();
      httpMock
        .expectOne('/api/projects/1/console')
        .flush({ sessionId: '1-console-a1b2c3d4', workingDirectory: '/repo' });
      fixture.detectChanges();

      // A second load of the same project (e.g. the input re-set) sees the project
      // still unrecorded -- this instance must not mint another seeded console.
      fixture.componentRef.setInput('projectId', 2);
      fixture.detectChanges();
      flushProjects([]);
      httpMock.expectOne('/api/projects/2/console/sessions').flush([row('2-console-a1b2c3d4')]);
      fixture.detectChanges();
      fixture.componentRef.setInput('projectId', 1);
      fixture.detectChanges();
      flushProjects([project(TEMPLATED)]);
      httpMock.expectOne('/api/projects/1/console/sessions').flush([row('1-console-a1b2c3d4')]);
      fixture.detectChanges();

      httpMock.expectNone('/api/projects/1/console');
      expect(fixture.componentInstance.consoles.map((c) => c.seed)).toEqual([null]);
    });

    it('does not open another seeded console once the engine has recorded the launch', () => {
      const fixture = init(1, [project({ ...TEMPLATED, templateSeededAt: '2026-09-01T12:00:00Z' })]);
      httpMock.expectOne('/api/projects/1/console/sessions').flush([row('1-console-a1b2c3d4')]);
      fixture.detectChanges();

      httpMock.expectNone('/api/projects/1/console');
      expect(fixture.componentInstance.consoles.map((c) => c.seed)).toEqual([null]);
      expect(fixture.componentInstance.selected).toBe('1-console-a1b2c3d4');
    });

    it('a READY project without a template is never seeded -- the ordinary auto-start runs instead', () => {
      const fixture = init(1, [project({ id: 1 })]);
      httpMock.expectOne('/api/projects/1/console/sessions').flush([]);
      fixture.detectChanges();

      httpMock.expectOne('/api/projects/1/console').flush({ sessionId: '1-console-a1b2c3d4', workingDirectory: '/repo' });
      fixture.detectChanges();

      const terminal = fixture.debugElement.query(By.directive(TerminalComponent));
      expect(terminal.componentInstance.seed).toBeNull();
    });

    it('does nothing for a FAILED project, and seeds once a retry brings it to READY', fakeAsync(() => {
      const fixture = init(1, [project({ ...TEMPLATED, status: 'FAILED' })]);
      fixture.detectChanges();

      const compiled = fixture.nativeElement as HTMLElement;
      expect(fixture.componentInstance.failed).toBeTrue();
      expect(compiled.querySelector('.failed')?.textContent).toContain('creation failed');
      httpMock.expectNone('/api/projects/1/console/sessions');
      httpMock.expectNone('/api/projects/1/console');
      // A FAILED project is not polled: only CLONING re-reads.
      tick(3000);
      httpMock.expectNone('/api/projects');

      // The operator retried it from the project page; coming back here re-reads.
      fixture.componentRef.setInput('projectId', 2);
      fixture.detectChanges();
      flushProjects([]);
      httpMock.expectOne('/api/projects/2/console/sessions').flush([row('2-console-a1b2c3d4')]);
      fixture.detectChanges();
      fixture.componentRef.setInput('projectId', 1);
      fixture.detectChanges();
      flushProjects([project(TEMPLATED)]);
      httpMock.expectOne('/api/projects/1/console/sessions').flush([]);
      fixture.detectChanges();

      httpMock
        .expectOne('/api/projects/1/console')
        .flush({ sessionId: '1-console-a1b2c3d4', workingDirectory: '/repo' });
      fixture.detectChanges();
      expect(fixture.debugElement.query(By.directive(TerminalComponent)).componentInstance.seed).toBe('template');
    }));

    it('stops the cloning poll when the page is left', fakeAsync(() => {
      const fixture = init(1, [project({ ...TEMPLATED, status: 'CLONING' })]);
      fixture.detectChanges();

      fixture.destroy();
      tick(3000);

      httpMock.expectNone('/api/projects');
      // ngOnDestroy still tells the sidenav the issue list may be stale (#140).
    }));

    it('falls back to the pre-#537 flow when the project read fails', () => {
      const fixture = TestBed.createComponent(ProjectConsoleComponent);
      fixture.componentRef.setInput('projectId', 1);
      fixture.detectChanges();
      httpMock.expectOne('/api/projects').flush({ error: 'boom' }, { status: 500, statusText: 'Error' });
      httpMock.expectOne('/api/projects/1/console/sessions').flush([row('1-console-a1b2c3d4')]);
      fixture.detectChanges();

      expect(fixture.componentInstance.selected).toBe('1-console-a1b2c3d4');
    });
  });

  it('reloads when the project id changes', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([row('1-console-a1b2c3d4')]);
    fixture.detectChanges();

    fixture.componentRef.setInput('projectId', 2);
    fixture.detectChanges();
    flushProjects([]);
    httpMock.expectOne('/api/projects/2/console/sessions').flush([]);
    fixture.detectChanges();

    // The new project has no open console either, so it auto-starts one there too.
    httpMock.expectOne('/api/projects/2/console').flush({ sessionId: '2-console-a1b2c3d4', workingDirectory: '/repo' });
    fixture.detectChanges();

    expect(fixture.componentInstance.selected).toBe('2-console-a1b2c3d4');
  });

  it('reads and lists this project\u2019s past conversations only once the disclosure is opened (#372)', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([row('1-console-a1b2c3d4')]);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    // Landing on a live console costs no extra request -- the list is read on demand.
    httpMock.expectNone('/api/projects/1/console/resume-sessions');
    expect(compiled.querySelector('app-session-list')).toBeFalsy();

    compiled.querySelector<HTMLButtonElement>('.past-toggle')!.click();
    httpMock.expectOne('/api/projects/1/console/resume-sessions').flush([
      {
        worktreeId: '1-console-a1b2c3d4',
        tool: 'claude',
        resumeId: '11111111-1111-1111-1111-111111111111',
        capturedAt: '2026-08-27T09:30:00Z',
        title: null,
      },
    ]);
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

  it('says so plainly when the project has no past conversations (#372)', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([row('1-console-a1b2c3d4')]);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    compiled.querySelector<HTMLButtonElement>('.past-toggle')!.click();
    httpMock.expectOne('/api/projects/1/console/resume-sessions').flush([]);
    fixture.detectChanges();

    expect(compiled.querySelector('app-session-list')).toBeFalsy();
    expect(compiled.querySelector('.past-empty')!.textContent).toContain('no past conversations');
  });

  it('renames a tab in place and saves the name against the session (#393)', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([row('1-console-a1b2c3d4')]);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.tab')!.textContent!.trim()).toBe('console');
    const renamedSpy = spyOn(TestBed.inject(ConsolesService), 'notifyRenamed');

    typeAndCommit(fixture, renameField(fixture), '  release notes  ');

    // Shown immediately, before the server has answered -- and announced just as
    // immediately, so the header consoles widget refetches its rows (#456).
    expect(compiled.querySelector('.tab')!.textContent!.trim()).toBe('release notes');
    expect(renamedSpy).toHaveBeenCalledTimes(1);
    const request = httpMock.expectOne('/api/projects/1/console/1-console-a1b2c3d4/name');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ name: 'release notes' });
    request.flush(null);
  });

  it('puts the cursor straight in the rename field (#393)', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([row('1-console-a1b2c3d4')]);
    fixture.detectChanges();

    const field = renameField(fixture);

    expect(document.activeElement).toBe(field);
    field.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();
  });

  it('shows the name the engine already has for a tab on load (#393)', () => {
    const fixture = init();
    httpMock
      .expectOne('/api/projects/1/console/sessions')
      .flush([row('1-console-a1b2c3d4', '2026-08-27T10:00:00Z', 'release notes')]);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelector('.tab')!.textContent!.trim()).toBe('release notes');
  });

  it('clearing the name falls back to the auto-generated label (#393)', () => {
    const fixture = init();
    httpMock
      .expectOne('/api/projects/1/console/sessions')
      .flush([row('1-console-a1b2c3d4', '2026-08-27T10:00:00Z', 'release notes')]);
    fixture.detectChanges();

    typeAndCommit(fixture, renameField(fixture), '   ');

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.tab')!.textContent!.trim()).toBe('console');
    const request = httpMock.expectOne('/api/projects/1/console/1-console-a1b2c3d4/name');
    expect(request.request.body).toEqual({ name: '' });
    request.flush(null);
  });

  it('puts the previous name back when the rename fails (#393), announcing the revert too (#456)', () => {
    const fixture = init();
    httpMock
      .expectOne('/api/projects/1/console/sessions')
      .flush([row('1-console-a1b2c3d4', '2026-08-27T10:00:00Z', 'release notes')]);
    fixture.detectChanges();
    const renamedSpy = spyOn(TestBed.inject(ConsolesService), 'notifyRenamed');

    typeAndCommit(fixture, renameField(fixture), 'new name');
    httpMock
      .expectOne('/api/projects/1/console/1-console-a1b2c3d4/name')
      .flush({}, { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.tab')!.textContent!.trim()).toBe('release notes');
    expect(compiled.querySelector('.strip-error')!.textContent).toContain('could not rename');
    // Once for the optimistic update, once for the revert -- the widget's row must
    // fall back with the tab (#456).
    expect(renamedSpy).toHaveBeenCalledTimes(2);
  });

  it('abandoning the field with Escape changes nothing (#393)', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([row('1-console-a1b2c3d4')]);
    fixture.detectChanges();

    const field = renameField(fixture);
    field.value = 'never saved';
    field.dispatchEvent(new Event('input'));
    field.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();

    // No request at all -- httpMock.verify() in afterEach is what asserts that.
    expect((fixture.nativeElement as HTMLElement).querySelector('.tab')!.textContent!.trim()).toBe('console');
  });

  it('re-reads the list on every open, so a console closed meanwhile shows up (#372)', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([row('1-console-a1b2c3d4')]);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const toggle = compiled.querySelector<HTMLButtonElement>('.past-toggle')!;
    toggle.click();
    httpMock.expectOne('/api/projects/1/console/resume-sessions').flush([]);
    fixture.detectChanges();

    toggle.click();
    fixture.detectChanges();
    toggle.click();
    httpMock.expectOne('/api/projects/1/console/resume-sessions').flush([]);
    fixture.detectChanges();
  });
});
