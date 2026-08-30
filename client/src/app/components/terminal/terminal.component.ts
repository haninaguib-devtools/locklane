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
  /** Whether this tab is the visible one — xterm can only size itself while visible. */
  @Input() active = true;

  @ViewChild('container', { static: true }) container!: ElementRef<HTMLDivElement>;

  private term: Terminal | null = null;
  private fitAddon: FitAddon | null = null;
  private session: TerminalSession | null = null;
  private resizeSub: IDisposable | null = null;
  private resizeObserver: ResizeObserver | null = null;
  private pendingFit: ReturnType<typeof setTimeout> | null = null;
  /** The deferred first measure-then-connect for an initially-active tab (#268). */
  private pendingInit: ReturnType<typeof setTimeout> | null = null;
  /** Whether term.open() has run yet — deferred for a tab that starts inactive (#211). */
  private opened = false;

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
    // A hidden (display:none) container measures as 0x0, and xterm caches whatever
    // character size it sees at open() time — a later fit() against that cache never
    // corrects it. Only open a tab that's actually visible; an inactive one is opened
    // on its first activation instead (ngOnChanges), once it has real dimensions (#211).
    if (this.active) {
      this.term.open(this.container.nativeElement);
      this.opened = true;
      // The container may not have its final layout size yet at this point in the
      // change-detection cycle — same reasoning as the tab-switch fit below (#211),
      // deferred here too so an initially-active tab doesn't lock in a bad cached
      // size the way #257 did. The connection waits for that measurement rather than
      // racing it: the size on the connect URL is the size the engine starts the new
      // PTY at, so connecting first would start the process at xterm's 80x24
      // constructor defaults and never correct it (#268).
      this.pendingInit = setTimeout(() => {
        this.pendingInit = null;
        this.fitAddon?.fit();
        this.term?.focus();
        this.connect();
      });
    }
    this.term.onData((input) => this.session?.send(input));
    // Fires for every size xterm settles on — the initial fit above, a later
    // tab-becomes-active fit, and the ResizeObserver-driven fit below — so the
    // server's PTY is told every time, not just once at connect.
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
        if (this.active) {
          this.fitAddon?.fit();
        }
      }, TerminalComponent.FIT_QUIET_MS);
    });
    this.resizeObserver.observe(this.container.nativeElement);
    if (!this.active) {
      // A tab that starts hidden has nothing measurable to wait for, so it connects
      // straight away at xterm's defaults; the fit on its first activation emits the
      // real size, which TerminalSession holds until the socket opens if the two
      // happen to race (#268).
      this.connect();
    }
    document.addEventListener('visibilitychange', this.checkConnectionOnForeground);
    window.addEventListener('focus', this.checkConnectionOnForeground);
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['active'] && this.active) {
      if (this.term && !this.opened) {
        // First time this tab is shown: the container is visible now, so this is
        // xterm's first real (non-zero) measurement (#211).
        this.term.open(this.container.nativeElement);
        this.opened = true;
      }
      // A hidden container has no dimensions, so the fit is deferred to the
      // moment the tab becomes visible again.
      if (this.fitAddon) {
        setTimeout(() => {
          this.fitAddon?.fit();
          // The container has to be visible (not `display: none` via tab-hidden)
          // for browser keyboard focus to actually land, hence the same setTimeout.
          this.term?.focus();
        });
      }
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
