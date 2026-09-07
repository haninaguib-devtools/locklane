import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { WorktreeListComponent } from './worktree-list.component';
import { ProjectWorktree } from '../../services/worktrees.service';
import { OpenShell } from '../../services/shells.service';
import { AppEvent, EventsService } from '../../services/events.service';

describe('WorktreeListComponent', () => {
  let httpMock: HttpTestingController;

  function row(overrides: Partial<ProjectWorktree> = {}): ProjectWorktree {
    return {
      worktreeId: '1-42-add-widget',
      issueNumber: 42,
      workingDirectory: '/work/1-42-add-widget',
      clean: true,
      sessionAttached: false,
      ...overrides,
    };
  }

  function shell(overrides: Partial<OpenShell> = {}): OpenShell {
    return {
      sessionId: '1-shell-42-aaaa0001',
      projectId: 1,
      issueNumber: 42,
      mainCheckout: false,
      workingDirectory: '/work/1-42-add-widget',
      createdAt: '2026-01-01T00:00:00Z',
      lastAttachedAt: '2026-01-01T00:00:00Z',
      displayName: null,
      ...overrides,
    };
  }

  /** Reaches past EventsService's public API (#129) -- there is no other way to fake an incoming socket message. */
  function emitAppEvent(event: unknown): void {
    (TestBed.inject(EventsService) as unknown as { eventsSubject: { next: (e: unknown) => void } }).eventsSubject.next(
      event,
    );
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [WorktreeListComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function init(
    rows: ProjectWorktree[],
    shells: OpenShell[] = [],
    projectId = 1,
  ): ReturnType<typeof TestBed.createComponent<WorktreeListComponent>> {
    const fixture = TestBed.createComponent(WorktreeListComponent);
    fixture.componentRef.setInput('projectId', projectId);
    fixture.detectChanges();
    httpMock.expectOne(`/api/projects/${projectId}/worktrees`).flush(rows);
    httpMock.expectOne('/api/shells').flush(shells);
    fixture.detectChanges();
    return fixture;
  }

  it('shows a row per worktree with its issue, path, and status', () => {
    const fixture = init([row(), row({ worktreeId: '1-7-dirty', issueNumber: 7, clean: false, sessionAttached: true })]);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';

    expect(text).toContain('#42');
    expect(text).toContain('/work/1-42-add-widget');
    expect(text).toContain('clean');
    expect(text).toContain('#7');
    expect(text).toContain('dirty');
    expect(text).toContain('attached');
  });

  it('shows "agent" instead of an issue number for a project-console worktree (#339)', () => {
    const fixture = init([row({ worktreeId: '1-console-abcd1234', issueNumber: null, workingDirectory: '/work/1-console-abcd1234' })]);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';

    expect(text).toContain('agent');
    expect(text).not.toContain('#null');
    expect(text).toContain('/work/1-console-abcd1234');
  });

  it('shows the guard refusal verbatim when the server refuses to remove a project-console worktree', () => {
    const fixture = init([row({ worktreeId: '1-console-abcd1234', issueNumber: null })]);

    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.remove-button')!.click();
    httpMock
      .expectOne('/api/projects/1/worktrees/1-console-abcd1234')
      .flush(
        { error: 'a branch is checked out in this worktree — it has outgrown scratch use, so it is left alone' },
        { status: 409, statusText: 'Conflict' },
      );
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'a branch is checked out in this worktree — it has outgrown scratch use, so it is left alone',
    );
    expect(fixture.componentInstance.rows.length).toBe(1);
  });

  it('shows a placeholder state when the project has no worktrees', () => {
    const fixture = init([]);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('no worktrees for this project');
  });

  it('removes a row on a successful DELETE', () => {
    const fixture = init([row()]);

    const removeButton = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.remove-button')!;
    removeButton.click();
    fixture.detectChanges();

    httpMock.expectOne('/api/projects/1/worktrees/1-42-add-widget').flush(null);
    fixture.detectChanges();

    expect(fixture.componentInstance.rows).toEqual([]);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('no worktrees for this project');
  });

  it('shows the guard refusal verbatim when the server rejects a remove', () => {
    const fixture = init([row()]);

    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.remove-button')!.click();
    httpMock
      .expectOne('/api/projects/1/worktrees/1-42-add-widget')
      .flush({ error: 'issue #42 is still open — close it before removing its worktree' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'issue #42 is still open — close it before removing its worktree',
    );
    expect(fixture.componentInstance.rows.length).toBe(1);
  });

  it('runs cleanup and reports what it removed, then reloads the list', () => {
    const fixture = init([row()]);

    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.cleanup-button')!.click();
    fixture.detectChanges();

    httpMock.expectOne('/api/projects/1/worktrees/cleanup').flush({ removed: ['1-42-add-widget'] });
    fixture.detectChanges();
    httpMock.expectOne('/api/projects/1/worktrees').flush([]);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('removed 1 worktree');
    expect(fixture.componentInstance.rows).toEqual([]);
  });

  it('reports when cleanup finds nothing to remove', () => {
    const fixture = init([]);

    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.cleanup-button')!.click();
    httpMock.expectOne('/api/projects/1/worktrees/cleanup').flush({ removed: [] });
    fixture.detectChanges();
    httpMock.expectOne('/api/projects/1/worktrees').flush([]);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('nothing to clean up');
  });

  it('shows an error when cleanup fails', () => {
    const fixture = init([]);

    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.cleanup-button')!.click();
    httpMock.expectOne('/api/projects/1/worktrees/cleanup').flush(null, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('could not run cleanup');
  });

  it('shows a row per open shell for this project, filtering out other projects (#733)', () => {
    const fixture = init(
      [],
      [
        shell({ sessionId: '1-shell-42-aaaa0001', issueNumber: 42, workingDirectory: '/work/1-42-add-widget' }),
        shell({ sessionId: '1-shell-main-bbbb0001', mainCheckout: true, issueNumber: null, workingDirectory: '/work/main' }),
        shell({ sessionId: '2-shell-9-cccc0001', projectId: 2, issueNumber: 9 }),
      ],
    );
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';

    expect(text).toContain('#42');
    expect(text).toContain('/work/1-42-add-widget');
    expect(text).toContain('main checkout');
    expect(text).toContain('/work/main');
    expect(text).not.toContain('#9');
    expect(fixture.componentInstance.shells.length).toBe(2);
  });

  it("shows a shell's own name instead of its location when it has one (#393)", () => {
    const fixture = init([], [shell({ displayName: 'debugging the flaky test' })]);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('debugging the flaky test');
  });

  it('shows a placeholder state when the project has no open shells', () => {
    const fixture = init([]);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('no open shells for this project');
  });

  it('opens a shell in the singleton Shells window on click', () => {
    const fixture = init([], [shell()]);
    const openSpy = spyOn(window, 'open');

    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.shell-link')!.click();

    expect(openSpy).toHaveBeenCalledWith('/shells/1-shell-42-aaaa0001', 'locklane-shells');
  });

  it('closes a shell on a successful DELETE and removes its row', () => {
    const fixture = init([], [shell()]);

    const closeButtons = (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('.remove-button');
    closeButtons[closeButtons.length - 1].click();
    fixture.detectChanges();

    httpMock.expectOne('/api/projects/1/shells/1-shell-42-aaaa0001').flush(null);
    fixture.detectChanges();

    expect(fixture.componentInstance.shells).toEqual([]);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('no open shells for this project');
  });

  it('shows the refusal verbatim when closing a shell fails', () => {
    const fixture = init([], [shell()]);

    const closeButtons = (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('.remove-button');
    closeButtons[closeButtons.length - 1].click();
    httpMock
      .expectOne('/api/projects/1/shells/1-shell-42-aaaa0001')
      .flush({ error: 'could not close this shell' }, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('could not close this shell');
    expect(fixture.componentInstance.shells.length).toBe(1);
  });

  it('reloads the shell list when a consolesChanged event arrives remotely (#195)', () => {
    const fixture = init([], []);

    emitAppEvent({ type: 'consolesChanged', projectId: 1 } satisfies AppEvent);
    fixture.detectChanges();

    // ConsolesService folds a remote consolesChanged into both onOpened and onClosed
    // (consoles.service.spec.ts), and this component reloads on either -- one remote
    // event is therefore two identical reload requests here.
    for (const req of httpMock.match('/api/shells')) {
      req.flush([shell()]);
    }
    fixture.detectChanges();

    expect(fixture.componentInstance.shells.length).toBe(1);
  });
});
