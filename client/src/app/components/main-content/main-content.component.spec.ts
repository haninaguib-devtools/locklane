import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { MainContentComponent } from './main-content.component';
import { GhIssue, IssueDetail } from '../../models/issue.model';

describe('MainContentComponent', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [MainContentComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function respond(number: number) {
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
    httpMock.expectOne(`/api/issues/${number}/worktrees`).flush(['main']);
  }

  it('loads the issue, its detail, and its worktrees when issueNumber is set', () => {
    const fixture = TestBed.createComponent(MainContentComponent);
    fixture.componentInstance.issueNumber = 7;
    fixture.componentInstance.ngOnChanges({
      issueNumber: { currentValue: 7, previousValue: null, firstChange: true, isFirstChange: () => true },
    });

    respond(7);

    expect(fixture.componentInstance.issue?.number).toBe(7);
    expect(fixture.componentInstance.detail?.number).toBe(7);
    expect(fixture.componentInstance.worktreeIds).toEqual(['main']);
  });

  it('defaults the selected worktree to the first one returned', () => {
    const fixture = TestBed.createComponent(MainContentComponent);
    fixture.componentInstance.issueNumber = 7;
    fixture.componentInstance.ngOnChanges({
      issueNumber: { currentValue: 7, previousValue: null, firstChange: true, isFirstChange: () => true },
    });

    respond(7);

    expect(fixture.componentInstance.selectedWorktree).toBe('main');
  });

  it('has no selected worktree when the issue has none yet', () => {
    const fixture = TestBed.createComponent(MainContentComponent);
    fixture.componentInstance.issueNumber = 8;
    fixture.componentInstance.ngOnChanges({
      issueNumber: { currentValue: 8, previousValue: null, firstChange: true, isFirstChange: () => true },
    });

    const issue: GhIssue = { number: 8, title: 'T', state: 'OPEN', labels: [], body: '', createdAt: '', updatedAt: '' };
    const detail: IssueDetail = {
      number: 8,
      recordPath: null,
      checks: { passing: 0, failing: 0, pending: 0 },
      branch: null,
      prNumber: null,
      prState: null,
      prDraft: false,
      flowSteps: [],
    };
    httpMock.expectOne('/api/issues/8').flush(issue);
    httpMock.expectOne('/api/issues/8/detail').flush(detail);
    httpMock.expectOne('/api/issues/8/worktrees').flush([]);

    expect(fixture.componentInstance.selectedWorktree).toBeNull();
  });

  it('switching worktree tabs updates the selection without reloading the issue', () => {
    const fixture = TestBed.createComponent(MainContentComponent);
    fixture.componentInstance.issueNumber = 7;
    fixture.componentInstance.ngOnChanges({
      issueNumber: { currentValue: 7, previousValue: null, firstChange: true, isFirstChange: () => true },
    });
    respond(7);

    fixture.componentInstance.selectWorktree('other-worktree');

    expect(fixture.componentInstance.selectedWorktree).toBe('other-worktree');
    expect(fixture.componentInstance.issue?.number).toBe(7); // unchanged, no reload
  });
});
