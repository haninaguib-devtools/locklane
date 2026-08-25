import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { ConsoleIndicatorComponent } from './console-indicator.component';
import { ActiveConsoleStore } from '../../services/active-console-store';
import { AgentStore } from '../../services/agent-store';
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
    httpMock.expectOne('/api/consoles').flush(ids);
    httpMock.expectOne('/api/issues').flush(issues);
  }

  it('builds an entry per console, with issue title and label', () => {
    TestBed.inject(AgentStore).set('7-main-a1b2c3d4', 'claude');
    const fixture = TestBed.createComponent(ConsoleIndicatorComponent);
    fixture.detectChanges();

    flushInitialFetch(['7-main-a1b2c3d4', '8-rename-toggle'], [issue(7, 'Seven'), issue(8, 'Eight')]);

    expect(fixture.componentInstance.entries).toEqual([
      { sessionId: '7-main-a1b2c3d4', issueNumber: 7, issueTitle: 'Seven', label: 'main · claude' },
      { sessionId: '8-rename-toggle', issueNumber: 8, issueTitle: 'Eight', label: 'wtree' },
    ]);
  });

  it('falls back to "#N" when the issue title is not known', () => {
    const fixture = TestBed.createComponent(ConsoleIndicatorComponent);
    fixture.detectChanges();

    flushInitialFetch(['9-slug'], []);

    expect(fixture.componentInstance.entries[0].issueTitle).toBe('#9');
  });

  it('excludes a console id with no issue-number prefix', () => {
    const fixture = TestBed.createComponent(ConsoleIndicatorComponent);
    fixture.detectChanges();

    flushInitialFetch(['main', '7-rename-toggle'], [issue(7, 'Seven')]);

    expect(fixture.componentInstance.entries.map((e) => e.sessionId)).toEqual(['7-rename-toggle']);
  });

  it('refetches when the picker is opened', () => {
    const fixture = TestBed.createComponent(ConsoleIndicatorComponent);
    fixture.detectChanges();
    flushInitialFetch([], []);

    fixture.componentInstance.toggle();

    expect(fixture.componentInstance.open).toBeTrue();
    flushInitialFetch(['7-rename-toggle'], [issue(7, 'Seven')]);
    expect(fixture.componentInstance.entries.length).toBe(1);
  });

  it("jumping to an entry remembers it as the issue's active console, closes the picker, and navigates there", () => {
    const fixture = TestBed.createComponent(ConsoleIndicatorComponent);
    fixture.detectChanges();
    flushInitialFetch(['7-rename-toggle'], [issue(7, 'Seven')]);
    fixture.componentInstance.toggle();
    flushInitialFetch(['7-rename-toggle'], [issue(7, 'Seven')]);
    expect(fixture.componentInstance.open).toBeTrue();
    const router = TestBed.inject(Router);
    const navigateSpy = spyOn(router, 'navigate');

    fixture.componentInstance.jumpTo(fixture.componentInstance.entries[0]);

    expect(TestBed.inject(ActiveConsoleStore).get(7)).toBe('7-rename-toggle');
    expect(fixture.componentInstance.open).toBeFalse();
    expect(navigateSpy).toHaveBeenCalledWith(['/issues', 7]);
  });
});
