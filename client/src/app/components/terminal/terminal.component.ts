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

@Component({
  selector: 'app-terminal',
  standalone: true,
  templateUrl: './terminal.component.html',
  styleUrl: './terminal.component.css',
})
export class TerminalComponent implements AfterViewInit, OnChanges, OnDestroy {
  @Input({ required: true }) worktreeId!: string;
  @Input() dir: string | null = null;

  @ViewChild('container', { static: true }) container!: ElementRef<HTMLDivElement>;

  private term: Terminal | null = null;
  private fitAddon: FitAddon | null = null;
  private session: TerminalSession | null = null;
  private viewReady = false;

  ngAfterViewInit(): void {
    this.viewReady = true;
    this.term = new Terminal({
      fontFamily: 'ui-monospace, "SF Mono", Menlo, monospace',
      fontSize: 13,
      theme: { background: '#1c1a17' },
      convertEol: true,
    });
    this.fitAddon = new FitAddon();
    this.term.loadAddon(this.fitAddon);
    this.term.open(this.container.nativeElement);
    this.fitAddon.fit();
    this.term.onData((input) => this.session?.send(input));
    this.connect();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (!this.viewReady) {
      return;
    }
    if (changes['worktreeId'] && !changes['worktreeId'].firstChange) {
      this.term?.clear();
      this.connect();
    }
  }

  ngOnDestroy(): void {
    this.session?.close();
    this.term?.dispose();
  }

  private connect(): void {
    this.session?.close();
    this.session = new TerminalSession(this.worktreeId, this.dir);
    this.session.connect(
      (text) => this.term?.write(text),
      () => {
        // The session (and its underlying PTY process) is unaffected by this
        // connection closing — nothing to do here but note the tab went quiet.
      },
    );
  }
}
