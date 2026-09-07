package dev.locklane.engine.ide;

/**
 * One row of {@code GET /api/ides/installed} (#781): the stable id a client stores as
 * its preference and sends back in {@code open-ide}'s body, the label it shows, and
 * whether picking this IDE opens a window on the engine host's own desktop ({@code
 * desktop} true — VS Code, IntelliJ IDEA) rather than a page in the browser
 * ({@code false} — the bundled code-server). The client offers a desktop IDE only to a
 * browser on the engine's own machine, and the engine honours one only for such a
 * request ({@code security.LoopbackRequests}).
 */
public record IdeInfo(String id, String label, boolean desktop) {
}
