// Mirrors dev.locklane.engine.usage.WindowUsage.
export interface WindowUsage {
  percentLeft: number;
  resetsAt: string;
}

// Mirrors dev.locklane.engine.usage.ModelWeeklyLimit.
export interface ModelWeeklyLimit {
  modelName: string;
  window: WindowUsage;
}

// Mirrors dev.locklane.engine.usage.ProviderUsage.
export interface ProviderUsage {
  available: boolean;
  fiveHour: WindowUsage | null;
  weekly: WindowUsage | null;
  modelWeeklyLimits: ModelWeeklyLimit[];
}

// Mirrors dev.locklane.engine.usage.UsageSnapshot.
export interface UsageSnapshot {
  claude: ProviderUsage;
  codex: ProviderUsage;
  opencode: ProviderUsage;
  updatedAt: string;
}
