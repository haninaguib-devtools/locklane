import { Component, ElementRef, EventEmitter, HostListener, Input, OnInit, Optional, Output, ViewChild } from '@angular/core';
import { Agent } from '../../services/agent-store';
import { AttentionStore } from '../../services/attention-store';
import { InstalledAgent } from '../../services/default-agent-store';
import { CODE_SERVER_IDE, DefaultIdeStore, InstalledIde } from '../../services/default-ide-store';
import { ConfirmDialogComponent } from '../confirm-dialog/confirm-dialog.component';
import { AgentSessionsService } from '../../services/agent-sessions.service';
import { AgentSessionTab, OVERVIEW_TAB_ID, tabText } from './agent-session-labels';
import { AgentShellPickerComponent } from '../agent-shell-picker/agent-shell-picker.component';

export interface OpenAgentSessionRequest {
  agent: Agent;
}

/** A tab renamed in place (#393): an empty `name` means "clear it". */
export interface RenameAgentSessionRequest {
  id: string;
  name: string;
}

@Component({
  selector: 'app-agent-session-tabs',
  standalone: true,
  imports: [ConfirmDialogComponent, AgentShellPickerComponent],
  templateUrl: './agent-session-tabs.component.html',
  styleUrl: './agent-session-tabs.component.css',
})
export class AgentSessionTabsComponent implements OnInit {
  // @Optional() constructor parameters, not inject() fields, deliberately (#447):
  // the existing unit tests instantiate this component with bare
  // `new AgentSessionTabsComponent()` (the defaults cover that), and other component
  // suites render this strip under TestBeds with no HttpClient provider —
  // @Optional() lets DI hand in null there instead of erroring.
  // The attention store (#791) follows the same rule: a null store means no tab is
  // ever waiting, so the strip renders its dots plain blue.
  constructor(
    @Optional() private readonly agentSessionsService: AgentSessionsService | null = null,
    @Optional() private readonly defaultIdeStore: DefaultIdeStore | null = null,
    @Optional() private readonly attentionStore: AttentionStore | null = null,
  ) {}

  // #782: a browser that never chose an IDE in Settings acts on code-server whatever
  // is installed, so there is nothing to look up; one that did needs the engine's
  // installed set to know whether that choice still holds (DefaultIdeStore.effective).
  // Fetched once per app load, from whichever strip or dialog asks first.
  ngOnInit(): void {
    if (this.defaultIdeStore !== null && this.defaultIdeStore.ide() !== '') {
      this.defaultIdeStore.refreshInstalled();
    }
  }

  // Exposed for the template's Overview tab, pinned first in the same strip (#96).
  readonly overviewId = OVERVIEW_TAB_ID;
  // Exposed for the template: the user's own name for a tab, or its auto label (#393).
  readonly tabText = tabText;

  @Input() tabs: AgentSessionTab[] = [];
  @Input() selected: string | null = null;
  @Input() starting = false;
  // The issue page pins an Overview tab first; the project-agent-session page (#178)
  // has none — every agent session there is its own top-level tab.
  @Input() overview = true;
  // Read from Settings (#219) by the caller. The open button launches with this
  // agent directly whenever there is nothing to choose between (#757: fewer than two
  // installed agents known) -- the issue page's Agent button (#318) and the
  // project-agent-session tab strip's "+" (#256) alike.
  @Input() defaultAgent: Agent = '';
  // #757: the agents the engine detected on its host PATH, as the Settings dialog's
  // "Default agent" section lists them (`DefaultAgentStore.installed()`), bound by the
  // host rather than injected here so the strip still constructs bare in its own
  // specs. Together with the Shell entry (#876) they decide whether "+" asks or
  // launches directly; with no installed agent known the default agent (#219)
  // stands in as the single agent choice.
  @Input() installedAgents: InstalledAgent[] = [];
  // Whether the Agent choice is offered at all (#876): the project page always
  // offers it, while the issue page suppresses it once its worktree session is
  // live -- picking it would only reselect what the strip already shows, so Shell
  // is the only choice left and "+" launches one directly.
  @Input() offerAgent = true;
  // #393: only the project agent session page lets a user name its tabs; the issue page
  // keeps the auto labels, so renaming is opt-in per call site rather than on
  // everywhere the shared strip is used. Shell tabs never rename -- the engine
  // exposes no shell rename -- even where renaming is on.
  @Input() renamable = false;
  @Output() selectedChange = new EventEmitter<string>();
  @Output() open = new EventEmitter<OpenAgentSessionRequest>();
  /** A shell tab asked for (#876): the page mints it at its own directory. */
  @Output() openShell = new EventEmitter<void>();
  @Output() close = new EventEmitter<string>();
  @Output() rename = new EventEmitter<RenameAgentSessionRequest>();
  // #441: reveals a tab's worktree in the OS's file manager. No confirmation needed
  // (unlike close) -- it can't lose anything.
  @Output() reveal = new EventEmitter<string>();

