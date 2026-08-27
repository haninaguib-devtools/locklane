import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { ProjectConsoleComponent } from './project-console.component';
import { IssuesService } from '../../services/issues.service';
import { TerminalComponent } from '../terminal/terminal.component';

describe('ProjectConsoleComponent', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ProjectConsoleComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function init(projectId = 1): ReturnType<typeof TestBed.createComponent<ProjectConsoleComponent>> {
    const fixture = TestBed.createComponent(ProjectConsoleComponent);
    fixture.componentRef.setInput('projectId', projectId);
    fixture.detectChanges();
    return fixture;
  }

  it('attaches straight to an existing session, skipping the agent picker', () => {
    const fixture = init();
    httpMock
      .expectOne('/api/projects/1/console')
      .flush({ sessionId: '1-console', workingDirectory: '/repo' });
    fixture.detectChanges();

    expect(fixture.componentInstance.sessionId).toBe('1-console');
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-terminal')).toBeTruthy();
    expect(compiled.querySelector('app-agent-picker')).toBeFalsy();
  });

  it('shows the agent picker when no session has been attached to yet', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console').flush(null, { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-agent-picker')).toBeTruthy();
    expect(compiled.querySelector('app-terminal')).toBeFalsy();
  });

  it('starts a session with the chosen agent and then shows the terminal', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console').flush(null, { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();

    fixture.componentInstance.agent = 'codex';
    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.start')!.click();
    fixture.detectChanges();

    const req = httpMock.expectOne('/api/projects/1/console');
    expect(req.request.method).toBe('POST');
    req.flush({ sessionId: '1-console', workingDirectory: '/repo' });
    fixture.detectChanges();

    expect(fixture.componentInstance.sessionId).toBe('1-console');
    const terminal = fixture.debugElement.query(By.directive(TerminalComponent));
    expect(terminal.componentInstance.cmd).toBe('codex');
  });

  it('shows an error and lets the user retry when starting fails', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console').flush(null, { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();

    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.start')!.click();
    httpMock.expectOne('/api/projects/1/console').flush(null, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(fixture.componentInstance.startError).toBeTrue();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('could not start a console');
  });

  it('navigates back to the project\'s issues page', () => {
    const fixture = init();
    httpMock
      .expectOne('/api/projects/1/console')
      .flush({ sessionId: '1-console', workingDirectory: '/repo' });
    fixture.detectChanges();
    const router = TestBed.inject(Router);
    spyOn(router, 'navigate');

    fixture.componentInstance.back();

    expect(router.navigate).toHaveBeenCalledWith(['/projects', 1, 'issues']);
  });

  it('notifies the issue list to bust its cache when the console is left', fakeAsync(() => {
    const fixture = init();
    httpMock
      .expectOne('/api/projects/1/console')
      .flush({ sessionId: '1-console', workingDirectory: '/repo' });
    fixture.detectChanges();

    let notified: number | undefined;
    TestBed.inject(IssuesService).onProjectStale.subscribe((projectId) => (notified = projectId));

    fixture.destroy();
    tick();

    expect(notified).toBe(1);
  }));

  it('reloads when the project id changes', () => {
    const fixture = init();
    httpMock
      .expectOne('/api/projects/1/console')
      .flush({ sessionId: '1-console', workingDirectory: '/repo' });
    fixture.detectChanges();

    fixture.componentRef.setInput('projectId', 2);
    fixture.detectChanges();
    httpMock.expectOne('/api/projects/2/console').flush(null, { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();

    expect(fixture.componentInstance.sessionId).toBeNull();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-agent-picker')).toBeTruthy();
  });
});
