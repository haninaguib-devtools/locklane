import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { WorktreeListComponent } from './worktree-list.component';
import { ProjectWorktree } from '../../services/worktrees.service';

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

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [WorktreeListComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function init(rows: ProjectWorktree[], projectId = 1): ReturnType<typeof TestBed.createComponent<WorktreeListComponent>> {
    const fixture = TestBed.createComponent(WorktreeListComponent);
    fixture.componentRef.setInput('projectId', projectId);
    fixture.detectChanges();
    httpMock.expectOne(`/api/projects/${projectId}/worktrees`).flush(rows);
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
});
