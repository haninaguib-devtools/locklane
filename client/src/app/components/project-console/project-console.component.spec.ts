import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { ProjectConsoleComponent } from './project-console.component';
import { AgentStore } from '../../services/agent-store';
import { IssuesService } from '../../services/issues.service';
import { TerminalComponent } from '../terminal/terminal.component';

describe('ProjectConsoleComponent', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    localStorage.removeItem('locklane.sessionAgents');
    TestBed.configureTestingModule({
      imports: [ProjectConsoleComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.removeItem('locklane.sessionAgents');
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

  it('shows neither an Overview tab nor the main/worktree choice in its strip', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([row('1-console-a1b2c3d4')]);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).not.toContain('Overview');
    compiled.querySelector<HTMLButtonElement>('.plus')!.click();
    fixture.detectChanges();
    expect(compiled.querySelector('.picker')).toBeTruthy();
    // The agent picker stays; the "where" (main/worktree) group is what's gone.
    expect(compiled.querySelector('app-agent-picker')).toBeTruthy();
    const labels = Array.from(compiled.querySelectorAll('.picker-label')).map((l) => l.textContent!.trim());
    expect(labels).toEqual(['agent']);
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

  it('labels a tab with the agent this browser launched it with', () => {
    TestBed.inject(AgentStore).set('1-console-a1b2c3d4', 'codex');
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([row('1-console-a1b2c3d4')]);
    fixture.detectChanges();

    const tab = (fixture.nativeElement as HTMLElement).querySelector('.tab')!;
    expect(tab.textContent!.trim()).toBe('console · codex');
  });

  it('shows the agent picker when the project has no open console', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([]);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-agent-picker')).toBeTruthy();
    expect(compiled.querySelector('app-terminal')).toBeFalsy();
  });

  it('starts a session with the chosen agent and then shows the terminal', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([]);
    fixture.detectChanges();

    fixture.componentInstance.agent = 'codex';
    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.start')!.click();
    fixture.detectChanges();

    const req = httpMock.expectOne('/api/projects/1/console');
    expect(req.request.method).toBe('POST');
    req.flush({ sessionId: '1-console-a1b2c3d4', workingDirectory: '/repo' });
    fixture.detectChanges();

    expect(fixture.componentInstance.selected).toBe('1-console-a1b2c3d4');
    const terminal = fixture.debugElement.query(By.directive(TerminalComponent));
    expect(terminal.componentInstance.cmd).toBe('codex');
    expect(TestBed.inject(AgentStore).get('1-console-a1b2c3d4')).toBe('codex');
  });

  it('opens another console from the tab strip\'s "+" and selects it', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([row('1-console-a1b2c3d4')]);
    fixture.detectChanges();

    fixture.componentInstance.openFromTabs({ worktree: false, agent: 'claude' });
    const req = httpMock.expectOne('/api/projects/1/console');
    expect(req.request.method).toBe('POST');
    req.flush({ sessionId: '1-console-e5f6a7b8', workingDirectory: '/repo' });
    fixture.detectChanges();

    expect(fixture.componentInstance.selected).toBe('1-console-e5f6a7b8');
    expect((fixture.nativeElement as HTMLElement).querySelectorAll('app-terminal').length).toBe(2);
  });

  it('closes a console from its tab and falls back to the first remaining one', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([
      row('1-console-a1b2c3d4', '2026-08-27T10:00:00Z'),
      row('1-console-e5f6a7b8', '2026-08-27T11:00:00Z'),
    ]);
    fixture.detectChanges();
    spyOn(window, 'confirm').and.returnValue(true);

    const compiled = fixture.nativeElement as HTMLElement;
    compiled.querySelectorAll<HTMLButtonElement>('.tab-close')[1].click();
    const req = httpMock.expectOne('/api/projects/1/console/1-console-e5f6a7b8');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
    fixture.detectChanges();

    expect(fixture.componentInstance.selected).toBe('1-console-a1b2c3d4');
    expect(compiled.querySelectorAll('app-terminal').length).toBe(1);
  });

  it('shows the starter again once the last console is closed', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([row('1-console-a1b2c3d4')]);
    fixture.detectChanges();
    spyOn(window, 'confirm').and.returnValue(true);

    const compiled = fixture.nativeElement as HTMLElement;
    compiled.querySelector<HTMLButtonElement>('.tab-close')!.click();
    httpMock.expectOne('/api/projects/1/console/1-console-a1b2c3d4').flush(null);
    fixture.detectChanges();

    expect(compiled.querySelector('app-terminal')).toBeFalsy();
    expect(compiled.querySelector('app-agent-picker')).toBeTruthy();
  });

  it('shows an error and keeps the tab when closing fails', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([row('1-console-a1b2c3d4')]);
    fixture.detectChanges();
    spyOn(window, 'confirm').and.returnValue(true);

    const compiled = fixture.nativeElement as HTMLElement;
    compiled.querySelector<HTMLButtonElement>('.tab-close')!.click();
    httpMock
      .expectOne('/api/projects/1/console/1-console-a1b2c3d4')
      .flush(null, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(compiled.textContent).toContain('could not close that console');
    expect(compiled.querySelectorAll('app-terminal').length).toBe(1);
  });

  it('shows an error and lets the user retry when starting fails', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([]);
    fixture.detectChanges();

    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.start')!.click();
    httpMock.expectOne('/api/projects/1/console').flush(null, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(fixture.componentInstance.startError).toBeTrue();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('could not start a console');
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

    expect(fixture.componentInstance.selected).toBeNull();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-agent-picker')).toBeTruthy();
  });
});
