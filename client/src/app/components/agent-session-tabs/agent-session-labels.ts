import { Agent } from '../../services/agent-store';

// The Overview pseudo-tab's id: it sits in the same tab strip as open agent sessions
// (#96) but isn't a real session, so it needs a value no real agent session id can
// collide with.
export const OVERVIEW_TAB_ID = 'overview';

// An agent session open under an issue: the engine's session id, plus the agent the
// client remembers launching it with (null when unknown — another browser
// opened it, or storage was cleared). `dir` is the agent session's working directory
// when the caller knows it (#447: the project-agent-session page's tabs carry it at
// runtime, since labelProjectAgentSessions spreads that page's own agent session objects);
// absent, the open-shell control resolves it from the project worktree list.
export interface AgentSessionInfo {
  id: string;
  agent: Agent | null;
  dir?: string | null;
}

export interface AgentSessionTab extends AgentSessionInfo {
  /** The auto-generated label, from {@link labelAgentSessions} or the caller's own rule. */
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
export function tabText(tab: AgentSessionTab): string {
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
 * Tab labels: location ("main"/"wtree"), an index from the second agent session of
 * that location on ("main", "main 2"), and the agent when known
 * ("wtree · claude").
 */
export function labelAgentSessions(agentSessions: AgentSessionInfo[]): AgentSessionTab[] {
  const seen = { main: 0, wtree: 0 };
  return agentSessions.map((c) => {
    const location = isMainSession(c.id) ? 'main' : 'wtree';
    seen[location]++;
    const index = seen[location] > 1 ? ` ${seen[location]}` : '';
    const agent = c.agent ? ` · ${c.agent}` : '';
    return { ...c, label: `${location}${index}${agent}` };
  });
}

/**
 * Project-agent-session tab labels (#139/#177): every agent session runs in its own worktree
 * with no location to label by, just an index from the second agent session on
 * ("agent", "agent 2") -- no agent suffix, unlike {@link labelAgentSessions}
 * (#456). The one place this is computed (#449) -- the project-agent-session page's own
 * tab strip and the header agent sessions widget both call this and then
 * {@link tabText}, rather than each maintaining its own copy of the numbering
 * rule.
 */
export function labelProjectAgentSessions(agentSessions: (AgentSessionInfo & { name?: string | null })[]): AgentSessionTab[] {
  return agentSessions.map((c, i) => {
    const index = i > 0 ? ` ${i + 1}` : '';
    return { ...c, label: `agent${index}` };
  });
}
