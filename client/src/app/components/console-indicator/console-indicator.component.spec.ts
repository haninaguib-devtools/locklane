import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { ConsoleIndicatorComponent } from './console-indicator.component';
import { ActiveConsoleStore } from '../../services/active-console-store';
import { AgentStore } from '../../services/agent-store';
import { ConsolesService } from '../../services/consoles.service';
import { GhIssue } from '../../models/issue.model';

describe('ConsoleIndicatorComponent', () => {
  let httpMock: HttpTestingController;

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

  function flushInitialFetch(ids: string[], issues: GhIssue[]): void {
    httpMock.expectOne('/api/projects/1/consoles').flush(ids);
    httpMock.expectOne('/api/projects/1/issues').flush(issues);
  }

  /** Sets the required projectId input and runs the ngOnChanges that feeds the reactive fetch. */
  function init(): ReturnType<typeof TestBed.createComponent<ConsoleIndicatorComponent>> {
    const fixture = TestBed.createComponent(ConsoleIndicatorComponent);
    fixture.componentInstance.projectId = 1;
    fixture.componentInstance.ngOnChanges();
    return fixture;
  }

  it('builds an entry per console, with issue title and label', () => {
    TestBed.inject(AgentStore).set('1-7-main-a1b2c3d4', 'claude');
    const fixture = init();

    flushInitialFetch(['1-7-main-a1b2c3d4', '1-8-rename-toggle'], [issue(7, 'Seven'), issue(8, 'Eight')]);

    expect(fixture.componentInstance.entries()).toEqual([
      { sessionId: '1-7-main-a1b2c3d4', issueNumber: 7, issueTitle: 'Seven', label: 'main · claude' },
      { sessionId: '1-8-rename-toggle', issueNumber: 8, issueTitle: 'Eight', label: 'wtree' },
    ]);
  });

  it('falls back to "#N" when the issue title is not known', () => {
    const fixture = init();

    flushInitialFetch(['1-9-slug'], []);

    expect(fixture.componentInstance.entries()[0].issueTitle).toBe('#9');
  });

  it('excludes a console id with no project/issue-number prefix', () => {
    const fixture = init();

    flushInitialFetch(['main', '1-7-rename-toggle'], [issue(7, 'Seven')]);

    expect(fixture.componentInstance.entries().map((e) => e.sessionId)).toEqual(['1-7-rename-toggle']);
  });

  it('updates the count as soon as a console opens elsewhere, without needing a close first', () => {
    const fixture = init();
    flushInitialFetch([], []);
    expect(fixture.componentInstance.entries().length).toBe(0);

    TestBed.inject(ConsolesService).notifyOpened();

    flushInitialFetch(['1-7-rename-toggle'], [issue(7, 'Seven')]);
    expect(fixture.componentInstance.entries().length).toBe(1);
  });

  it('refreshes the count when a console is closed elsewhere (#75)', () => {
    const fixture = init();
    flushInitialFetch(['1-7-rename-toggle'], [issue(7, 'Seven')]);
    expect(fixture.componentInstance.entries().length).toBe(1);

    TestBed.inject(ConsolesService).notifyClosed();

    flushInitialFetch([], []);
    expect(fixture.componentInstance.entries().length).toBe(0);
  });

  it("jumping to an entry remembers it as the issue's active console, closes the picker, and navigates there", () => {
    const fixture = init();
    flushInitialFetch(['1-7-rename-toggle'], [issue(7, 'Seven')]);
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
    const fixture = init();
    flushInitialFetch(['1-7-rename-toggle'], [issue(7, 'Seven')]);
    fixture.componentInstance.toggle();
    expect(fixture.componentInstance.open()).toBeTrue();

    TestBed.inject(ConsolesService).notifyClosed();
    flushInitialFetch([], []);
    fixture.detectChanges();

    expect(fixture.componentInstance.entries().length).toBe(0);
    expect(fixture.componentInstance.open()).toBeFalse();
  });

  it('navigates the selection with arrow keys and jumps on enter', () => {
    const fixture = init();
    flushInitialFetch(
      ['1-7-rename-toggle', '1-8-rename-toggle'],
      [issue(7, 'Seven'), issue(8, 'Eight')],
    );
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
    const fixture = init();
    flushInitialFetch(['1-7-rename-toggle'], [issue(7, 'Seven')]);
    fixture.componentInstance.toggle();
    expect(fixture.componentInstance.open()).toBeTrue();

    fixture.componentInstance.onKey({ key: 'Escape', preventDefault: () => {} } as KeyboardEvent);

    expect(fixture.componentInstance.open()).toBeFalse();
  });
});
