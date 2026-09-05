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

// Mirrors dev.locklane.engine.usage.UsageSnapshot.ProviderSnapshot.
export interface ProviderSnapshot {
  id: string;
  label: string;
  color: string;
  usage: ProviderUsage;
}

// Mirrors dev.locklane.engine.usage.UsageSnapshot.
export interface UsageSnapshot {
  providers: ProviderSnapshot[];
  updatedAt: string;
}
