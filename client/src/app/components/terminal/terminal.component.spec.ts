import { ComponentFixture, fakeAsync, TestBed, tick } from '@angular/core/testing';
import { ClipboardAddon } from '@xterm/addon-clipboard';
import { FitAddon } from '@xterm/addon-fit';
import { Terminal } from '@xterm/xterm';
import { TerminalComponent } from './terminal.component';

// TerminalSession opens a real WebSocket the moment a tab connects -- stubbed out
// here (same shape as terminal-session.spec.ts's FakeWebSocket) purely so that
// never actually happens during these tests; nothing here exercises the connection.
class FakeWebSocket {
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSING = 2;
  static readonly CLOSED = 3;

  /** Every socket opened during one spec, so a test can read the connect URL (#375). */
  static opened: FakeWebSocket[] = [];

  readyState = FakeWebSocket.OPEN;
  onopen: (() => void) | null = null;
  onmessage: ((event: MessageEvent) => void) | null = null;
  onclose: (() => void) | null = null;

  constructor(public readonly url: string) {
    FakeWebSocket.opened.push(this);
  }

  send(): void {}

  close(): void {
    this.readyState = FakeWebSocket.CLOSED;
    this.onclose?.();
  }
}

describe('TerminalComponent', () => {
  let originalWebSocket: typeof WebSocket;
  let fixture: ComponentFixture<TerminalComponent> | null = null;

  beforeEach(() => {
    originalWebSocket = window.WebSocket;
    (window as unknown as { WebSocket: unknown }).WebSocket = FakeWebSocket;
    FakeWebSocket.opened = [];
    TestBed.configureTestingModule({ imports: [TerminalComponent] });
  });

  afterEach(() => {
    fixture?.destroy();
    fixture = null;
    (window as unknown as { WebSocket: unknown }).WebSocket = originalWebSocket;
  });

  /**
   * Creates the component and returns the key handler xterm's own
   * attachCustomKeyEventHandler was given -- captured via a spy rather than fired
   * through a real DOM keydown, since xterm reads the event straight from its
   * argument and never round-trips it through the DOM itself.
   */
  function createTerminal(): { component: TerminalComponent; handler: (event: KeyboardEvent) => boolean } {
    let handler!: (event: KeyboardEvent) => boolean;
    spyOn(Terminal.prototype, 'attachCustomKeyEventHandler').and.callFake(function (
      fn: (event: KeyboardEvent) => boolean,
    ) {
      handler = fn;
    });

    fixture = TestBed.createComponent(TerminalComponent);
    fixture.componentInstance.sessionId = 'test-session';
    fixture.detectChanges();
    return { component: fixture.componentInstance, handler };
  }

  /**
   * Mounts a tab with `active` set either way, without the key-handler spy the copy
   * specs need. Nothing is ticked here — the mount fit and connect are deferred one
   * tick (#268), and each spec decides when to let that run.
   */
  function mountTab(active: boolean): TerminalComponent {
    fixture = TestBed.createComponent(TerminalComponent);
    fixture.componentInstance.sessionId = 'test-session';
    fixture.componentInstance.active = active;
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  function copyChordEvent(metaKey = false, ctrlKey = false): KeyboardEvent {
    return new KeyboardEvent('keydown', { key: 'c', metaKey, ctrlKey, cancelable: true });
  }

  function term(component: TerminalComponent): Terminal {
    return (component as unknown as { term: Terminal }).term;
  }

  function containerEl(): HTMLElement {
    return fixture!.nativeElement.querySelector('.terminal');
  }

  function rightClickOnTerminal(): MouseEvent {
    // Dispatched on xterm's own element inside the container, the way a real click
    // lands -- the component's capture-phase listener on the container must run
    // before it reaches xterm.
    const event = new MouseEvent('mousedown', { button: 2, bubbles: true, cancelable: true });
    spyOn(event, 'stopPropagation').and.callThrough();
    (containerEl().querySelector('.xterm') as HTMLElement).dispatchEvent(event);
    return event;
  }

  it('passes rightClickSelectsWord: false to xterm so a right-click preserves a drag selection (#350)', () => {
    const { component } = createTerminal();
    expect((term(component) as unknown as { options: { rightClickSelectsWord: boolean } }).options.rightClickSelectsWord).toBeFalse();
  });

  it('falls through to xterm when Cmd/Ctrl+C fires with no selection, leaving interrupt behavior unchanged', () => {
    const { component, handler } = createTerminal();
    spyOn(term(component), 'hasSelection').and.returnValue(false);
    const event = copyChordEvent(true);
    const preventDefaultSpy = spyOn(event, 'preventDefault');

    expect(handler(event)).toBeTrue();
    expect(preventDefaultSpy).not.toHaveBeenCalled();
  });

  it('prevents default and swallows the chord when a selection is present', () => {
    const { component, handler } = createTerminal();
    spyOn(term(component), 'hasSelection').and.returnValue(true);
    spyOn(term(component), 'getSelection').and.returnValue('selected text');
    spyOnProperty(navigator, 'clipboard', 'get').and.returnValue({
      writeText: (): Promise<void> => Promise.resolve(),
    } as unknown as Clipboard);
    const event = copyChordEvent(true);
    const preventDefaultSpy = spyOn(event, 'preventDefault');

    expect(handler(event)).toBeFalse();
    expect(preventDefaultSpy).toHaveBeenCalled();
  });

  it('copies the selection via the async Clipboard API when it is available and resolves', () => {
    const { component, handler } = createTerminal();
    spyOn(term(component), 'hasSelection').and.returnValue(true);
    spyOn(term(component), 'getSelection').and.returnValue('selected text');
    const writeText = jasmine.createSpy('writeText').and.returnValue(Promise.resolve());
    spyOnProperty(navigator, 'clipboard', 'get').and.returnValue({ writeText } as unknown as Clipboard);
    const execCommandSpy = spyOn(document, 'execCommand');

    handler(copyChordEvent(true));

    expect(writeText).toHaveBeenCalledWith('selected text');
    expect(execCommandSpy).not.toHaveBeenCalled();
  });

  it('falls back to a synchronous execCommand(copy) when the Clipboard API is unavailable', fakeAsync(() => {
    const { component, handler } = createTerminal();
    spyOn(term(component), 'hasSelection').and.returnValue(true);
    spyOn(term(component), 'getSelection').and.returnValue('selected text');
    spyOnProperty(navigator, 'clipboard', 'get').and.returnValue(undefined as unknown as Clipboard);
    const execCommandSpy = spyOn(document, 'execCommand').and.returnValue(true);

    handler(copyChordEvent(true));
    tick();

    expect(execCommandSpy).toHaveBeenCalledWith('copy');
  }));

  it('falls back to execCommand(copy) when writeText rejects (Safari: no user-gesture credit for a keydown; Chrome: blocked clipboard permission)', fakeAsync(() => {
    const { component, handler } = createTerminal();
    spyOn(term(component), 'hasSelection').and.returnValue(true);
    spyOn(term(component), 'getSelection').and.returnValue('selected text');
    spyOnProperty(navigator, 'clipboard', 'get').and.returnValue({
      writeText: (): Promise<void> => Promise.reject(new Error('denied')),
    } as unknown as Clipboard);
    const execCommandSpy = spyOn(document, 'execCommand').and.returnValue(true);

    handler(copyChordEvent(false, true));
    tick();

    expect(execCommandSpy).toHaveBeenCalledWith('copy');
  }));

  it('logs rather than silently swallowing a copy that fails even through the fallback', fakeAsync(() => {
    const { component, handler } = createTerminal();
    spyOn(term(component), 'hasSelection').and.returnValue(true);
    spyOn(term(component), 'getSelection').and.returnValue('selected text');
    spyOnProperty(navigator, 'clipboard', 'get').and.returnValue({
      writeText: (): Promise<void> => Promise.reject(new Error('denied')),
    } as unknown as Clipboard);
    spyOn(document, 'execCommand').and.returnValue(false);
    const consoleErrorSpy = spyOn(console, 'error');

    handler(copyChordEvent(false, true));
    tick();

    expect(consoleErrorSpy).toHaveBeenCalled();
  }));

  it('loads the clipboard addon so an OSC 52 write from the PTY application is not dropped (#435)', () => {
    const loadAddonSpy = spyOn(Terminal.prototype, 'loadAddon').and.callThrough();

    mountTab(true);

    expect(loadAddonSpy.calls.allArgs().some((args) => args[0] instanceof ClipboardAddon))
      .withContext('a ClipboardAddon instance must be among the loaded addons')
      .toBeTrue();
  });

  it('puts an OSC 52 payload from the PTY application onto the browser clipboard (#435)', (done) => {
    const writeText = jasmine.createSpy('writeText').and.returnValue(Promise.resolve());
    spyOnProperty(navigator, 'clipboard', 'get').and.returnValue({ writeText } as unknown as Clipboard);

    const component = mountTab(true);
    // "hello" base64-encoded, exactly as claude emits it when its own Ctrl+C copies.
    term(component).write('\x1b]52;c;aGVsbG8=\x07', () => {
      expect(writeText).toHaveBeenCalledWith('hello');
      done();
    });
  });

  it('declines an OSC 52 clipboard *read*, so a PTY application cannot see the clipboard (#435)', (done) => {
    const readText = jasmine.createSpy('readText').and.returnValue(Promise.resolve('secret'));
    spyOnProperty(navigator, 'clipboard', 'get').and.returnValue({ readText } as unknown as Clipboard);

    const component = mountTab(true);
    term(component).write('\x1b]52;c;?\x07', () => {
      expect(readText).not.toHaveBeenCalled();
      done();
    });
  });

  it('swallows a right-click mousedown while the application has mouse tracking on, so the press never reaches the CLI (#435)', (done) => {
    const component = mountTab(true);
    // DECSET 1002: button-event mouse tracking, one of the modes claude enables.
    term(component).write('\x1b[?1002h', () => {
      expect(term(component).modes.mouseTrackingMode).not.toBe('none');

      const event = rightClickOnTerminal();

      expect(event.stopPropagation).toHaveBeenCalled();
      done();
    });
  });

  it('leaves a right-click alone in a plain shell tab (no mouse tracking), preserving #350 behavior', () => {
    mountTab(true);

    const event = rightClickOnTerminal();

    expect(event.stopPropagation).not.toHaveBeenCalled();
  });

  it('leaves a left-click alone under mouse tracking -- only button 2 is suppressed (#435)', (done) => {
    const component = mountTab(true);
    term(component).write('\x1b[?1002h', () => {
      const event = new MouseEvent('mousedown', { button: 0, bubbles: true, cancelable: true });
      spyOn(event, 'stopPropagation').and.callThrough();
      (containerEl().querySelector('.xterm') as HTMLElement).dispatchEvent(event);

      expect(event.stopPropagation).not.toHaveBeenCalled();
      done();
    });
  });

  it('opens xterm at mount even for a tab that is not the selected one (#375)', () => {
    const openSpy = spyOn(Terminal.prototype, 'open').and.callThrough();

    mountTab(false);

    expect(openSpy).toHaveBeenCalled();
  });

  it('fits an unselected tab before it connects, so its connect URL carries a measured size (#375)', fakeAsync(() => {
    // What the old code got wrong: a tab that mounted hidden connected straight away,
    // so the engine started its PTY at xterm's 80x24 defaults. The ordering below is
    // what makes the URL carry a real size instead -- the size cannot be measured after
    // the socket is already open, because the engine has created the PTY by then.
    let socketsWhenFitted = -1;
    spyOn(FitAddon.prototype, 'fit').and.callFake(() => {
      socketsWhenFitted = FakeWebSocket.opened.length;
    });

    mountTab(false);
    expect(FakeWebSocket.opened.length)
      .withContext('an unselected tab must not connect before it has been measured')
      .toBe(0);

    tick();

    expect(socketsWhenFitted).withContext('fit() must run before the socket opens').toBe(0);
    expect(FakeWebSocket.opened.length).toBe(1);
  }));

  it('leaves the keyboard alone when it mounts unselected, and takes it when selected (#166)', fakeAsync(() => {
    const focusSpy = spyOn(Terminal.prototype, 'focus');

    mountTab(false);
    tick();
    expect(focusSpy).not.toHaveBeenCalled();

    fixture?.destroy();
    fixture = null;

    mountTab(true);
    tick();
    expect(focusSpy).toHaveBeenCalled();
  }));

  it('refits on a container resize that happens while the tab is hidden (#375)', fakeAsync(() => {
    // The debounced refit used to be gated on the tab being active, so a window resize
    // while a console sat in the background was simply dropped and the tab kept the
    // size it had before. It is measurable while hidden now, so it must react.
    const originalResizeObserver = window.ResizeObserver;
    let notifyResize: (() => void) | null = null;
    (window as unknown as { ResizeObserver: unknown }).ResizeObserver = class {
      constructor(callback: () => void) {
        notifyResize = callback;
      }
      observe(): void {}
      disconnect(): void {}
    };

    try {
      mountTab(false);
      tick();

      const fitSpy = spyOn(FitAddon.prototype, 'fit');
      notifyResize!();
      tick(150); // TerminalComponent.FIT_QUIET_MS

      expect(fitSpy).toHaveBeenCalled();
    } finally {
      (window as unknown as { ResizeObserver: unknown }).ResizeObserver = originalResizeObserver;
    }
  }));
});
