package dev.locklane.engine.usage;

/**
 * One CLI's usage source (#695: also the single place that names its id, display
 * label, and the bar color the widget paints for it — the client renders whichever
 * providers {@link UsageService} registers, without knowing any agent's name itself).
 * {@link #fetch()} never throws — any failure resolves to {@link ProviderUsage#unavailable()}.
 */
public interface UsageProvider {

    /** Stable id, the same value {@link dev.locklane.engine.agent.InstalledAgentsStore} uses for this CLI. */
    String id();

    /** Display label the widget shows for this provider. */
    String label();

    /** CSS color the widget paints this provider's bars with. */
    String color();

    ProviderUsage fetch();
}