  // The longest name accepted, mirroring the engine's own bound (#393) so an
  // over-long name is prevented here rather than rejected after a round trip.
  readonly maxNameLength = 60;

  // The tab currently being renamed, and the text in its field. Only one tab is
  // ever editable at a time -- starting a rename elsewhere commits nothing and
  // simply moves the field.
  renamingId: string | null = null;
  draftName = '';

  // The rename field only exists while a tab is being renamed, so this setter runs
  // exactly when it appears -- the moment to put the cursor in it. `autofocus` does
  // nothing here: the attribute is only honoured when the document itself loads, not
  // when an element is added to a page that is already showing.
  @ViewChild('nameInput')
  set nameInput(input: ElementRef<HTMLInputElement> | undefined) {
    if (input) {
      input.nativeElement.focus();
      input.nativeElement.select();
    }
  }

  // The tab whose close is awaiting confirmation in the app-styled dialog (#231),
  // replacing the synchronous native `confirm()` this used to block on.
  pendingCloseId: string | null = null;

  select(id: string): void {
    this.selectedChange.emit(id);
  }

  /**
   * Whether this tab's agent is waiting for the user (#130, #791): a tab's id is the
   * agent's session id, the same id the shared store keys by, so no lookup is needed.
   * A signal read underneath, so the dot follows the store as events land. Selecting
   * the tab focuses the session engine-side, which clears the state on its own.
   */
  isWaiting(id: string): boolean {
    return this.attentionStore?.isWaiting(id) ?? false;
  }

  /** The tab button's tooltip: the waiting state first, else the rename hint where renaming is on (#393, never on a shell tab). */
  tabTitle(id: string): string | null {
    if (this.isWaiting(id)) {
      return 'Waiting for you';
    }
    if (!this.renamable || this.tabs.some((tab) => tab.id === id && tab.kind === 'shell')) {
      return null;
    }
    return 'double-click to rename';
  }

  /**
   * Double-clicking a tab turns its label into a field (#393), seeded with the name
   * the user already gave it -- never with the auto-generated label, so committing
   * an untouched field on a never-named tab is not a rename to the label's text.
   * Shell tabs never rename (#876), so double-clicking one does nothing.
   */
  startRename(tab: AgentSessionTab, event: Event): void {
    if (!this.renamable || tab.kind === 'shell') {
      return;
    }
    event.stopPropagation();
    this.renamingId = tab.id;
    this.draftName = tab.name ?? '';
  }

  /** Commits the field, trimmed; an empty result clears the name (#393). */
  commitRename(): void {
    const id = this.renamingId;
    if (id === null) {
      return;
    }
    this.renamingId = null;
    this.rename.emit({ id, name: this.draftName.trim() });
  }

  /** Abandons the field, changing nothing. */
  cancelRename(): void {
    this.renamingId = null;
    this.draftName = '';
  }

  onRenameInput(value: string): void {
    this.draftName = value;
  }

  // The tab whose overflow menu is open, or null when none is (#480). At most one is
  // ever open at a time -- opening another closes whichever was open, matching the
  // sidenav's own kebab menu (isMenuOpen/toggleMenu below).
  openMenuId: string | null = null;

  isMenuOpen(id: string): boolean {
    return this.openMenuId === id;
  }

  // Revealing a worktree in the OS file manager only makes sense against a local
  // install (#497) -- an engine reached over the network has no file manager to open.
  get isLocalHost(): boolean {
    return this.currentHostname() === 'localhost';
  }

  // Indirection for testability (#497): most browsers refuse to let a spy override
  // window.location.hostname, since it is not a configurable property.
  protected currentHostname(): string {
    return window.location.hostname;
  }

  toggleMenu(id: string, event: Event): void {
    event.stopPropagation();
    this.openMenuId = this.openMenuId === id ? null : id;
    // At most one dropdown at a time: opening a tab menu closes the agent/Shell
    // picker (#757), just as opening the picker closes any tab menu (see
    // onPickerOpenedChange below).
    this.picker?.close();
  }

