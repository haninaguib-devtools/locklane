import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { By } from '@angular/platform-browser';
import { ShellsWindowComponent } from './shells-window.component';
import { TerminalComponent } from '../terminal/terminal.component';
import { ConfirmDialogComponent } from '../confirm-dialog/confirm-dialog.component';
import { EventsService } from '../../services/events.service';
import { OpenShell } from '../../services/shells.service';
import { routes } from '../../app.routes';

describe('ShellsWindowComponent', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ShellsWindowComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter(routes)],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function shell(overrides: Partial<OpenShell>): OpenShell {
    return {
      sessionId: '1-shell-main-a1b2c3d4',
      projectId: 1,
      issueNumber: null,
      mainCheckout: true,
      workingDirectory: '/repo',
      createdAt: '2026-08-31T10:00:00Z',
      lastAttachedAt: '2026-08-31T10:05:00Z',
      displayName: null,
      ...overrides,
    };
  }

  function init(shells: OpenShell[]): ReturnType<typeof TestBed.createComponent<ShellsWindowComponent>> {
    const fixture = TestBed.createComponent(ShellsWindowComponent);
    fixture.detectChanges();
    httpMock
      .expectOne('/api/projects')
      .flush([
        { id: 1, name: 'alpha', gitUrl: 'u', workareaPath: '/w', defaultBranch: 'main', status: 'READY', createdAt: '', accentColor: null },
        { id: 2, name: 'beta', gitUrl: 'u', workareaPath: '/w2', defaultBranch: 'main', status: 'READY', createdAt: '', accentColor: null },
      ]);
    httpMock.expectOne('/api/shells').flush(shells);
    fixture.detectChanges();
    return fixture;
  }

  /** Reaches past EventsService's public API — there is no other way to fake an incoming socket message. */
  function emitAppEvent(event: unknown): void {
    (TestBed.inject(EventsService) as unknown as { eventsSubject: { next: (e: unknown) => void } }).eventsSubject.next(
      event,
    );
  }

  it('renders shells grouped by project and labeled by issue or Main', () => {
    const fixture = init([
      shell({ sessionId: '1-shell-main-aaaa0001' }),
      shell({ sessionId: '1-shell-main-aaaa0002' }),
      shell({ sessionId: '1-shell-438-aaaa0003', issueNumber: 438, mainCheckout: false }),
      shell({ sessionId: '1-shell-438-aaaa0004', issueNumber: 438, mainCheckout: false }),
      shell({ sessionId: '2-shell-7-aaaa0005', projectId: 2, issueNumber: 7, mainCheckout: false }),
    ]);

    const groups = fixture.nativeElement.querySelectorAll('.group-name');
    expect(Array.from(groups, (el: Element) => el.textContent!.trim())).toEqual(['alpha', 'beta']);
    const labels = fixture.nativeElement.querySelectorAll('.row-label');
    expect(Array.from(labels, (el: Element) => el.textContent!.trim())).toEqual([
      'Main',
      'Main 2',
      '#438',
      '#438 · wtree 2',
      '#7',
    ]);
  });

  it('shows a user-given display name in place of the auto label', () => {
    const fixture = init([shell({ displayName: 'tail the logs' })]);

    const labels = fixture.nativeElement.querySelectorAll('.row-label');
    expect(labels[0].textContent!.trim()).toBe('tail the logs');
  });

  it('selecting a row navigates to /shells/:id and shows that shell terminal', async () => {
    const fixture = init([
      shell({ sessionId: '1-shell-main-aaaa0001' }),
      shell({ sessionId: '1-shell-438-aaaa0002', issueNumber: 438, mainCheckout: false }),
    ]);

    const rows = fixture.nativeElement.querySelectorAll('.row-label');
    (rows[1] as HTMLButtonElement).click();
    fixture.detectChanges();
    await fixture.whenStable();

    expect(TestBed.inject(Router).url).toBe('/shells/1-shell-438-aaaa0002');
    const terminals = fixture.debugElement.queryAll(By.directive(TerminalComponent));
    const selected = terminals.find((t) => t.componentInstance.sessionId === '1-shell-438-aaaa0002')!;
    const other = terminals.find((t) => t.componentInstance.sessionId === '1-shell-main-aaaa0001')!;
    expect(selected.nativeElement.classList.contains('tab-hidden')).toBeFalse();
    expect(selected.componentInstance.active).toBeTrue();
    expect(other.nativeElement.classList.contains('tab-hidden')).toBeTrue();
  });

  it('a consolesChanged event adds a new row without a manual reload', () => {
    const fixture = init([shell({ sessionId: '1-shell-main-aaaa0001' })]);
    expect(fixture.nativeElement.querySelectorAll('.row-label').length).toBe(1);

    emitAppEvent({ type: 'consolesChanged', projectId: 1 });
    httpMock
      .expectOne('/api/shells')
      .flush([
        shell({ sessionId: '1-shell-main-aaaa0001' }),
        shell({ sessionId: '1-shell-438-bbbb0001', issueNumber: 438, mainCheckout: false }),
      ]);
    fixture.detectChanges();

    const labels = fixture.nativeElement.querySelectorAll('.row-label');
    expect(Array.from(labels, (el: Element) => el.textContent!.trim())).toEqual(['Main', '#438']);
  });

  it('a project section + mints a shell at the project base path and selects it (#448)', async () => {
    const fixture = init([shell({ sessionId: '1-shell-main-aaaa0001' })]);

    (fixture.nativeElement.querySelector('.group-add') as HTMLButtonElement).click();
    const post = httpMock.expectOne('/api/projects/1/shells');
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({ issueNumber: null, workingDirectory: '/w' });
    post.flush({ sessionId: '1-shell-main-cccc0001', workingDirectory: '/w' });
    httpMock
      .expectOne('/api/shells')
      .flush([shell({ sessionId: '1-shell-main-aaaa0001' }), shell({ sessionId: '1-shell-main-cccc0001' })]);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(fixture.componentInstance.selected).toBe('1-shell-main-cccc0001');
    expect(TestBed.inject(Router).url).toBe('/shells/1-shell-main-cccc0001');
    // No dedupe: another click mints another shell.
    (fixture.nativeElement.querySelectorAll('.group-add')[0] as HTMLButtonElement).click();
    const secondPost = httpMock.expectOne('/api/projects/1/shells');
    secondPost.flush({ sessionId: '1-shell-main-cccc0002', workingDirectory: '/w' });
    httpMock.expectOne('/api/shells').flush([]);
  });

  it('a group without a known project row shows no + (#448)', () => {
    const fixture = init([shell({ sessionId: '9-shell-main-aaaa0009', projectId: 9 })]);

    expect(fixture.nativeElement.querySelector('.group-add')).toBeNull();
  });

  it('closing a shell asks for confirmation, deletes it, and keeps the window open', () => {
    const fixture = init([shell({ sessionId: '1-shell-main-aaaa0001' })]);

    (fixture.nativeElement.querySelector('.row-close') as HTMLButtonElement).click();
    fixture.detectChanges();
    const dialog = fixture.debugElement.query(By.directive(ConfirmDialogComponent));
    expect(dialog).not.toBeNull();

    dialog.componentInstance.confirmed.emit();
    const del = httpMock.expectOne('/api/projects/1/shells/1-shell-main-aaaa0001');
    expect(del.request.method).toBe('DELETE');
    del.flush(null);
    httpMock.expectOne('/api/shells').flush([]);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.empty')).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain('No open shells');
  });
});
