// Mirrors dev.locklane.engine.usage.WindowUsage.
export interface WindowUsage {
  percentLeft: number;
  resetsAt: string;
}

// Mirrors dev.locklane.engine.usage.ProviderUsage.
export interface ProviderUsage {
  available: boolean;
  fiveHour: WindowUsage | null;
  weekly: WindowUsage | null;
}

// Mirrors dev.locklane.engine.usage.UsageSnapshot.
export interface UsageSnapshot {
  claude: ProviderUsage;
  codex: ProviderUsage;
  updatedAt: string;
}