  // Closes any open menu on a click anywhere else in the document -- the same
  // outside-click convention the sidenav's kebab menu already uses. A click on the
  // trigger or a menu item stops propagation before this fires, so it never fights
  // the toggle above. The picker (#886) closes itself on an outside click.
  @HostListener('document:click')
  closeMenu(): void {
    this.openMenuId = null;
  }

  closeTab(id: string, event: Event): void {
    event.stopPropagation();
    this.openMenuId = null;
    this.pendingCloseId = id;
  }

  revealTab(id: string, event: Event): void {
    event.stopPropagation();
    this.openMenuId = null;
    this.reveal.emit(id);
  }

  // Whether the tab awaiting close confirmation is a shell (#876): the dialog
  // names what it is about to end.
  get pendingCloseIsShell(): boolean {
    return this.tabs.some((tab) => tab.id === this.pendingCloseId && tab.kind === 'shell');
  }

  get closeTitle(): string {
    return this.pendingCloseIsShell ? 'Close shell?' : 'Close agent?';
  }

  get closeMessage(): string {
    return this.pendingCloseIsShell
      ? 'This ends the shell session for good — the process is killed and its scrollback is gone.'
      : 'Close this agent? It will be terminated and cannot be reattached.';
  }

  // Whether the last "Open IDE" attempt failed (#628) -- cleared on the next one.
  ideOpenFailed = false;

  // The IDE "Open IDE" acts on (#782): the Settings choice when this browser may use
  // it, else code-server -- and code-server outright when the strip was built without
  // the store (bare construction in specs).
  get effectiveIde(): InstalledIde {
    return this.defaultIdeStore?.effective() ?? CODE_SERVER_IDE;
  }

  // The menu item names a desktop choice -- `Open in VS Code`, `Open in IntelliJ IDEA`
  // -- and stays `Open IDE` for code-server (#782).
  get ideLabel(): string {
    const ide = this.effectiveIde;
    return ide.desktop ? `Open in ${ide.label}` : 'Open IDE';
  }

  /**
   * The "Open IDE" menu item (#627/#628, #782): asks the engine to open this tab's
   * worktree in {@link effectiveIde}. For code-server that starts (or reuses) its
   * process and the returned URL opens in a singleton browser tab -- mint/reuse the
   * session server-side, then `window.open` it, never a path sent from here. For a
   * desktop IDE the engine launches the editor on its own host and returns no URL,
   * so nothing opens here. Offered on every host, unlike Folder (#655): away from
   * localhost the effective choice is always code-server, whose URL is the engine's
   * own proxied path, so it works wherever this page itself was reached from.
   */
  openIdeAt(tab: AgentSessionTab, event: Event): void {
    event.stopPropagation();
    this.openMenuId = null;
    const agentSessions = this.agentSessionsService;
    const projectId = projectIdOf(tab.id);
    if (agentSessions === null || projectId === null) {
      return;
    }
    this.ideOpenFailed = false;
    agentSessions.openIde(projectId, tab.id, this.effectiveIde.id).subscribe({
      next: (opened) => {
        if (opened.url !== null) {
          window.open(opened.url, 'locklane-ide');
        }
      },
      error: () => (this.ideOpenFailed = true),
    });
  }

  confirmClose(): void {
    const id = this.pendingCloseId;
    this.pendingCloseId = null;
    if (id !== null) {
      this.close.emit(id);
    }
  }

  cancelClose(): void {
    this.pendingCloseId = null;
  }

  // The shared agent/Shell picker under the "+" button (#757, #876, #886): its own
  // markup, open/close state, outside-click and Escape handling live in
  // AgentShellPickerComponent, shared with the sidenav's per-project "+" rather than
  // duplicated here.
  @ViewChild(AgentShellPickerComponent) picker?: AgentShellPickerComponent;

  // A picker entry (#757): starts the agent session with exactly that agent.
  onPickAgent(agent: Agent): void {
    this.open.emit({ agent });
  }

  // The picker's Shell entry (#876): the page mints a shell at its own directory.
  onPickShell(): void {
    this.openShell.emit();
  }

  // Mutual exclusion with a tab's own overflow menu (#757): opening the picker
  // closes whichever tab menu was open, the counterpart to toggleMenu closing the
  // picker above.
  onPickerOpenedChange(opened: boolean): void {
    if (opened) {
      this.openMenuId = null;
    }
  }
}

/** The leading numeric segment every real session id starts with (`<projectId>-…`). */
function projectIdOf(sessionId: string): number | null {
  const match = /^(\d+)-/.exec(sessionId);
  return match ? Number(match[1]) : null;
}
