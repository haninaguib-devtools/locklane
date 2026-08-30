import {
  AfterViewInit,
  Component,
  ElementRef,
  Input,
  OnChanges,
  OnDestroy,
  SimpleChanges,
  ViewChild,
} from '@angular/core';
import { FitAddon } from '@xterm/addon-fit';
import { IDisposable, Terminal } from '@xterm/xterm';
import { TerminalSession } from '../../services/terminal-session';

// One console tab's terminal. An instance is bound to a single session for its
// whole life (#30: every tab stays mounted and connected, hidden with CSS when
// not selected, so switching tabs never drops a connection or its scrollback).
@Component({
  selector: 'app-terminal',
  standalone: true,
  templateUrl: './terminal.component.html',
  styleUrl: './terminal.component.css',
})
export class TerminalComponent implements AfterViewInit, OnChanges, OnDestroy {
  @Input({ required: true }) sessionId!: string;
  @Input() dir: string | null = null;
  @Input() cmd: string | null = null;
  /** Past-conversation id (#103): a brand-new claude/codex session resumes it. */
  @Input() resume: string | null = null;
  /**
   * Whether this tab is the selected one. Sizing no longer depends on it (#375: a hidden
   * tab keeps a layout box, so every tab measures itself at mount); it decides only who
   * takes keyboard focus and which session the engine is told the user is looking at.
   */
  @Input() active = true;

  @ViewChild('container', { static: true }) container!: ElementRef<HTMLDivElement>;

  private term: Terminal | null = null;
  private fitAddon: FitAddon | null = null;
  private session: TerminalSession | null = null;
  private resizeSub: IDisposable | null = null;
  private resizeObserver: ResizeObserver | null = null;
  private pendingFit: ReturnType<typeof setTimeout> | null = null;
  /** The deferred first measure-then-connect, for every tab now (#268, #375). */
  private pendingInit: ReturnType<typeof setTimeout> | null = null;

  // The browser tab regaining focus after being backgrounded (#279) -- distinct from
  // the `active`/`ngOnChanges` app-level tab switch above, and the only way a session
  // whose connection died while unattended gets checked without the user navigating
  // away and back. Bound once so removeEventListener in ngOnDestroy actually matches;
  // `visibilityState` is re-checked because `focus` alone can fire while still hidden
  // (e.g. a devtools panel taking focus without the page itself becoming visible).
  private readonly checkConnectionOnForeground = (): void => {
    if (document.visibilityState === 'visible') {
      this.session?.checkConnection();
    }
  };

  /* A sidenav-slider drag or live window resize fires the ResizeObserver on
     every pixel. Fitting on each tick streams a column count per pixel to the
     server, and each reflow rewraps the CLI's full-width output into scrollback
     for good (#117). One fit after the size settles keeps a resize to a single
     redraw. */
  private static readonly FIT_QUIET_MS = 150;

