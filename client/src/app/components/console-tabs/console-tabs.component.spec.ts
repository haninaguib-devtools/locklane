import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ConsoleTabsComponent } from './console-tabs.component';
import { ConsoleTab } from './console-labels';
import { DefaultIdeStore } from '../../services/default-ide-store';

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

  it('with no installed agents known, the "+" starts a console with the default agent directly (#341, #757)', () => {
    const c = new ConsoleTabsComponent();
    c.defaultAgent = 'codex';
    let emitted: { agent: string } | undefined;
    c.open.subscribe((request) => (emitted = request));

    c.plusClicked();

    expect(emitted).toEqual({ agent: 'codex' });
    expect(c.pickerOpen).toBeFalse();
  });

  it('with exactly one installed agent, the "+" starts a console with that agent immediately, no picker (#757)', () => {
    const c = new ConsoleTabsComponent();
    c.defaultAgent = 'codex';
    c.installedAgents = [{ id: 'claude', label: 'Claude' }];
    let emitted: { agent: string } | undefined;
    c.open.subscribe((request) => (emitted = request));

    c.plusClicked();

    expect(c.offersPicker).toBeFalse();
    expect(c.pickerOpen).toBeFalse();
    expect(emitted).toEqual({ agent: 'claude' });
  });

  it('with two or more installed agents, the "+" opens the picker and emits only once an entry is chosen (#757)', () => {
    const c = new ConsoleTabsComponent();
    c.defaultAgent = 'claude';
    c.installedAgents = [
      { id: 'claude', label: 'Claude' },
      { id: 'codex', label: 'Codex' },
    ];
    const emitted: { agent: string }[] = [];
    c.open.subscribe((request) => emitted.push(request));
    const event = new Event('click');
    const stopSpy = spyOn(event, 'stopPropagation');

    c.plusClicked(event);

    expect(stopSpy).toHaveBeenCalled();
    expect(c.pickerOpen).toBeTrue();
    expect(emitted).toEqual([]);

    c.pickAgent('codex', new Event('click'));

    expect(c.pickerOpen).toBeFalse();
    expect(emitted).toEqual([{ agent: 'codex' }]);
  });

  it('dismissing the picker -- an outside click or Escape -- starts nothing (#757)', () => {
    const c = new ConsoleTabsComponent();
    c.installedAgents = [
      { id: 'claude', label: 'Claude' },
      { id: 'codex', label: 'Codex' },
    ];
    let emitted = 0;
    c.open.subscribe(() => emitted++);

    c.plusClicked(new Event('click'));
    expect(c.pickerOpen).toBeTrue();
    c.closeMenu();
    expect(c.pickerOpen).toBeFalse();

    c.plusClicked(new Event('click'));
    expect(c.pickerOpen).toBeTrue();
    c.closePicker();
    expect(c.pickerOpen).toBeFalse();

    // A second click on the button itself closes an open picker rather than stacking.
    c.plusClicked(new Event('click'));
    c.plusClicked(new Event('click'));
    expect(c.pickerOpen).toBeFalse();

    // The picker and a tab's overflow menu never show together: each opening closes the other.
    c.toggleMenu('7-rename-toggle', new Event('click'));
    c.plusClicked(new Event('click'));
    expect(c.openMenuId).toBeNull();
    expect(c.pickerOpen).toBeTrue();
    c.toggleMenu('7-rename-toggle', new Event('click'));
    expect(c.pickerOpen).toBeFalse();
    expect(c.isMenuOpen('7-rename-toggle')).toBeTrue();

    expect(emitted).toBe(0);
  });

  it('renders one picker entry per installed agent, labelled, and none until the "+" is clicked (#757)', () => {
    TestBed.configureTestingModule({
      imports: [ConsoleTabsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(ConsoleTabsComponent);
    fixture.componentInstance.overview = false;
    fixture.componentInstance.installedAgents = [
      { id: 'claude', label: 'Claude' },
      { id: 'codex', label: 'Codex' },
    ];
    const emitted: { agent: string }[] = [];
    fixture.componentInstance.open.subscribe((request) => emitted.push(request));
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;

    expect(root.querySelector('.agent-picker')).toBeNull();

    (root.querySelector('.plus') as HTMLButtonElement).click();
    fixture.detectChanges();

    const options = Array.from(root.querySelectorAll('.agent-option')) as HTMLButtonElement[];
    expect(options.map((option) => option.textContent!.trim())).toEqual(['Claude', 'Codex']);
    expect(emitted).toEqual([]);

    options[1].click();
    fixture.detectChanges();

    expect(emitted).toEqual([{ agent: 'codex' }]);
    expect(root.querySelector('.agent-picker')).toBeNull();
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

  it('omits only the Folder menu item once the overflow menu opens away from localhost, keeping Shell, Open IDE and Close (#497, #655)', () => {
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
    expect(tabWrap.querySelector('.tab-open-ide')).not.toBeNull();
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

// The Open IDE control (#627/#628, #782) talks HTTP and the DOM, the same shape as
// open-a-shell above.
describe('ConsoleTabsComponent open-the-ide (#628, #782)', () => {
  const IDE_STORAGE_KEY = 'locklane.defaultIde';
  let httpMock: HttpTestingController;

  beforeEach(() => {
    localStorage.removeItem(IDE_STORAGE_KEY);
    TestBed.configureTestingModule({
      imports: [ConsoleTabsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.removeItem(IDE_STORAGE_KEY);
  });

  function render(tabs: ConsoleTab[], overview = true) {
    const fixture = TestBed.createComponent(ConsoleTabsComponent);
    fixture.componentInstance.tabs = tabs;
    fixture.componentInstance.overview = overview;
    fixture.detectChanges();
    return fixture;
  }

  function openMenu(fixture: ReturnType<typeof render>, tabWrapIndex = 0): void {
    const wraps = fixture.nativeElement.querySelectorAll('.tab-wrap');
    (wraps[tabWrapIndex].querySelector('.tab-menu-trigger') as HTMLButtonElement).click();
    fixture.detectChanges();
  }

  /** Puts the page at `hostname` as far as the IDE store is concerned (#497's spy pattern). */
  function atHost(hostname: string): void {
    spyOn<any>(TestBed.inject(DefaultIdeStore), 'currentHostname').and.returnValue(hostname);
  }

  /** A strip rendered in a browser that chose `ide` in Settings (#782): stores it, renders, and answers the strip's installed-IDEs lookup. */
  function renderChosen(ide: string, hostname: string) {
    localStorage.setItem(IDE_STORAGE_KEY, ide);
    atHost(hostname);
    const fixture = render([{ id: '1-7-do-the-thing', agent: 'claude', label: 'wtree · claude' }]);
    httpMock.expectOne('/api/ides/installed').flush({
      installed: [
        { id: 'code-server', label: 'code-server', desktop: false },
        { id: 'vscode', label: 'VS Code', desktop: true },
        { id: 'intellij', label: 'IntelliJ IDEA', desktop: true },
      ],
    });
    fixture.detectChanges();
    return fixture;
  }

  const ideItem = (fixture: ReturnType<typeof render>) =>
    fixture.nativeElement.querySelector('.tab-open-ide') as HTMLButtonElement;

  it('shows the Open IDE item once its tab menu is open (#628)', () => {
    const fixture = render([{ id: '1-7-do-the-thing', agent: 'claude', label: 'wtree · claude' }]);
    openMenu(fixture, 1);

    expect(fixture.nativeElement.querySelectorAll('.tab-open-ide').length).toBe(1);
  });

  it('with no IDE chosen, the item reads "Open IDE" and nothing is looked up (#782)', () => {
    const fixture = render([{ id: '1-7-do-the-thing', agent: 'claude', label: 'wtree · claude' }]);
    openMenu(fixture, 1);

    expect(ideItem(fixture).textContent!.trim()).toBe('Open IDE');
    httpMock.expectNone('/api/ides/installed');
  });

  it('names a desktop IDE chosen on localhost -- "Open in VS Code" / "Open in IntelliJ IDEA" (#782)', () => {
    const fixture = renderChosen('intellij', 'localhost');
    openMenu(fixture, 1);

    expect(ideItem(fixture).textContent!.trim()).toBe('Open in IntelliJ IDEA');
  });

  it('keeps "Open IDE" for a desktop IDE chosen but viewed away from localhost, and for code-server (#782)', () => {
    const remote = renderChosen('vscode', 'example.com');
    openMenu(remote, 1);
    expect(ideItem(remote).textContent!.trim()).toBe('Open IDE');
    remote.destroy();

    // The store is a root singleton and fetched once already; only the stored choice changes.
    TestBed.inject(DefaultIdeStore).set('code-server');
    const local = render([{ id: '1-7-do-the-thing', agent: 'claude', label: 'wtree · claude' }]);
    openMenu(local, 1);
    expect(ideItem(local).textContent!.trim()).toBe('Open IDE');
  });

  it('clicking Open IDE starts code-server for that console and opens the singleton window', () => {
    const openSpy = spyOn(window, 'open');
    const fixture = render([{ id: '1-7-do-the-thing', agent: 'claude', label: 'wtree · claude' }]);
    openMenu(fixture, 1);

    ideItem(fixture).click();

    const post = httpMock.expectOne('/api/projects/1/consoles/1-7-do-the-thing/open-ide');
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({ ide: 'code-server' });
    // The engine's own proxied path (#655), relative so it opens against whatever
    // host this page was reached at -- never code-server's loopback address.
    post.flush({ url: '/api/projects/1/consoles/1-7-do-the-thing/ide/' });
    expect(openSpy).toHaveBeenCalledWith('/api/projects/1/consoles/1-7-do-the-thing/ide/', 'locklane-ide');
  });

  it('clicking a desktop choice names it to the engine and opens nothing here (#782)', () => {
    const openSpy = spyOn(window, 'open');
    const fixture = renderChosen('vscode', 'localhost');
    openMenu(fixture, 1);

    ideItem(fixture).click();

    const post = httpMock.expectOne('/api/projects/1/consoles/1-7-do-the-thing/open-ide');
    expect(post.request.body).toEqual({ ide: 'vscode' });
    post.flush({ url: null });
    fixture.detectChanges();

    expect(openSpy).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('.ide-error')).toBeNull();
  });

  it('a desktop choice viewed away from localhost falls back to code-server on the wire too (#782)', () => {
    // Always stub window.open here: a real one in headless Chrome opens a second window
    // that steals focus from the Karma page and fails every focus/visibility-dependent
    // spec that happens to run after this one.
    const openSpy = spyOn(window, 'open');
    const fixture = renderChosen('vscode', 'example.com');
    openMenu(fixture, 1);

    ideItem(fixture).click();

    const post = httpMock.expectOne('/api/projects/1/consoles/1-7-do-the-thing/open-ide');
    expect(post.request.body).toEqual({ ide: 'code-server' });
    post.flush({ url: '/api/projects/1/consoles/1-7-do-the-thing/ide/' });
    expect(openSpy).toHaveBeenCalledWith('/api/projects/1/consoles/1-7-do-the-thing/ide/', 'locklane-ide');
  });

  it('a failed start shows the error note instead of opening a window', () => {
    const openSpy = spyOn(window, 'open');
    const fixture = render([{ id: '1-7-do-the-thing', agent: 'claude', label: 'wtree · claude' }]);
    openMenu(fixture, 1);

    ideItem(fixture).click();
    httpMock
      .expectOne('/api/projects/1/consoles/1-7-do-the-thing/open-ide')
      .flush(null, { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();

    expect(openSpy).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('.ide-error')).not.toBeNull();
  });

  it('a refused desktop launch shows the same error note (#782)', () => {
    const openSpy = spyOn(window, 'open');
    const fixture = renderChosen('intellij', 'localhost');
    openMenu(fixture, 1);

    ideItem(fixture).click();
    httpMock
      .expectOne('/api/projects/1/consoles/1-7-do-the-thing/open-ide')
      .flush(null, { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();

    expect(openSpy).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('.ide-error')).not.toBeNull();
  });
});
