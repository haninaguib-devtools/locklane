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
import { Terminal } from '@xterm/xterm';
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
  /** Whether this tab is the visible one — xterm can only size itself while visible. */
  @Input() active = true;

  @ViewChild('container', { static: true }) container!: ElementRef<HTMLDivElement>;

  private term: Terminal | null = null;
  private fitAddon: FitAddon | null = null;
  private session: TerminalSession | null = null;

  ngAfterViewInit(): void {
    this.term = new Terminal({
      fontFamily: 'ui-monospace, "SF Mono", Menlo, monospace',
      fontSize: 13,
      theme: { background: '#1c1a17' },
      convertEol: true,
    });
    this.fitAddon = new FitAddon();
    this.term.loadAddon(this.fitAddon);
    this.term.open(this.container.nativeElement);
    if (this.active) {
      this.fitAddon.fit();
    }
    this.term.onData((input) => this.session?.send(input));
    this.connect();
  }

  ngOnChanges(changes: SimpleChanges): void {
    // A hidden container has no dimensions, so the fit is deferred to the
    // moment the tab becomes visible again.
    if (changes['active'] && this.active && this.fitAddon) {
      setTimeout(() => this.fitAddon?.fit());
    }
  }

  ngOnDestroy(): void {
    this.session?.close();
    this.term?.dispose();
  }

  private connect(): void {
    this.session = new TerminalSession(this.sessionId, this.dir, this.cmd);
    this.session.connect(
      (text) => this.term?.write(text),
      () => {
        // The session (and its underlying PTY process) is unaffected by this
        // connection closing — nothing to do here but note the tab went quiet.
      },
    );
  }
}
