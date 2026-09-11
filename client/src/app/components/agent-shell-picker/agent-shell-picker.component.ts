import { Component, ElementRef, EventEmitter, HostBinding, HostListener, Input, OnDestroy, Output, ViewChild } from '@angular/core';
import { Agent } from '../../services/agent-store';
import { InstalledAgent } from '../../services/default-agent-store';

/**
 * The agent/Shell "+" picker (#757, #876), shared between the agent session tab
 * strip's own "+" and the sidenav's per-project "+" (#886) rather than each host
 * keeping its own copy of the dropdown's markup and open/close state. A host hands
 * in the choices it knows about and reacts to what got picked; this owns the button,
 * the dropdown, and dismissing it -- a choice, an outside click, Escape, or a scroll
 * that would otherwise leave a fixed-position dropdown drifted from the button that
 * opened it.
 *
 * The dropdown is positioned in the viewport from the button's own rect rather than
 * `position: absolute` in flow (the tab strip's original approach): the sidenav's
 * project list scrolls in its own clipping container (`.sidenav-scroll`), and an
 * in-flow absolute menu there can render partly outside the currently-scrolled
 * viewport (#886). Fixed positioning escapes that regardless of which host embeds it.
 */
@Component({
  selector: 'app-agent-shell-picker',
  standalone: true,
  templateUrl: './agent-shell-picker.component.html',
  styleUrl: './agent-shell-picker.component.css',
})
export class AgentShellPickerComponent implements OnDestroy {
  // The agents to offer alongside Shell, and whether Agent is offered at all (#757,
  // #876) -- the same inputs the tab strip already took for this, now shared.
  @Input() installedAgents: InstalledAgent[] = [];
  @Input() defaultAgent: Agent = '';
  @Input() offerAgent = true;
  // Disables the button -- the tab strip's own "starting" guard; the sidenav never sets this.
  @Input() disabled = false;
  // The button's own content -- '+' normally, '…' while the tab strip's agent
  // session is starting; the sidenav never shows anything but '+'.
  @Input() label = '+';
  @Input() ariaLabel = 'open a new agent or shell';
  @Input() buttonTitle: string | null = null;
  // Which host's own button/dropdown sizing to use (#886) -- both defined in this
  // component's own stylesheet rather than leaking picker CSS back out to either host.
  @Input() variant: 'tab' | 'sidenav' = 'tab';

  @Output() pickAgent = new EventEmitter<Agent>();
  @Output() pickShell = new EventEmitter<void>();
  // Fires whenever the dropdown opens or closes (#886): lets a host close whatever
  // menu of its own -- a tab's overflow menu, a sidenav row's kebab -- was showing,
  // the same mutual exclusion the tab strip already gave its own picker and tab menus.
  @Output() openedChange = new EventEmitter<boolean>();

  // Named to never collide with a plain ".tab"/".sidenav" element elsewhere in the
  // DOM (the agent session tab strip's own tab buttons carry exactly that class) --
  // a raw querySelector, unlike Angular's own view-encapsulated CSS, does not scope
  // by component boundary.
  @HostBinding('class.picker-tab') get isTabVariant(): boolean {
    return this.variant === 'tab';
  }
  @HostBinding('class.picker-sidenav') get isSidenavVariant(): boolean {
    return this.variant === 'sidenav';
  }

  @ViewChild('trigger', { static: true }) private readonly trigger!: ElementRef<HTMLButtonElement>;

  open = false;
  menuTop = 0;
  menuLeft = 0;

  get offersPicker(): boolean {
    return (this.offerAgent ? this.agentChoices.length : 0) + 1 >= 2;
  }

  /**
   * The agent entries the picker offers (#757): the installed agents when any are
   * known, else the Settings default as the single fallback -- or nothing when even
   * that is unknown yet, leaving Shell the only choice.
   */
  get agentChoices(): InstalledAgent[] {
    if (this.installedAgents.length > 0) {
      return this.installedAgents;
    }
    return this.defaultAgent ? [{ id: this.defaultAgent, label: this.defaultAgent }] : [];
  }

  // The "+" button: with a choice to make it opens the dropdown and starts nothing
  // until one is chosen; with Shell the only choice it mints one directly (#876).
  toggle(event?: Event): void {
    event?.stopPropagation();
    if (this.disabled) {
      return;
    }
    if (!this.offersPicker) {
      this.pickShell.emit();
      return;
    }
    if (!this.open) {
      this.positionMenu();
    }
    this.setOpen(!this.open);
  }

  choose(agent: Agent, event: Event): void {
    event.stopPropagation();
    this.setOpen(false);
    this.pickAgent.emit(agent);
  }

  chooseShell(event: Event): void {
    event.stopPropagation();
    this.setOpen(false);
    this.pickShell.emit();
  }

  /** Dismisses the dropdown without choosing anything -- an outside click, Escape, or a scroll. */
  close(): void {
    this.setOpen(false);
  }

  @HostListener('document:click')
  onDocumentClick(): void {
    this.close();
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.close();
  }

  ngOnDestroy(): void {
    document.removeEventListener('scroll', this.onScroll, true);
  }

  // Bound once so add/removeEventListener target the same function reference.
  private readonly onScroll = (): void => this.close();

  private setOpen(value: boolean): void {
    if (this.open === value) {
      return;
    }
    this.open = value;
    // Capture phase (#886): an ancestor's own scroll -- the sidenav's case list --
    // never bubbles to document, so a plain bubble-phase listener would miss it and
    // leave the dropdown visually stranded away from the button that opened it.
    if (value) {
      document.addEventListener('scroll', this.onScroll, true);
    } else {
      document.removeEventListener('scroll', this.onScroll, true);
    }
    this.openedChange.emit(value);
  }

  /**
   * The dropdown's fixed-viewport position, from the button's own rect (#886) --
   * escapes any scrolling ancestor's clipping, unlike the tab strip's original
   * in-flow menu, which never sat inside one. Clamped so a narrow sidebar never
   * pushes it off the right edge of the window. A bare-constructed instance (no
   * real button to measure) leaves the position at its default.
   */
  private positionMenu(): void {
    if (!this.trigger) {
      return;
    }
    const rect = this.trigger.nativeElement.getBoundingClientRect();
    const estimatedWidth = 130;
    this.menuTop = rect.bottom + 2;
    this.menuLeft = Math.max(4, Math.min(rect.left, window.innerWidth - estimatedWidth));
  }
}
