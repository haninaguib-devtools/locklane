/**
 * Estimated staged text for the client-only import/create/clone wait (#717): no engine
 * signal backs these thresholds -- the engine only ever reports CLONING/READY/FAILED --
 * so they are wide guesses meant to read as "still working" rather than "stuck", not a
 * real accounting of what the engine is doing at that moment.
 */
export function cloneStageHint(elapsedSeconds: number): string {
  if (elapsedSeconds < 3) {
    return 'contacting GitHub…';
  }
  if (elapsedSeconds < 10) {
    return 'cloning repository…';
  }
  return 'preparing workarea…';
}

/** Whole seconds between `sinceMs` and `nowMs`, floored at zero against clock skew. */
export function elapsedSeconds(sinceMs: number, nowMs: number): number {
  return Math.max(0, Math.floor((nowMs - sinceMs) / 1000));
}
