import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AgentSessionTabsComponent } from './agent-session-tabs.component';
import { AgentSessionTab } from './agent-session-labels';
import { DefaultIdeStore } from '../../services/default-ide-store';
import { EventsService } from '../../services/events.service';

// Session ids ("<projectId>-console[-<hex>]"), "<repo>-console-<hex>" worktree directories, the
// /console and /consoles REST paths and the 'console' route segment below keep their persisted and
// on-the-wire shape: compatibility surfaces kept under ADR-112 (#766 renamed only the identifiers).

describe('AgentSessionTabsComponent', () => {
  it('emits the clicked agent session id', () => {
    const c = new AgentSessionTabsComponent();
    c.tabs = [
      { id: '7-main-a1b2c3d4', agent: 'shell', label: 'main · shell' },
      { id: '7-rename-toggle', agent: 'claude', label: 'wtree · claude' },
    ];
    let emitted: string | undefined;
    c.selectedChange.subscribe((id) => (emitted = id));

    c.select('7-rename-toggle');

    expect(emitted).toBe('7-rename-toggle');
  });

  // The picker's own choice/open-close logic is AgentShellPickerComponent's now
  // (#886), tested once there; these specs cover the tab strip's own wiring into it
  // -- its inputs reach the picker, and its outputs drive `open`/`openShell` --
  // plus the mutual exclusion with a tab's own overflow menu.

  it('the picker and a tab\'s overflow menu never show together: each opening closes the other (#757, #886)', () => {
    TestBed.configureTestingModule({
      imports: [AgentSessionTabsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(AgentSessionTabsComponent);
    fixture.componentInstance.overview = false;
    fixture.componentInstance.tabs = [{ id: '7-rename-toggle', agent: 'claude', label: 'wtree · claude' }];
    fixture.componentInstance.installedAgents = [{ id: 'claude', label: 'Claude' }];
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;

    (root.querySelector('.tab-menu-trigger') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(fixture.componentInstance.isMenuOpen('7-rename-toggle')).toBeTrue();

    (root.querySelector('.plus') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(fixture.componentInstance.openMenuId).toBeNull();
    expect(root.querySelector('.agent-picker')).not.toBeNull();

    (root.querySelector('.tab-menu-trigger') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(root.querySelector('.agent-picker')).toBeNull();
    expect(fixture.componentInstance.isMenuOpen('7-rename-toggle')).toBeTrue();
  });

  it('renders one picker entry per installed agent plus Shell, labelled, and none until the "+" is clicked (#757, #876)', () => {
    TestBed.configureTestingModule({
      imports: [AgentSessionTabsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(AgentSessionTabsComponent);
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
    expect(options.map((option) => option.textContent!.trim())).toEqual(['Claude', 'Codex', 'Shell']);
    expect(emitted).toEqual([]);

    options[1].click();
    fixture.detectChanges();

    expect(emitted).toEqual([{ agent: 'codex' }]);
    expect(root.querySelector('.agent-picker')).toBeNull();
  });

  it('picking Shell emits openShell and closes the picker (#876)', () => {
    TestBed.configureTestingModule({
      imports: [AgentSessionTabsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(AgentSessionTabsComponent);
    fixture.componentInstance.overview = false;
    fixture.componentInstance.installedAgents = [{ id: 'claude', label: 'Claude' }];
    let shelled = 0;
    fixture.componentInstance.openShell.subscribe(() => shelled++);
    let opened = 0;
    fixture.componentInstance.open.subscribe(() => opened++);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;

    (root.querySelector('.plus') as HTMLButtonElement).click();
    fixture.detectChanges();

    const options = Array.from(root.querySelectorAll('.agent-option')) as HTMLButtonElement[];
    expect(options.map((option) => option.textContent!.trim())).toEqual(['Claude', 'Shell']);
    options[1].click();
    fixture.detectChanges();

    expect(shelled).toBe(1);
    expect(opened).toBe(0);
    expect(root.querySelector('.agent-picker')).toBeNull();
  });

  it('the "+" button stays visible with tabs open -- shells need it even beside a live agent session (#876)', () => {
    TestBed.configureTestingModule({
      imports: [AgentSessionTabsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(AgentSessionTabsComponent);
    fixture.componentInstance.tabs = [{ id: '7-rename-toggle', agent: 'claude', label: 'wtree · claude' }];
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelector('.plus')).not.toBeNull();
  });

  it('closeTab stops propagation, closes the menu, and awaits confirmation before emitting', () => {
    const c = new AgentSessionTabsComponent();
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
    const c = new AgentSessionTabsComponent();
    let emitted: string | undefined;
    c.close.subscribe((id) => (emitted = id));
    c.closeTab('7-rename-toggle', new Event('click'));

    c.confirmClose();

    expect(emitted).toBe('7-rename-toggle');
    expect(c.pendingCloseId).toBeNull();
  });

  it('emits nothing when the pending close is cancelled', () => {
    const c = new AgentSessionTabsComponent();
    let emitted: string | undefined;
    c.close.subscribe((id) => (emitted = id));
    c.closeTab('7-rename-toggle', new Event('click'));

    c.cancelClose();

    expect(emitted).toBeUndefined();
    expect(c.pendingCloseId).toBeNull();
  });

  it('revealTab stops propagation, closes the menu, and emits the agent session id immediately, with no confirmation (#441, #480)', () => {
    const c = new AgentSessionTabsComponent();
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
    const c = new AgentSessionTabsComponent();

    // Karma itself serves specs from localhost, so the default reads true.
    expect(c.isLocalHost).toBeTrue();

    spyOn<any>(c, 'currentHostname').and.returnValue('example.com');
    expect(c.isLocalHost).toBeFalse();
  });

  it('opens one tab menu at a time, closes on an outside click (#480)', () => {
    const c = new AgentSessionTabsComponent();

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

  it('renders the overflow trigger and, once opened, a Folder menu item on a live agent session tab but not on the pinned Overview tab (#441, #480)', () => {
    // The http providers exist for the open-a-shell control's services (#447),
    // constructed with the component; this test itself never talks HTTP.
    TestBed.configureTestingModule({
      imports: [AgentSessionTabsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(AgentSessionTabsComponent);
    fixture.componentInstance.tabs = [{ id: '7-rename-toggle', agent: 'claude', label: 'wtree · claude' }];
    fixture.detectChanges();

    const tabWraps = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.tab-wrap'));
    expect(tabWraps.length).toBe(2); // the pinned Overview tab, plus the one agent session tab
    expect(tabWraps[0].querySelector('.tab-menu-trigger')).toBeNull();
    expect(tabWraps[1].querySelector('.tab-menu-trigger')).not.toBeNull();
    expect(tabWraps[1].querySelector('.tab-reveal')).toBeNull(); // menu closed

    (tabWraps[1].querySelector('.tab-menu-trigger') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(tabWraps[1].querySelector('.tab-reveal')).not.toBeNull();
  });

  it('shows a quick close button on every live agent session tab, without opening the overflow menu (#497)', () => {
    TestBed.configureTestingModule({
      imports: [AgentSessionTabsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(AgentSessionTabsComponent);
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

  it('omits only the Folder menu item once the overflow menu opens away from localhost, keeping Open IDE and Close (#497, #655)', () => {
    TestBed.configureTestingModule({
      imports: [AgentSessionTabsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(AgentSessionTabsComponent);
    spyOn<any>(fixture.componentInstance, 'currentHostname').and.returnValue('example.com');
    fixture.componentInstance.tabs = [{ id: '7-rename-toggle', agent: 'claude', label: 'wtree · claude' }];
    fixture.detectChanges();

    const tabWrap = fixture.nativeElement.querySelectorAll('.tab-wrap')[1] as HTMLElement;
    (tabWrap.querySelector('.tab-menu-trigger') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(tabWrap.querySelector('.tab-reveal')).toBeNull();
    expect(tabWrap.querySelector('.tab-open-ide')).not.toBeNull();
    // Shells are tabs now (#876) -- no per-tab Shell item anymore.
    expect(tabWrap.querySelector('.tab-shell')).toBeNull();
    expect(tabWrap.querySelector('.tab-close')).not.toBeNull();
  });

  it('does not start a rename where renaming is not enabled (#393: the issue page)', () => {
    const c = new AgentSessionTabsComponent();
    c.startRename({ id: '7-console-aaaaaaaa', agent: 'claude', label: 'agent' }, new Event('dblclick'));

    expect(c.renamingId).toBeNull();
  });

  it('seeds the field with the name already given, never with the auto label (#393)', () => {
    const c = new AgentSessionTabsComponent();
    c.renamable = true;

    c.startRename({ id: '7-console-aaaaaaaa', agent: 'claude', label: 'agent session · claude' }, new Event('dblclick'));
    expect(c.draftName).toBe('');

    c.startRename(
      { id: '7-console-bbbbbbbb', agent: 'claude', label: 'agent session 2', name: 'release notes' },
      new Event('dblclick'),
    );
    expect(c.draftName).toBe('release notes');
  });

  it('emits the trimmed name on commit, and an empty string to clear it (#393)', () => {
    const c = new AgentSessionTabsComponent();
    c.renamable = true;
    const emitted: { id: string; name: string }[] = [];
    c.rename.subscribe((request) => emitted.push(request));

    c.startRename({ id: '7-console-aaaaaaaa', agent: 'claude', label: 'agent' }, new Event('dblclick'));
    c.onRenameInput('  release notes  ');
    c.commitRename();

    c.startRename({ id: '7-console-aaaaaaaa', agent: 'claude', label: 'agent' }, new Event('dblclick'));
    c.onRenameInput('   ');
    c.commitRename();

    expect(emitted).toEqual([
      { id: '7-console-aaaaaaaa', name: 'release notes' },
      { id: '7-console-aaaaaaaa', name: '' },
    ]);
    expect(c.renamingId).toBeNull();
  });

  it('cancelling emits nothing (#393)', () => {
    const c = new AgentSessionTabsComponent();
    c.renamable = true;
    let emitted = 0;
    c.rename.subscribe(() => emitted++);

    c.startRename({ id: '7-console-aaaaaaaa', agent: 'claude', label: 'agent' }, new Event('dblclick'));
    c.onRenameInput('never saved');
    c.cancelRename();
    // A stray commit after cancelling has no tab to name, so it stays silent.
    c.commitRename();

    expect(emitted).toBe(0);
    expect(c.renamingId).toBeNull();
  });
});

// Shell tabs (#876) render in the same strip as agent tabs: no per-tab Shell
// menu item (the tab *is* the shell), no rename, and a close dialog that names
// what it is about to end.
describe('AgentSessionTabsComponent shell tabs (#876)', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AgentSessionTabsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
  });

  function render(tabs: AgentSessionTab[], overview = true) {
    const fixture = TestBed.createComponent(AgentSessionTabsComponent);
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

  it('renders a shell tab beside the agent tabs, with no Shell item in its overflow menu', () => {
    const fixture = render(
      [
        { id: '1-console-aaaa0001', agent: 'claude', label: 'agent' },
        { id: '1-shell-main-cccc0001', agent: null, label: 'shell', kind: 'shell' },
      ],
      false,
    );

    const wraps = fixture.nativeElement.querySelectorAll('.tab-wrap');
    expect(wraps.length).toBe(2);
    expect(wraps[1].textContent).toContain('shell');

    openMenu(fixture, 1);

    expect(fixture.nativeElement.querySelectorAll('.tab-shell').length).toBe(0);
  });

  it('never starts a rename on a shell tab, even where renaming is on', () => {
    const c = new AgentSessionTabsComponent();
    c.renamable = true;
    c.tabs = [{ id: '1-shell-main-cccc0001', agent: null, label: 'shell', kind: 'shell' }];

    c.startRename({ id: '1-shell-main-cccc0001', agent: null, label: 'shell', kind: 'shell' }, new Event('dblclick'));

    expect(c.renamingId).toBeNull();
    expect(c.tabTitle('1-shell-main-cccc0001')).toBeNull();
  });

  it('the pending close dialog names a shell tab as a shell', () => {
    const c = new AgentSessionTabsComponent();
    c.tabs = [
      { id: '1-console-aaaa0001', agent: 'claude', label: 'agent' },
      { id: '1-shell-main-cccc0001', agent: null, label: 'shell', kind: 'shell' },
    ];

    c.closeTab('1-console-aaaa0001', new Event('click'));
    expect(c.pendingCloseIsShell).toBeFalse();
    expect(c.closeTitle).toBe('Close agent?');
    c.cancelClose();

    c.closeTab('1-shell-main-cccc0001', new Event('click'));
    expect(c.pendingCloseIsShell).toBeTrue();
    expect(c.closeTitle).toBe('Close shell?');
    expect(c.closeMessage).toContain('shell session');
  });
});

// The Open IDE control (#627/#628, #782) talks HTTP and the DOM, so like the
// shell-tabs suite above it renders under TestBed.
describe('AgentSessionTabsComponent open-the-ide (#628, #782)', () => {
  const IDE_STORAGE_KEY = 'locklane.defaultIde';
  let httpMock: HttpTestingController;

  beforeEach(() => {
    localStorage.removeItem(IDE_STORAGE_KEY);
    TestBed.configureTestingModule({
      imports: [AgentSessionTabsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.removeItem(IDE_STORAGE_KEY);
  });

  function render(tabs: AgentSessionTab[], overview = true) {
    const fixture = TestBed.createComponent(AgentSessionTabsComponent);
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

  it('clicking Open IDE starts code-server for that agent session and opens the singleton window', () => {
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

// The per-tab attention dot (#791) reads the shared AttentionStore, so like the
// open-a-shell suites above these render under TestBed -- the store is providedIn
// root and constructs against the (never connected) EventsService.
describe('AgentSessionTabsComponent attention dot (#791)', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AgentSessionTabsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
  });

  /** Reaches past EventsService's public API (#129) -- there is no other way to fake an incoming socket message. */
  function emitAppEvent(event: unknown): void {
    (TestBed.inject(EventsService) as unknown as { eventsSubject: { next: (e: unknown) => void } }).eventsSubject.next(
      event,
    );
  }

  function render(tabs: AgentSessionTab[], overview = true) {
    const fixture = TestBed.createComponent(AgentSessionTabsComponent);
    fixture.componentInstance.tabs = tabs;
    fixture.componentInstance.overview = overview;
    fixture.detectChanges();
    return fixture;
  }

  function tabButtons(fixture: ReturnType<typeof render>): HTMLButtonElement[] {
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button.tab'));
  }

  it('renders a dot inside every agent tab, and none on the Overview tab', () => {
    const fixture = render([
      { id: '1-7-rename-toggle', agent: 'claude', label: 'wtree · claude' },
      { id: '1-console-aaaa0001', agent: 'codex', label: 'agent' },
    ]);

    const buttons = tabButtons(fixture);
    expect(buttons.length).toBe(3);
    expect(buttons[0].textContent!.trim()).toBe('Overview');
    expect(buttons[0].querySelector('.tab-dot')).toBeNull();
    expect(buttons[1].querySelector('.tab-dot')).not.toBeNull();
    expect(buttons[2].querySelector('.tab-dot')).not.toBeNull();
    // Plain blue until the store says otherwise; nothing claims to be waiting.
    expect(fixture.nativeElement.querySelectorAll('.tab-dot.waiting').length).toBe(0);
    expect(buttons[1].getAttribute('title')).toBeNull();
    expect(buttons[1].getAttribute('aria-label')).toBeNull();
  });

  it('the waiting class follows the store for that session only, and the button says so in words', () => {
    const fixture = render([
      { id: '1-7-rename-toggle', agent: 'claude', label: 'wtree · claude' },
      { id: '1-console-aaaa0001', agent: 'codex', label: 'agent' },
    ]);

    emitAppEvent({ type: 'consoleAttention', sessionId: '1-console-aaaa0001', state: 'waiting' });
    fixture.detectChanges();

    let buttons = tabButtons(fixture);
    expect(buttons[1].querySelector('.tab-dot')!.classList.contains('waiting')).toBeFalse();
    expect(buttons[2].querySelector('.tab-dot')!.classList.contains('waiting')).toBeTrue();
    expect(buttons[2].getAttribute('title')).toBe('Waiting for you');
    expect(buttons[2].getAttribute('aria-label')).toBe('agent, waiting for you');
    expect(buttons[1].getAttribute('aria-label')).toBeNull();

    emitAppEvent({ type: 'consoleAttention', sessionId: '1-console-aaaa0001', state: 'active' });
    fixture.detectChanges();

    buttons = tabButtons(fixture);
    expect(fixture.nativeElement.querySelectorAll('.tab-dot.waiting').length).toBe(0);
    expect(buttons[2].getAttribute('title')).toBeNull();
    expect(buttons[2].getAttribute('aria-label')).toBeNull();
  });

  it('a waiting tab keeps its title over the rename hint until it settles (#393)', () => {
    const fixture = TestBed.createComponent(AgentSessionTabsComponent);
    fixture.componentInstance.renamable = true;
    fixture.componentInstance.overview = false;
    fixture.componentInstance.tabs = [{ id: '1-console-aaaa0001', agent: 'codex', label: 'agent' }];
    fixture.detectChanges();

    expect(tabButtons(fixture)[0].getAttribute('title')).toBe('double-click to rename');

    emitAppEvent({ type: 'consoleAttention', sessionId: '1-console-aaaa0001', state: 'waiting' });
    fixture.detectChanges();
    expect(tabButtons(fixture)[0].getAttribute('title')).toBe('Waiting for you');

    emitAppEvent({ type: 'consoleAttention', sessionId: '1-console-aaaa0001', state: 'active' });
    fixture.detectChanges();
    expect(tabButtons(fixture)[0].getAttribute('title')).toBe('double-click to rename');
  });

  it('constructed bare, with no store, never reports a tab waiting', () => {
    const c = new AgentSessionTabsComponent();
    c.tabs = [{ id: '1-console-aaaa0001', agent: 'codex', label: 'agent' }];

    expect(c.isWaiting('1-console-aaaa0001')).toBeFalse();
    expect(c.tabTitle('1-console-aaaa0001')).toBeNull();
  });
});
