import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ConsoleTabsComponent } from './console-tabs.component';
import { ConsoleTab } from './console-labels';

describe('ConsoleTabsComponent', () => {
  it('emits the clicked console id', () => {
    const c = new ConsoleTabsComponent();
    c.tabs = [
      { id: '7-main-a1b2c3d4', agent: 'shell', label: 'main · shell' },
      { id: '7-rename-toggle', agent: 'claude', label: 'wtree · claude' },
    ];
    let emitted: string | undefined;
    c.selectedChange.subscribe((id) => (emitted = id));

    c.select('7-rename-toggle');

    expect(emitted).toBe('7-rename-toggle');
  });

  it('the "+" starts a console with the default agent directly (#341: no location left to choose)', () => {
    const c = new ConsoleTabsComponent();
    c.defaultAgent = 'codex';
    let emitted: { agent: string } | undefined;
    c.open.subscribe((request) => (emitted = request));

    c.plusClicked();

    expect(emitted).toEqual({ agent: 'codex' });
  });

  it('shows the open button when there are no open tabs, even with hideOpenWhenActive set (#318)', () => {
    const c = new ConsoleTabsComponent();
    c.hideOpenWhenActive = true;
    c.tabs = [];

    expect(c.showOpenButton).toBeTrue();
  });

  it('hides the open button once a tab is open, when hideOpenWhenActive is set (#318)', () => {
    const c = new ConsoleTabsComponent();
    c.hideOpenWhenActive = true;
    c.tabs = [{ id: '7-rename-toggle', agent: 'claude', label: 'wtree · claude' }];

    expect(c.showOpenButton).toBeFalse();
  });

  it('keeps showing the open button with tabs open when hideOpenWhenActive is not set', () => {
    const c = new ConsoleTabsComponent();
    c.tabs = [{ id: '7-rename-toggle', agent: 'claude', label: 'wtree · claude' }];

    expect(c.showOpenButton).toBeTrue();
  });

  it('closeTab stops propagation, closes the menu, and awaits confirmation before emitting', () => {
    const c = new ConsoleTabsComponent();
    c.openMenuId = '7-rename-toggle';
    let emitted: string | undefined;
    c.close.subscribe((id) => (emitted = id));
    const event = new Event('click');
    const stopSpy = spyOn(event, 'stopPropagation');

    c.closeTab('7-rename-toggle', event);

    expect(stopSpy).toHaveBeenCalled();
    expect(c.openMenuId).toBeNull();
    expect(c.pendingCloseId).toBe('7-rename-toggle');
    expect(emitted).toBeUndefined();
  });

  it('emits close when the pending close is confirmed', () => {
    const c = new ConsoleTabsComponent();
    let emitted: string | undefined;
    c.close.subscribe((id) => (emitted = id));
    c.closeTab('7-rename-toggle', new Event('click'));

    c.confirmClose();

    expect(emitted).toBe('7-rename-toggle');
    expect(c.pendingCloseId).toBeNull();
  });

  it('emits nothing when the pending close is cancelled', () => {
    const c = new ConsoleTabsComponent();
    let emitted: string | undefined;
    c.close.subscribe((id) => (emitted = id));
    c.closeTab('7-rename-toggle', new Event('click'));

    c.cancelClose();

    expect(emitted).toBeUndefined();
    expect(c.pendingCloseId).toBeNull();
  });

  it('revealTab stops propagation, closes the menu, and emits the console id immediately, with no confirmation (#441, #480)', () => {
    const c = new ConsoleTabsComponent();
    c.openMenuId = '7-rename-toggle';
    let emitted: string | undefined;
    c.reveal.subscribe((id) => (emitted = id));
    const event = new Event('click');
    const stopSpy = spyOn(event, 'stopPropagation');

    c.revealTab('7-rename-toggle', event);

    expect(stopSpy).toHaveBeenCalled();
    expect(c.openMenuId).toBeNull();
    expect(emitted).toBe('7-rename-toggle');
  });

  it('offers the Folder menu item only when reached at localhost (#497)', () => {
    const c = new ConsoleTabsComponent();

    // Karma itself serves specs from localhost, so the default reads true.
    expect(c.isLocalHost).toBeTrue();

    spyOn<any>(c, 'currentHostname').and.returnValue('example.com');
    expect(c.isLocalHost).toBeFalse();
  });

  it('opens one tab menu at a time, closes on an outside click (#480)', () => {
    const c = new ConsoleTabsComponent();

    c.toggleMenu('7-rename-toggle', new Event('click'));
    expect(c.isMenuOpen('7-rename-toggle')).toBeTrue();

    c.toggleMenu('7-other-tab', new Event('click'));
    expect(c.isMenuOpen('7-rename-toggle')).toBeFalse();
    expect(c.isMenuOpen('7-other-tab')).toBeTrue();

    c.toggleMenu('7-other-tab', new Event('click'));
    expect(c.isMenuOpen('7-other-tab')).toBeFalse();

    c.openMenuId = '7-rename-toggle';
    c.closeMenu();
    expect(c.openMenuId).toBeNull();
  });

  it('renders the overflow trigger and, once opened, a Folder menu item on a live console tab but not on the pinned Overview tab (#441, #480)', () => {
    // The http providers exist for the open-a-shell control's services (#447),
    // constructed with the component; this test itself never talks HTTP.
    TestBed.configureTestingModule({
      imports: [ConsoleTabsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(ConsoleTabsComponent);
    fixture.componentInstance.tabs = [{ id: '7-rename-toggle', agent: 'claude', label: 'wtree · claude' }];
    fixture.detectChanges();

    const tabWraps = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.tab-wrap'));
    expect(tabWraps.length).toBe(2); // the pinned Overview tab, plus the one console tab
    expect(tabWraps[0].querySelector('.tab-menu-trigger')).toBeNull();
    expect(tabWraps[1].querySelector('.tab-menu-trigger')).not.toBeNull();
    expect(tabWraps[1].querySelector('.tab-reveal')).toBeNull(); // menu closed

    (tabWraps[1].querySelector('.tab-menu-trigger') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(tabWraps[1].querySelector('.tab-reveal')).not.toBeNull();
  });

  it('shows a quick close button on every live console tab, without opening the overflow menu (#497)', () => {
    TestBed.configureTestingModule({
      imports: [ConsoleTabsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(ConsoleTabsComponent);
    fixture.componentInstance.tabs = [{ id: '7-rename-toggle', agent: 'claude', label: 'wtree · claude' }];
    fixture.detectChanges();

    const tabWraps = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.tab-wrap'));
    expect(tabWraps[0].querySelector('.tab-close-quick')).toBeNull(); // the pinned Overview tab
    const quickClose = tabWraps[1].querySelector('.tab-close-quick') as HTMLButtonElement;
    expect(quickClose).not.toBeNull();

    quickClose.click();
    fixture.detectChanges();

    expect(fixture.componentInstance.pendingCloseId).toBe('7-rename-toggle');
  });

  it('omits the Folder menu item once the overflow menu opens away from localhost, keeping Shell and Close (#497)', () => {
    TestBed.configureTestingModule({
      imports: [ConsoleTabsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(ConsoleTabsComponent);
    spyOn<any>(fixture.componentInstance, 'currentHostname').and.returnValue('example.com');
    fixture.componentInstance.tabs = [{ id: '7-rename-toggle', agent: 'claude', label: 'wtree · claude' }];
    fixture.detectChanges();

    const tabWrap = fixture.nativeElement.querySelectorAll('.tab-wrap')[1] as HTMLElement;
    (tabWrap.querySelector('.tab-menu-trigger') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(tabWrap.querySelector('.tab-reveal')).toBeNull();
    expect(tabWrap.querySelector('.tab-shell')).not.toBeNull();
    expect(tabWrap.querySelector('.tab-close')).not.toBeNull();
  });

  it('does not start a rename where renaming is not enabled (#393: the issue page)', () => {
    const c = new ConsoleTabsComponent();
    c.startRename({ id: '7-console-aaaaaaaa', agent: 'claude', label: 'console' }, new Event('dblclick'));

    expect(c.renamingId).toBeNull();
  });

  it('seeds the field with the name already given, never with the auto label (#393)', () => {
    const c = new ConsoleTabsComponent();
    c.renamable = true;

    c.startRename({ id: '7-console-aaaaaaaa', agent: 'claude', label: 'console · claude' }, new Event('dblclick'));
    expect(c.draftName).toBe('');

    c.startRename(
      { id: '7-console-bbbbbbbb', agent: 'claude', label: 'console 2', name: 'release notes' },
      new Event('dblclick'),
    );
    expect(c.draftName).toBe('release notes');
  });

  it('emits the trimmed name on commit, and an empty string to clear it (#393)', () => {
    const c = new ConsoleTabsComponent();
    c.renamable = true;
    const emitted: { id: string; name: string }[] = [];
    c.rename.subscribe((request) => emitted.push(request));

    c.startRename({ id: '7-console-aaaaaaaa', agent: 'claude', label: 'console' }, new Event('dblclick'));
    c.onRenameInput('  release notes  ');
    c.commitRename();

    c.startRename({ id: '7-console-aaaaaaaa', agent: 'claude', label: 'console' }, new Event('dblclick'));
    c.onRenameInput('   ');
    c.commitRename();

    expect(emitted).toEqual([
      { id: '7-console-aaaaaaaa', name: 'release notes' },
      { id: '7-console-aaaaaaaa', name: '' },
    ]);
    expect(c.renamingId).toBeNull();
  });

  it('cancelling emits nothing (#393)', () => {
    const c = new ConsoleTabsComponent();
    c.renamable = true;
    let emitted = 0;
    c.rename.subscribe(() => emitted++);

    c.startRename({ id: '7-console-aaaaaaaa', agent: 'claude', label: 'console' }, new Event('dblclick'));
    c.onRenameInput('never saved');
    c.cancelRename();
    // A stray commit after cancelling has no tab to name, so it stays silent.
    c.commitRename();

    expect(emitted).toBe(0);
    expect(c.renamingId).toBeNull();
  });
});

// The open-a-shell control (#447) talks HTTP and the DOM, so unlike the pure unit
// tests above these render the component under TestBed.
describe('ConsoleTabsComponent open-a-shell (#447)', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ConsoleTabsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function render(tabs: ConsoleTab[], overview = true) {
    const fixture = TestBed.createComponent(ConsoleTabsComponent);
    fixture.componentInstance.tabs = tabs;
    fixture.componentInstance.overview = overview;
    fixture.detectChanges();
    return fixture;
  }

  // The Shell menu item only exists once its tab's overflow menu is open (#480).
  function openMenu(fixture: ReturnType<typeof render>, tabWrapIndex = 0): void {
    const wraps = fixture.nativeElement.querySelectorAll('.tab-wrap');
    (wraps[tabWrapIndex].querySelector('.tab-menu-trigger') as HTMLButtonElement).click();
    fixture.detectChanges();
  }

  it('shows the Shell item once its tab menu is open, and never on the Overview pseudo-tab (#480)', () => {
    const fixture = render([{ id: '1-7-do-the-thing', agent: 'claude', label: 'wtree · claude' }]);

    // The Overview tab renders first with no overflow trigger at all.
    const overviewWrap = fixture.nativeElement.querySelector('.tab-wrap');
    expect(overviewWrap.querySelector('.tab-menu-trigger')).toBeNull();

    openMenu(fixture, 1);

    const icons = fixture.nativeElement.querySelectorAll('.tab-shell');
    expect(icons.length).toBe(1);
  });

  it('clicking Shell mints a shell at the tab-carried directory and opens the singleton window', () => {
    // The project-console page's tabs carry their directory (#447).
    const openSpy = spyOn(window, 'open');
    const fixture = render(
      [{ id: '1-console-aaaa0001', agent: 'shell', label: 'console', dir: '/repo-console-aaaa0001' }],
      false,
    );
    openMenu(fixture);

    (fixture.nativeElement.querySelector('.tab-shell') as HTMLButtonElement).click();

    const post = httpMock.expectOne('/api/projects/1/shells');
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({ issueNumber: null, workingDirectory: '/repo-console-aaaa0001' });
    post.flush({ sessionId: '1-shell-main-cccc0001', workingDirectory: '/repo-console-aaaa0001' });
    expect(openSpy).toHaveBeenCalledWith('/shells/1-shell-main-cccc0001', 'locklane-shells');
  });

  it('an issue tab with no carried directory resolves it from the project worktree list', () => {
    const openSpy = spyOn(window, 'open');
    const fixture = render([{ id: '1-7-do-the-thing', agent: 'claude', label: 'wtree · claude' }]);
    openMenu(fixture, 1);

    (fixture.nativeElement.querySelectorAll('.tab-shell')[0] as HTMLButtonElement).click();

    httpMock.expectOne('/api/projects/1/worktrees').flush([
      {
        worktreeId: '1-7-do-the-thing',
        issueNumber: 7,
        workingDirectory: '/repo-7',
        clean: true,
        sessionAttached: true,
      },
    ]);
    const post = httpMock.expectOne('/api/projects/1/shells');
    expect(post.request.body).toEqual({ issueNumber: 7, workingDirectory: '/repo-7' });
    post.flush({ sessionId: '1-shell-7-dddd0001', workingDirectory: '/repo-7' });
    expect(openSpy).toHaveBeenCalledWith('/shells/1-shell-7-dddd0001', 'locklane-shells');
  });

  it('clicking again mints another shell — no reuse', () => {
    const openSpy = spyOn(window, 'open');
    const fixture = render(
      [{ id: '1-console-aaaa0001', agent: 'shell', label: 'console', dir: '/repo-console-aaaa0001' }],
      false,
    );

    openMenu(fixture);
    (fixture.nativeElement.querySelector('.tab-shell') as HTMLButtonElement).click();
    httpMock
      .expectOne('/api/projects/1/shells')
      .flush({ sessionId: '1-shell-main-cccc0001', workingDirectory: '/repo-console-aaaa0001' });

    // Selecting the item closed the menu (#480); reopen it for the second click.
    openMenu(fixture);
    (fixture.nativeElement.querySelector('.tab-shell') as HTMLButtonElement).click();
    httpMock
      .expectOne('/api/projects/1/shells')
      .flush({ sessionId: '1-shell-main-cccc0002', workingDirectory: '/repo-console-aaaa0001' });

    expect(openSpy).toHaveBeenCalledTimes(2);
    expect(openSpy).toHaveBeenCalledWith('/shells/1-shell-main-cccc0002', 'locklane-shells');
  });

  it('a failed mint shows the error note instead of opening a window', () => {
    const openSpy = spyOn(window, 'open');
    const fixture = render(
      [{ id: '1-console-aaaa0001', agent: 'shell', label: 'console', dir: '/repo-console-aaaa0001' }],
      false,
    );
    openMenu(fixture);

    (fixture.nativeElement.querySelector('.tab-shell') as HTMLButtonElement).click();
    httpMock.expectOne('/api/projects/1/shells').flush(null, { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();

    expect(openSpy).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('.shell-error')).not.toBeNull();
  });
});
