package dev.locklane.engine.process;

/**
 * The exit code and both streams of a completed subprocess — the one shape every
 * {@code ProcessBuilder} caller in the engine converts its own captured output into
 * before logging, so a failed command's log line is built the same way everywhere
 * (#546) instead of each class re-implementing its own version of {@link #describe()}.
 */
public record ProcessOutcome(int exitCode, String stdout, String stderr) {

    public boolean failed() {
        return exitCode != 0;
    }

    /** Both streams, stripped and joined for a log line that can actually explain why. */
    public String describe() {
        String out = stdout.strip();
        String err = stderr.strip();
        if (out.isEmpty()) {
            return err;
        }
        if (err.isEmpty()) {
            return out;
        }
        return out + " | " + err;
    }
}
