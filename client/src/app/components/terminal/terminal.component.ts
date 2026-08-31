import { HttpErrorResponse } from '@angular/common/http';
import {
  AfterViewInit,
  Component,
  ElementRef,
  Input,
  OnChanges,
  OnDestroy,
  SimpleChanges,
  ViewChild,
  inject,
} from '@angular/core';
import { ClipboardAddon } from '@xterm/addon-clipboard';
import { FitAddon } from '@xterm/addon-fit';
import { IDisposable, Terminal } from '@xterm/xterm';
import { forkJoin } from 'rxjs';
import { SessionUploadsService } from '../../services/session-uploads.service';
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

  /**
   * A transient message about a dropped/pasted file (#436) — an upload refusal (too
   * big, a folder) or failure, surfaced as an overlay instead of failing silently.
   * The PTY's own display is never written to for this: the CLI owns that screen.
   */
  notice: string | null = null;
  private noticeTimer: ReturnType<typeof setTimeout> | null = null;

  private readonly uploadsService = inject(SessionUploadsService);

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

  // A CLI with mouse tracking enabled (claude/codex turn on ?1000h/?1002h/?1003h) owns
  // the selection the user sees, and xterm forwards every button press to it -- so a
  // right-click aimed at the browser's context menu reached the CLI as a mouse press
  // and made it drop that selection (#435). The context menu never needs the PTY to
  // know about the click: swallow button 2 before xterm's own element listener can
  // report it (capture phase on the container fires first; xterm only ever sends the
  // matching release for a press it saw, so suppressing the press suppresses both).
  // Plain shell tabs have no mouse tracking, so right-click there is untouched.
  private readonly suppressRightClickUnderMouseTracking = (event: MouseEvent): void => {
    if (event.button === 2 && this.term != null && this.term.modes.mouseTrackingMode !== 'none') {
      event.stopPropagation();
    }
  };

  // Dropping a file on a native terminal types its path; here the CLI runs on the
  // server while the file lives on the browser's machine, and a page only ever gets
  // the file's *contents* -- so the bytes are uploaded first and the server-side
  // path is what gets pasted (#436). Default-prevented only when the drag actually
  // carries files, so the browser never navigates to a dropped file while any other
  // kind of drag keeps today's behavior untouched.
  private readonly allowFileDrop = (event: DragEvent): void => {
    if (event.dataTransfer?.types.includes('Files')) {
      event.preventDefault();
      event.dataTransfer.dropEffect = 'copy';
    }
  };

  private readonly uploadDroppedFiles = (event: DragEvent): void => {
    const transfer = event.dataTransfer;
    if (!transfer || !transfer.types.includes('Files')) {
      return;
    }
    event.preventDefault();
    const files: File[] = [];
    let droppedFolder = false;
    for (const item of Array.from(transfer.items)) {
      if (item.kind !== 'file') {
        continue;
      }
      // The only reliable folder test a drop offers: a directory still yields a
      // File object (empty, OS-dependent), so File itself can't be trusted here.
      if (item.webkitGetAsEntry?.()?.isDirectory) {
        droppedFolder = true;
        continue;
      }
      const file = item.getAsFile();
      if (file) {
        files.push(file);
      }
    }
    if (droppedFolder) {
      this.showNotice('Folders cannot be dropped here — drop individual files instead.');
    }
    this.uploadAndPastePaths(files);
  };

  // Capture phase on the container, same trick as the right-click suppression
  // above: it runs before xterm's own paste handling on its textarea, but only a
  // paste actually carrying files is intercepted -- a text paste falls through to
  // xterm completely untouched (#436).
  private readonly uploadPastedFiles = (event: ClipboardEvent): void => {
    const files = event.clipboardData?.files;
    if (!files || files.length === 0) {
      return;
    }
    event.preventDefault();
    event.stopPropagation();
    this.uploadAndPastePaths(Array.from(files));
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
    // OSC 52 is how a CLI running in the PTY (claude's own Ctrl+C copy, tmux, vim)
    // puts text on the clipboard -- the server has no clipboard tool, so this escape
    // sequence is its only route out, and without a handler xterm drops it silently
    // (#435). Writes go through copyToClipboard rather than the addon's default
    // provider: a plain navigator.clipboard.writeText rejection (Safari gives no
    // user-gesture credit to server output) would vanish as an unhandled promise,
    // while copyToClipboard falls back to execCommand and logs a failure. Reads are
    // declined with an empty report: OSC 52 read would let any PTY application
    // silently exfiltrate the user's clipboard, which is why most terminals ship
    // with it off.
    this.term.loadAddon(
      new ClipboardAddon(undefined, {
        readText: () => '',
        writeText: (_selection, text) => this.copyToClipboard(text),
      }),
    );
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
        this.copyToClipboard(this.term.getSelection());
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
    // Capture phase: this has to run before the mousedown reaches xterm's own
    // element, whose "always on" listener is what reports the press to the PTY.
    this.container.nativeElement.addEventListener('mousedown', this.suppressRightClickUnderMouseTracking, true);
    // File drag-and-drop and image paste (#436). The paste listener is capture-phase
    // -- see uploadPastedFiles; dragover must be default-prevented for the drop
    // event to fire at all, and both are gated on the drag carrying files.
    this.container.nativeElement.addEventListener('dragover', this.allowFileDrop);
    this.container.nativeElement.addEventListener('drop', this.uploadDroppedFiles);
    this.container.nativeElement.addEventListener('paste', this.uploadPastedFiles, true);
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
    this.container.nativeElement.removeEventListener('mousedown', this.suppressRightClickUnderMouseTracking, true);
    this.container.nativeElement.removeEventListener('dragover', this.allowFileDrop);
    this.container.nativeElement.removeEventListener('drop', this.uploadDroppedFiles);
    this.container.nativeElement.removeEventListener('paste', this.uploadPastedFiles, true);
    document.removeEventListener('visibilitychange', this.checkConnectionOnForeground);
    window.removeEventListener('focus', this.checkConnectionOnForeground);
    if (this.noticeTimer !== null) {
      clearTimeout(this.noticeTimer);
    }
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
   * `navigator.clipboard.writeText` alone silently fails in two real cases (#350):
   * Safari does not treat a keydown -- let alone an OSC 52 arriving with server
   * output (#435) -- as a sufficient user gesture for a clipboard write, and Chrome
   * rejects when the site's clipboard permission is blocked. Either way, fall back
   * to a synchronous execCommand('copy') rather than swallowing the rejection.
   * Serves both copy paths: the Cmd/Ctrl+C chord on an xterm selection, and an
   * OSC 52 write from the PTY application via the clipboard addon.
   */
  private copyToClipboard(text: string): void {
    const clipboard = navigator.clipboard;
    if (clipboard?.writeText) {
      clipboard.writeText(text).catch(() => this.copyFallback(text));
    } else {
      this.copyFallback(text);
    }
  }

  private copyFallback(text: string): void {
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

  /**
   * Uploads each file and pastes the server-side paths through xterm's own paste()
   * — the same bracketed-paste transformation keyboard paste gets (#436), so a CLI
   * with paste mode on sees one atomic paste, exactly like a path typed by a
   * native terminal's drag-and-drop. A trailing space matches that native behavior
   * and leaves the prompt ready for Enter.
   */
  private uploadAndPastePaths(files: File[]): void {
    if (files.length === 0) {
      return;
    }
    forkJoin(files.map((file) => this.uploadsService.upload(this.sessionId, file))).subscribe({
      next: (results) => this.term?.paste(results.map((result) => result.path).join(' ') + ' '),
      error: (err) => this.showNotice(uploadFailureMessage(err)),
    });
  }

  private showNotice(text: string): void {
    this.notice = text;
    if (this.noticeTimer !== null) {
      clearTimeout(this.noticeTimer);
    }
    this.noticeTimer = setTimeout(() => {
      this.noticeTimer = null;
      this.notice = null;
    }, TerminalComponent.NOTICE_VISIBLE_MS);
  }

  private static readonly NOTICE_VISIBLE_MS = 6000;

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

/**
 * The engine's own refusal wording when it sent one (#436: the size cap, an
 * invalid session) — that message says why, so it beats anything composed here —
 * else a generic failure line for a network error or an errorless response.
 */
function uploadFailureMessage(err: unknown): string {
  if (err instanceof HttpErrorResponse) {
    const serverMessage = (err.error as { error?: unknown } | null)?.error;
    if (typeof serverMessage === 'string' && serverMessage.length > 0) {
      return serverMessage;
    }
  }
  return 'File upload failed — nothing was delivered to the session.';
}