  ngAfterViewInit(): void {
    this.term = new Terminal({
      fontFamily: 'ui-monospace, "SF Mono", Menlo, monospace',
      fontSize: 13,
      theme: { background: '#1c1a17' },
      convertEol: true,
      // xterm defaults this to on for macOS only, which replaces a drag selection
      // with just the word under the pointer the instant the user right-clicks --
      // so the context menu's own Copy could never copy more than one word (#350).
      rightClickSelectsWord: false,
    });
    this.fitAddon = new FitAddon();
    this.term.loadAddon(this.fitAddon);
    // xterm's default binds Ctrl/Cmd+C to sending an interrupt (SIGINT) no matter
    // what, and its canvas has `user-select: none` so there is no real DOM selection
    // for a browser copy shortcut to act on either (#226). With a selection present,
    // copy it ourselves and swallow the chord; with none, fall through to xterm's
    // normal interrupt handling unchanged.
    this.term.attachCustomKeyEventHandler((event) => {
      const isCopyChord =
        event.type === 'keydown' && (event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'c';
      if (isCopyChord && this.term?.hasSelection()) {
        event.preventDefault();
        this.copySelection(this.term.getSelection());
        return false;
      }
      return true;
    });
    // Every tab is opened and fitted here, selected or not (#375). An inactive tab is
    // hidden out of flow with `visibility: hidden` rather than removed with
    // `display: none`, so it still has a layout box: xterm can measure its character
    // size at open() time, and FitAddon can propose real dimensions instead of the NaN
    // an unmeasurable container produced — which is what left a tab that mounted hidden
    // stuck at xterm's 80x24 constructor default, parsing replayed output at the wrong
    // width for the rest of the session.
    this.term.open(this.container.nativeElement);
    // The container may not have its final layout size yet at this point in the
    // change-detection cycle, so the first measurement is deferred a tick rather than
    // locking in a bad cached size the way #257 did. The connection waits for that
    // measurement rather than racing it: the size on the connect URL is the size the
    // engine starts a new PTY at, so connecting first would start the process at the
    // constructor defaults and never correct it (#268).
    this.pendingInit = setTimeout(() => {
      this.pendingInit = null;
      this.fitAddon?.fit();
      // Only the selected tab takes the keyboard — focusing a hidden one would pull
      // focus away from whatever the user is actually looking at (#166).
      if (this.active) {
        this.term?.focus();
      }
      this.connect();
    });
    this.term.onData((input) => this.session?.send(input));
    // Fires for every size xterm settles on — the initial fit above and the
    // ResizeObserver-driven fit below — so the server's PTY is told every time,
    // not just once at connect.
    this.resizeSub = this.term.onResize(({ cols, rows }) => this.session?.resize(cols, rows));
    // xterm's FitAddon never observes its own container; nothing previously
    // reacted to the browser window (or a split/panel) changing size at all (#62).
    // Debounced, not immediate — see FIT_QUIET_MS.
    this.resizeObserver = new ResizeObserver(() => {
      if (this.pendingFit !== null) {
        clearTimeout(this.pendingFit);
      }
      this.pendingFit = setTimeout(() => {
        this.pendingFit = null;
        // Not gated on `active` any more: a hidden tab is measurable now, so a window
        // resize while it is in the background reaches it immediately rather than
        // waiting for the user to select it (#375).
        this.fitAddon?.fit();
      }, TerminalComponent.FIT_QUIET_MS);
    });
    this.resizeObserver.observe(this.container.nativeElement);
    document.addEventListener('visibilitychange', this.checkConnectionOnForeground);
    window.addEventListener('focus', this.checkConnectionOnForeground);
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['active'] && this.active) {
      // The tab was already open, measured and connected at its real size before this
      // (#375), so there is nothing to size here — this is only about handing the tab
      // the keyboard. Deferred a tick because the `tab-hidden` class is dropped by the
      // parent's own change detection: focus has to land after the element is visible,
      // or the browser drops it.
      setTimeout(() => {
        this.term?.focus();
      });
      // The tab becoming visible is "focus" for attention purposes (#130) -- by now
      // the socket opened long ago (this only fires on a tab *switch*, never on the
      // initial connect), so this reaches the server immediately.
      this.session?.focus();
    }
  }

  ngOnDestroy(): void {
    document.removeEventListener('visibilitychange', this.checkConnectionOnForeground);
    window.removeEventListener('focus', this.checkConnectionOnForeground);
    if (this.pendingFit !== null) {
      clearTimeout(this.pendingFit);
    }
    // Cancelling this matters now that it is what opens the connection: a tab torn
    // down inside the first tick would otherwise leave a socket nothing owns (#268).
    if (this.pendingInit !== null) {
      clearTimeout(this.pendingInit);
    }
    this.resizeObserver?.disconnect();
    this.resizeSub?.dispose();
    this.session?.close();
    this.term?.dispose();
  }

  /**
   * `navigator.clipboard.writeText` alone silently fails the copy chord in two real
   * cases (#350): Safari does not treat a keydown as a sufficient user gesture for a
   * clipboard write, and Chrome rejects when the site's clipboard permission is
   * blocked. Either way, fall back to a synchronous execCommand('copy') rather than
   * swallowing the rejection.
   */
  private copySelection(text: string): void {
    const clipboard = navigator.clipboard;
    if (clipboard?.writeText) {
      clipboard.writeText(text).catch(() => this.copySelectionFallback(text));
    } else {
      this.copySelectionFallback(text);
    }
  }

  private copySelectionFallback(text: string): void {
    const textarea = document.createElement('textarea');
    textarea.value = text;
    // Off-screen but still focusable/selectable -- execCommand('copy') acts on
    // whatever is currently selected, so this element has to actually hold focus
    // and a real selection, not just exist in the DOM.
    textarea.style.position = 'fixed';
    textarea.style.top = '0';
    textarea.style.left = '0';
    textarea.style.opacity = '0';
    document.body.appendChild(textarea);
    textarea.focus();
    textarea.select();
    try {
      if (!document.execCommand('copy')) {
        console.error('Terminal copy failed: execCommand(copy) returned false');
      }
    } catch (err) {
      console.error('Terminal copy failed', err);
    } finally {
      document.body.removeChild(textarea);
    }
  }

  private connect(): void {
    this.session = new TerminalSession(
      this.sessionId,
      this.dir,
      this.cmd,
      this.resume,
      this.term?.cols ?? null,
      this.term?.rows ?? null,
      this.active,
    );
    this.session.connect(
      (text) => this.term?.write(text),
      () => {
        // The session (and its underlying PTY process) is unaffected by this
        // connection closing -- TerminalSession reconnects itself (#279), with
        // checkConnectionOnForeground above nudging it the moment the tab is back.
      },
    );
  }
}
