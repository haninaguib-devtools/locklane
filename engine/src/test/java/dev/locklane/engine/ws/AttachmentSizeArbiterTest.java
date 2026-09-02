package dev.locklane.engine.ws;

import dev.locklane.engine.ws.AttachmentSizeArbiter.Size;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which attached client's size the PTY follows (#574): the focused one, with a lone
 * or unfocused-only set of clients behaving exactly as before (every resize applies).
 */
class AttachmentSizeArbiterTest {

    private static final String TERMINAL = "1-console";
    private static final Size WIDE = new Size(200, 50);
    private static final Size NARROW = new Size(120, 40);

    private final AttachmentSizeArbiter arbiter = new AttachmentSizeArbiter();

    @Test
    void aLoneClientThatNeverReportedFocusStillResizesThePty() {
        assertThat(arbiter.resized(TERMINAL, "a", WIDE)).contains(WIDE);
        assertThat(arbiter.resized(TERMINAL, "a", NARROW)).contains(NARROW);
    }

    @Test
    void aResizeFromAnUnfocusedClientIsHeldWhileAnotherIsFocused() {
        arbiter.focused(TERMINAL, "a");
        assertThat(arbiter.resized(TERMINAL, "a", WIDE)).contains(WIDE);

        assertThat(arbiter.resized(TERMINAL, "b", NARROW)).isEmpty();
    }

    @Test
    void focusMovingToTheOtherClientAppliesItsLastReportedSize() {
        arbiter.focused(TERMINAL, "a");
        arbiter.resized(TERMINAL, "a", WIDE);
        arbiter.resized(TERMINAL, "b", NARROW);

        assertThat(arbiter.focused(TERMINAL, "b")).contains(NARROW);
        // And a is now the one held back.
        assertThat(arbiter.resized(TERMINAL, "a", WIDE)).isEmpty();
        assertThat(arbiter.focused(TERMINAL, "a")).contains(WIDE);
    }

    @Test
    void focusFromAClientWithNoSizeYetAppliesNothingButStillTakesOver() {
        arbiter.resized(TERMINAL, "a", WIDE);

        assertThat(arbiter.focused(TERMINAL, "b")).isEmpty();
        assertThat(arbiter.resized(TERMINAL, "a", NARROW)).isEmpty();
        assertThat(arbiter.resized(TERMINAL, "b", NARROW)).contains(NARROW);
    }

    @Test
    void theFocusedClientDetachingLetsTheRemainingOneResizeAgain() {
        arbiter.focused(TERMINAL, "a");
        arbiter.resized(TERMINAL, "b", NARROW);

        arbiter.detached(TERMINAL, "a");

        assertThat(arbiter.resized(TERMINAL, "b", WIDE)).contains(WIDE);
    }

    @Test
    void anUnfocusedClientDetachingChangesNothingForTheFocusedOne() {
        arbiter.focused(TERMINAL, "a");
        arbiter.resized(TERMINAL, "b", NARROW);

        arbiter.detached(TERMINAL, "b");

        assertThat(arbiter.resized(TERMINAL, "a", WIDE)).contains(WIDE);
        assertThat(arbiter.focused(TERMINAL, "b")).isEmpty();
    }

    @Test
    void terminalsAreIndependent() {
        arbiter.focused(TERMINAL, "a");

        assertThat(arbiter.resized("other-terminal", "b", NARROW)).contains(NARROW);
    }
}
