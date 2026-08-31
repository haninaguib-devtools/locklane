import { Agent } from '../../services/agent-store';

// The Overview pseudo-tab's id: it sits in the same tab strip as open consoles
// (#96) but isn't a real session, so it needs a value no real console id can
// collide with.
export const OVERVIEW_TAB_ID = 'overview';

// A console open under an issue: the engine's session id, plus the agent the
// client remembers launching it with (null when unknown — another browser
// opened it, or storage was cleared). `dir` is the console's working directory
// when the caller knows it (#447: the project-console page's tabs carry it at
// runtime, since labelProjectConsoles spreads that page's own console objects);
// absent, the open-shell control resolves it from the project worktree list.
export interface ConsoleInfo {
  id: string;
  agent: Agent | null;
  dir?: string | null;
}

export interface ConsoleTab extends ConsoleInfo {
  /** The auto-generated label, from {@link labelConsoles} or the caller's own rule. */
  label: string;
  /**
   * The name the user gave this tab (#393), or null/absent when they gave it none.
   * The strip shows this in place of `label` when it is set; clearing it brings the
   * auto label straight back, which is why both are carried rather than one
   * overwriting the other.
   */
  name?: string | null;
}

/** What the tab strip actually shows: the user's own name when there is one (#393). */
export function tabText(tab: ConsoleTab): string {
  const name = tab.name?.trim();
  return name ? name : tab.label;
}

// Main-checkout session ids are minted as "<projectId>-<issue>-main-<random8>"
// (#29, project-prefixed since #43); everything else under an issue is a
// worktree session.
export function isMainSession(id: string): boolean {
  return /^\d+-\d+-main-/.test(id);
}

/**
 * Tab labels: location ("main"/"wtree"), an index from the second console of
 * that location on ("main", "main 2"), and the agent when known
 * ("wtree · claude").
 */
export function labelConsoles(consoles: ConsoleInfo[]): ConsoleTab[] {
  const seen = { main: 0, wtree: 0 };
  return consoles.map((c) => {
    const location = isMainSession(c.id) ? 'main' : 'wtree';
    seen[location]++;
    const index = seen[location] > 1 ? ` ${seen[location]}` : '';
    const agent = c.agent ? ` · ${c.agent}` : '';
    return { ...c, label: `${location}${index}${agent}` };
  });
}

/**
 * Project-console tab labels (#139/#177): every console runs in its own worktree
 * with no location to label by, just an index from the second console on
 * ("console", "console 2"), plus the agent when known ("console · claude"). The
 * one place this is computed (#449) -- the project-console page's own tab strip
 * and the header consoles widget both call this and then {@link tabText}, rather
 * than each maintaining its own copy of the numbering rule.
 */
export function labelProjectConsoles(consoles: (ConsoleInfo & { name?: string | null })[]): ConsoleTab[] {
  return consoles.map((c, i) => {
    const index = i > 0 ? ` ${i + 1}` : '';
    const agent = c.agent ? ` · ${c.agent}` : '';
    return { ...c, label: `console${index}${agent}` };
  });
}
