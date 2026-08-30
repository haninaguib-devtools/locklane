import { ComponentFixture, fakeAsync, TestBed, tick } from '@angular/core/testing';
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

  readyState = FakeWebSocket.OPEN;
  onopen: (() => void) | null = null;
  onmessage: ((event: MessageEvent) => void) | null = null;
  onclose: (() => void) | null = null;

  constructor(public readonly url: string) {}

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

  function copyChordEvent(metaKey = false, ctrlKey = false): KeyboardEvent {
    return new KeyboardEvent('keydown', { key: 'c', metaKey, ctrlKey, cancelable: true });
  }

  function term(component: TerminalComponent): Terminal {
    return (component as unknown as { term: Terminal }).term;
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
});
