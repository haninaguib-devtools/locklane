import { Agent } from '../../services/agent-store';

// A console open under an issue: the engine's session id, plus the agent the
// client remembers launching it with (null when unknown — another browser
// opened it, or storage was cleared).
export interface ConsoleInfo {
  id: string;
  agent: Agent | null;
}

export interface ConsoleTab extends ConsoleInfo {
  label: string;
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
