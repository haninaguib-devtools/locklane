package dev.locklane.engine.usage;

import java.time.Instant;

/** One rate-limit window (a CLI's "5-hour" or "weekly" limit) at the moment it was fetched. */
public record WindowUsage(double percentLeft, Instant resetsAt) {
}
