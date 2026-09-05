package dev.locklane.engine.agent;

/**
 * One coding-agent CLI the engine knows about: its id (the executable name on {@code
 * PATH}, and the value stored/sent everywhere else — sessions, preferences, usage
 * providers) and the display label the client shows for it (#695). The server is the
 * only place this pairing is written down; the client renders whichever ids and labels
 * {@link InstalledAgentsController} sends it.
 */
public record AgentInfo(String id, String label) {
}
