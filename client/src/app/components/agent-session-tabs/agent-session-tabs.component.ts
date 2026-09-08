import { Component, ElementRef, EventEmitter, HostListener, Input, OnInit, Optional, Output, ViewChild } from '@angular/core';
import { Observable, map, of, switchMap } from 'rxjs';
import { Agent } from '../../services/agent-store';
import { AttentionStore } from '../../services/attention-store';
import { InstalledAgent } from '../../services/default-agent-store';
import { CODE_SERVER_IDE, DefaultIdeStore, InstalledIde } from '../../services/default-ide-store';
import { ConfirmDialogComponent } from '../confirm-dialog/confirm-dialog.component';
import { AgentSessionsService, issueNumberFromSessionId } from '../../services/agent-sessions.service';
import { ShellsService } from '../../services/shells.service';
import { WorktreesService } from '../../services/worktrees.service';
import { AgentSessionTab, OVERVIEW_TAB_ID, tabText } from './agent-session-labels';

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
  imports: [ConfirmDialogComponent],
  templateUrl: './agent-session-tabs.component.html',
  styleUrl: './agent-session-tabs.component.css',
})
export class AgentSessionTabsComponent implements OnInit {
  // @Optional() constructor parameters, not inject() fields, deliberately (#447):
  // the existing unit tests instantiate this component with bare
  // `new AgentSessionTabsComponent()` (the defaults cover that), and other component
  // suites render this strip under TestBeds with no HttpClient provider —
  // @Optional() lets DI hand in null there instead of erroring, and the
  // open-shell control simply no-ops without its services.
  // The attention store (#791) follows the same rule: a null store means no tab is
  // ever waiting, so the strip renders its dots plain blue.
  constructor(
    @Optional() private readonly shellsService: ShellsService | null = null,
    @Optional() private readonly worktreesService: WorktreesService | null = null,
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
  // specs. Two or more turn the open button into a picker; one is launched directly;
  // none (the fetch not resolved yet, or nothing installed) falls back to
  // `defaultAgent`, exactly the pre-#757 behaviour.
  @Input() installedAgents: InstalledAgent[] = [];
  // The label on the open button — "+" everywhere except the issue page (#318),
  // which spells it out as "Agent" now that it launches one specific thing.
  @Input() openLabel = '+';
  // The issue page (#318): a live agent session already ties the button's job (open
  // *the* worktree agent session for this issue) to state that's visible in the tab
  // strip itself, so the button hides rather than sitting there doing nothing
  // useful. The project-agent-session strip keeps "+" visible to start more agent sessions.
  @Input() hideOpenWhenActive = false;
  // #393: only the project agent session page lets a user name its tabs; the issue page
  // keeps the auto labels, so renaming is opt-in per call site rather than on
  // everywhere the shared strip is used.
  @Input() renamable = false;
  @Output() selectedChange = new EventEmitter<string>();
  @Output() open = new EventEmitter<OpenAgentSessionRequest>();
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

  /** The tab button's tooltip: the waiting state first, else the rename hint where renaming is on (#393). */
  tabTitle(id: string): string | null {
    if (this.isWaiting(id)) {
      return 'Waiting for you';
    }
    return this.renamable ? 'double-click to rename' : null;
  }

  /**
   * Double-clicking a tab turns its label into a field (#393), seeded with the name
   * the user already gave it -- never with the auto-generated label, so committing
   * an untouched field on a never-named tab is not a rename to the label's text.
   */
  startRename(tab: AgentSessionTab, event: Event): void {
    if (!this.renamable) {
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
    // At most one dropdown at a time: opening a tab menu closes the agent picker
    // (#757), just as opening the picker closes any tab menu.
    this.pickerOpen = false;
  }

  // Closes any open menu on a click anywhere else in the document -- the same
  // outside-click convention the sidenav's kebab menu already uses. A click on the
  // trigger or a menu item stops propagation before this fires, so it never fights
  // the toggle above.
  @HostListener('document:click')
  closeMenu(): void {
    this.openMenuId = null;
    this.pickerOpen = false;
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

  // Whether the last open-shell attempt failed (#447) — cleared on the next one.
  shellOpenFailed = false;

  /**
   * The hover-revealed shell icon (#447): mints a brand-new shell session at this
   * tab's worktree — never a reuse, every click another shell — then opens/focuses
   * the singleton Shells window on it. The owning project id is parsed from the
   * tab's own session id (`<projectId>-…`, the same convention the engine keys
   * authorization and broadcasts on).
   */
  openShellAt(tab: AgentSessionTab, event: Event): void {
    event.stopPropagation();
    this.openMenuId = null;
    const shells = this.shellsService;
    const projectId = projectIdOf(tab.id);
    if (shells === null || this.worktreesService === null || projectId === null) {
      return;
    }
    this.shellOpenFailed = false;
    this.directoryOf(projectId, tab)
      .pipe(switchMap((dir) => shells.open(projectId, issueNumberFromSessionId(tab.id), dir)))
      .subscribe({
        next: (created) => {
          // The initiative's singleton convention (#444): a repeated open with the
          // same window name navigates and focuses the existing window.
          window.open(`/shells/${created.sessionId}`, 'locklane-shells');
        },
        error: () => (this.shellOpenFailed = true),
      });
  }

  /**
   * Where this tab's agent session actually runs: its own `dir` when the caller carried
   * it (the project-agent-session page does), otherwise the project worktree list — a
   * read-only lookup — first by the tab's exact session id, then by its issue
   * number, since an issue has one worktree. Errors when neither matches, which
   * the open-shell subscriber surfaces as {@link #shellOpenFailed}.
   */
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
   * process and the returned URL opens in a singleton browser tab -- the same shape
   * as {@link openShellAt}: mint/reuse the session server-side, then `window.open`
   * it, never a path sent from here. For a desktop IDE the engine launches the editor
   * on its own host and returns no URL, so nothing opens here. Offered on every host,
   * unlike Folder (#655): away from localhost the effective choice is always
   * code-server, whose URL is the engine's own proxied path, so it works wherever this
   * page itself was reached from.
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

  private directoryOf(projectId: number, tab: AgentSessionTab): Observable<string> {
    if (tab.dir) {
      return of(tab.dir);
    }
    return this.worktreesService!.list(projectId).pipe(
      map((rows) => {
        const own = rows.find((row) => row.worktreeId === tab.id);
        if (own) {
          return own.workingDirectory;
        }
        const issue = issueNumberFromSessionId(tab.id);
        const byIssue = issue !== null ? rows.find((row) => row.issueNumber === issue) : undefined;
        if (byIssue) {
          return byIssue.workingDirectory;
        }
        throw new Error(`no worktree directory known for agent '${tab.id}'`);
      }),
    );
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

  // Whether the open button renders at all — hidden once the issue page (#318)
  // already has a live agent session for this issue, since the button's whole job is
  // opening/reusing that one agent session and the tab strip already shows it is open.
  get showOpenButton(): boolean {
    return !this.hideOpenWhenActive || this.tabs.length === 0;
  }

  // Whether the agent picker under the open button is showing (#757). Closed by a
  // choice, an outside click (closeMenu above), or Escape (closePicker below).
  pickerOpen = false;

  // Whether the open button has anything to ask (#757): two or more installed
  // agents. With one or none there is no choice to offer, so it launches directly.
  get offersPicker(): boolean {
    return this.installedAgents.length >= 2;
  }

  // The "+" / "Agent" button — the issue page's Agent button (#318), or the
  // project-agent-session strip's own scratch worktree (#256/#314). Where an agent session runs is
  // settled (#341 retired the only other place, the project's main checkout); which
  // agent runs in it is the one question left (#757): with two or more installed
  // agents the button opens a picker and starts nothing until one is chosen; exactly
  // one installed agent is launched as is; none known falls back to the default.
  plusClicked(event?: Event): void {
    event?.stopPropagation();
    if (this.offersPicker) {
      this.openMenuId = null;
      this.pickerOpen = !this.pickerOpen;
      return;
    }
    const only = this.installedAgents[0];
    this.open.emit({ agent: only ? only.id : this.defaultAgent });
  }

  // A picker entry (#757): starts the agent session with exactly that agent.
  pickAgent(agent: Agent, event: Event): void {
    event.stopPropagation();
    this.pickerOpen = false;
    this.open.emit({ agent });
  }

  // Escape dismisses the picker without starting anything (#757).
  @HostListener('document:keydown.escape')
  closePicker(): void {
    this.pickerOpen = false;
  }
}

/** The leading numeric segment every real session id starts with (`<projectId>-…`). */
function projectIdOf(sessionId: string): number | null {
  const match = /^(\d+)-/.exec(sessionId);
  return match ? Number(match[1]) : null;
}
