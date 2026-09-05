package dev.locklane.engine.persistence;

import java.time.Instant;

/**
 * One resume id seen in one console's output (#102, #295, #681): {@code tool} is the CLI that can
 * resume it ("claude", "codex", "opencode", or "omp"), {@code resumeId} is what that CLI's resume command
 * accepts (`claude --resume <id>` / `codex resume <id>` / `opencode --session <id>` /
 * `omp --resume <id>`), and {@code worktreeId} ties
 * it to the console — and through the worktree-id naming convention
 * ({@link IssueWorktreeService}) to the project/issue — it was captured in.
 */
public record ConsoleResumeSessionRecord(
        String worktreeId,
        String tool,
        String resumeId,
        Instant capturedAt) {
}
