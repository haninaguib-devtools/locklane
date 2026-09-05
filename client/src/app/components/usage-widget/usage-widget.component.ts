import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { UsageService } from '../../services/usage.service';
import { ProviderSnapshot, UsageSnapshot, WindowUsage } from '../../models/usage.model';

/** How often the widget re-polls; the engine's own cache (#137's Goal) keeps this cheap. */
const POLL_MS = 60_000;

/** Below this remaining percentage a bar's color shifts toward the accent (#137's Goal, closed-state description). */
const LOW_PERCENT_LEFT = 20;

@Component({
  selector: 'app-usage-widget',
  standalone: true,
  templateUrl: './usage-widget.component.html',
  styleUrl: './usage-widget.component.css',
})
export class UsageWidgetComponent implements OnInit, OnDestroy {
  private readonly usageService = inject(UsageService);

  snapshot: UsageSnapshot | null = null;
  expanded = false;

  private pollTimer: ReturnType<typeof setTimeout> | null = null;

  ngOnInit(): void {
    this.load();
  }

  ngOnDestroy(): void {
    if (this.pollTimer !== null) {
      clearTimeout(this.pollTimer);
    }
  }

  toggle(): void {
    this.expanded = !this.expanded;
  }

  hasAnyProvider(snapshot: UsageSnapshot): boolean {
    return snapshot.providers.some((provider) => provider.usage.available);
  }

  // The collapsed row's mini bar has room for one number per provider -- the 5-hour
  // window leads since it is the one that actually gates a session; the weekly window
  // only shows once expanded.
  barPercentUsed(provider: ProviderSnapshot): number {
    return this.percentUsed(provider.usage.fiveHour ?? provider.usage.weekly);
  }

  isLow(provider: ProviderSnapshot): boolean {
    return this.isWindowLow(provider.usage.fiveHour ?? provider.usage.weekly);
  }

  isWindowLow(window: WindowUsage | null): boolean {
    return window !== null && window.percentLeft < LOW_PERCENT_LEFT;
  }

  // One row per window a provider actually has data for, in display order -- the
  // account-wide 5-hour and weekly windows first, then one row per model that has its
  // own scoped weekly limit (e.g. "Fable"), sourced generically from the list so a
  // future scoped model shows up without a template change.
  providerWindows(provider: ProviderSnapshot): { label: string; window: WindowUsage }[] {
    const rows: { label: string; window: WindowUsage }[] = [];
    if (provider.usage.fiveHour) {
      rows.push({ label: '5-hour limit', window: provider.usage.fiveHour });
    }
    if (provider.usage.weekly) {
      rows.push({ label: 'Weekly', window: provider.usage.weekly });
    }
    for (const limit of provider.usage.modelWeeklyLimits) {
      rows.push({ label: `${limit.modelName} weekly`, window: limit.window });
    }
    return rows;
  }

  percentUsed(window: WindowUsage | null): number {
    return window ? 100 - window.percentLeft : 0;
  }

  usedLabel(window: WindowUsage | null): string {
    return window ? `${Math.round(this.percentUsed(window))}% used` : '—';
  }

  resetLabel(window: WindowUsage | null): string {
    return window ? `resets ${this.relativeTime(window.resetsAt)}` : '';
  }

  updatedLabel(snapshot: UsageSnapshot): string {
    const minutes = Math.max(0, Math.round((Date.now() - new Date(snapshot.updatedAt).getTime()) / 60_000));
    return minutes === 0 ? 'updated just now' : `updated ${minutes} min ago`;
  }

  private load(): void {
    this.usageService.snapshot().subscribe({
      next: (snapshot) => {
        this.snapshot = snapshot;
        this.schedulePoll();
      },
      // A failed request to our own engine (as opposed to a provider outage, which the
      // engine already turns into `available: false`) just tries again on the next
      // poll -- the widget stays hidden in the meantime with no stale snapshot shown.
      error: () => this.schedulePoll(),
    });
  }

  private schedulePoll(): void {
    this.pollTimer = setTimeout(() => this.load(), POLL_MS);
  }

  private relativeTime(iso: string): string {
    const minutes = Math.round((new Date(iso).getTime() - Date.now()) / 60_000);
    if (minutes <= 0) {
      return 'soon';
    }
    if (minutes < 60) {
      return `in ${minutes}m`;
    }
    const hours = Math.round(minutes / 60);
    if (hours < 24) {
      return `in ${hours}h`;
    }
    return `in ${Math.round(hours / 24)}d`;
  }
}
