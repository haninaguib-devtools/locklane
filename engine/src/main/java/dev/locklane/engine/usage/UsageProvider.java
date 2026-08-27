package dev.locklane.engine.usage;

/** One CLI's usage source. Never throws — any failure resolves to {@link ProviderUsage#unavailable()}. */
public interface UsageProvider {

    ProviderUsage fetch();
}
