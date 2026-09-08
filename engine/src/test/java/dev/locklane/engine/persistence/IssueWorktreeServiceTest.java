package dev.locklane.engine.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers #15's done-when: worktrees grouped by project+issue, non-conforming ids
 * excluded (#43). Visibility itself is #242's project-owner-derived model (ADR-101
 * Decision 6, via {@link WorktreeSessionAuthorization}): a session's owning project
 * is resolved from the leading numeric segment of its id, and only that project's
 * owner sees it — who last attached to the session no longer matters, and since #394
 * (ADR-105) neither does the caller's role.
 */
class IssueWorktreeServiceTest {
    // Session ids ("<projectId>-console[-<hex>]"), "<repo>-console-<hex>" worktree directories and the
    // /console and /consoles REST paths below keep their persisted and on-the-wire shape: compatibility
    // surfaces kept under ADR-112 (#766 renamed only the identifiers).

    @Test
    void returnsWorktreeIdsMatchingTheProjectAndIssuePrefix(@TempDir Path dbDir) {
        createProject(dbDir, "alice", "one"); // project 1
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), now, "alice");
        repository.recordAttach("1-174-other-attempt", dbDir.resolve("wt2"), now, "alice");
        repository.recordAttach("1-175-something", dbDir.resolve("wt3"), now, "alice");

        IssueWorktreeService service = service(dbDir, repository);

        assertThat(service.worktreeIdsForIssue(1, 174, "alice"))
                .containsExactlyInAnyOrder("1-174-rename-toggle", "1-174-other-attempt");
        assertThat(service.worktreeIdsForIssue(1, 175, "alice")).containsExactly("1-175-something");
    }

    @Test
    void aDifferentProjectWithTheSameIssueNumberIsExcluded(@TempDir Path dbDir) {
        createProject(dbDir, "alice", "one"); // project 1
        createProject(dbDir, "alice", "two"); // project 2, also alice's
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), now, "alice");
        repository.recordAttach("2-174-unrelated-repo", dbDir.resolve("wt2"), now, "alice");

        IssueWorktreeService service = service(dbDir, repository);

        assertThat(service.worktreeIdsForIssue(1, 174, "alice")).containsExactly("1-174-rename-toggle");
        assertThat(service.worktreeIdsForIssue(2, 174, "alice")).containsExactly("2-174-unrelated-repo");
    }

    @Test
    void anIssueWithNoKnownWorktreesReturnsAnEmptyList(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        IssueWorktreeService service = service(dbDir, repository);

        assertThat(service.worktreeIdsForIssue(1, 999, "alice")).isEmpty();
    }

    @Test
    void nonConformingWorktreeIdsAreExcludedRatherThanThrowing(@TempDir Path dbDir) {
        createProject(dbDir, "alice", "one"); // project 1
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        repository.recordAttach("main", dbDir.resolve("wt1"), now, "alice");
        repository.recordAttach("not-numeric-prefix", dbDir.resolve("wt2"), now, "alice");
        repository.recordAttach("174-rename-toggle", dbDir.resolve("wt3"), now, "alice"); // only one numeric segment
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt4"), now, "alice");

        IssueWorktreeService service = service(dbDir, repository);

        // "main", a non-numeric id, and a single-segment-numeric id never match any project/issue.
        assertThat(service.worktreeIdsForIssue(1, 174, "alice")).containsExactly("1-174-rename-toggle");
        for (int n = 0; n < 1000; n++) {
            assertThat(service.worktreeIdsForIssue(1, n, "alice"))
                    .doesNotContain("main", "not-numeric-prefix", "174-rename-toggle");
        }
    }

    @Test
    void projectAgentSessionIdsNeverReadAsAnIssuesSession(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        repository.recordAttach("1-console", dbDir.resolve("wt1"), now, "alice"); // legacy pre-#177 shape
        repository.recordAttach("1-console-0a1b2c3d", dbDir.resolve("wt2"), now, "alice"); // #177 family
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt3"), now, "alice");

        IssueWorktreeService service = service(dbDir, repository);

        // The agent session family's second segment is the literal "console" (ADR-112 compatibility surface), never a
        // number — so neither shape can collide with any single issue's session list.
        for (int n = 0; n < 1000; n++) {
            assertThat(service.worktreeIdsForIssue(1, n, "alice"))
                    .doesNotContain("1-console", "1-console-0a1b2c3d");
        }
    }

    @Test
    void allWorktreeIdsIncludesTheProjectsOwnAgentSessions(@TempDir Path dbDir) {
        createProject(dbDir, "alice", "one"); // project 1
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        repository.recordAttach("1-console", dbDir.resolve("wt1"), now, "alice"); // legacy pre-#177 shape
        repository.recordAttach("1-console-0a1b2c3d", dbDir.resolve("wt2"), now, "alice"); // #177 family
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt3"), now, "alice");
        repository.recordAttach("2-console", dbDir.resolve("wt4"), now, "alice"); // a different, unowned-by-alice project

        IssueWorktreeService service = service(dbDir, repository);

        // #194: the header indicator/picker reads this list, so it must include the
        // project's own agent sessions alongside its issues' — scoped to the requested
        // project, same as any other row here.
        assertThat(service.allWorktreeIds(1, "alice")).containsExactlyInAnyOrder(
                "1-console", "1-console-0a1b2c3d", "1-174-rename-toggle");
    }

    @Test
    void doesNotFalselyMatchAnIssueNumberThatIsAPrefixOfAnother(@TempDir Path dbDir) {
        createProject(dbDir, "alice", "one"); // project 1
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), now, "alice");
        repository.recordAttach("1-1740-other-issue", dbDir.resolve("wt2"), now, "alice");

        IssueWorktreeService service = service(dbDir, repository);

        // Issue 174's worktree must not accidentally capture issue 1740's.
        List<String> forIssue174 = service.worktreeIdsForIssue(1, 174, "alice");
        assertThat(forIssue174).containsExactly("1-174-rename-toggle");
    }

    @Test
    void includesEverySessionInTheOwnedProjectRegardlessOfWhoAttached(@TempDir Path dbDir) {
        createProject(dbDir, "alice", "one"); // project 1
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        repository.recordAttach("1-174-alices-session", dbDir.resolve("wt1"), now, "alice");
        // #242: a session's visibility is derived from its project's owner, not from
        // whoever attached to it -- bob attaching to a session in alice's project
        // doesn't take it out of her view.
        repository.recordAttach("1-174-bobs-session", dbDir.resolve("wt2"), now, "bob");
        repository.recordAttach("1-174-unclaimed-session", dbDir.resolve("wt3"), now, null);

        IssueWorktreeService service = service(dbDir, repository);

        assertThat(service.worktreeIdsForIssue(1, 174, "alice")).containsExactlyInAnyOrder(
                "1-174-alices-session", "1-174-bobs-session", "1-174-unclaimed-session");
    }

    @Test
    void excludesASessionInAProjectTheCallerDoesNotOwn(@TempDir Path dbDir) {
        createProject(dbDir, "bob", "bobs"); // project 1, owned by bob -- not alice
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        repository.recordAttach("1-174-bobs-session", dbDir.resolve("wt1"), now, "bob");

        IssueWorktreeService service = service(dbDir, repository);

        assertThat(service.worktreeIdsForIssue(1, 174, "alice")).isEmpty();
        assertThat(service.worktreeIdsForIssue(1, 174, "bob")).containsExactly("1-174-bobs-session");
    }

    /**
     * #394 (ADR-105) withdrew the administrator exemption ADR-101 Decision 6 granted:
     * an administrator sees another account's worktree and agent sessions exactly
     * as any other non-owner does — not at all.
     */
    @Test
    void excludesASessionInAProjectAnAdminDoesNotOwn(@TempDir Path dbDir) {
        createProject(dbDir, "bob", "bobs"); // project 1, owned by bob
        adminUserId(dbDir, "root");
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        repository.recordAttach("1-174-bobs-session", dbDir.resolve("wt1"), now, "bob");
        repository.recordAttach("1-console-0a1b2c3d", dbDir.resolve("wt2"), now, "bob");

        IssueWorktreeService service = service(dbDir, repository);

        assertThat(service.worktreeIdsForIssue(1, 174, "root")).isEmpty();
        assertThat(service.allWorktreeIds(1, "root")).isEmpty();
        assertThat(service.allWorktreeIds(1, "bob"))
                .containsExactlyInAnyOrder("1-174-bobs-session", "1-console-0a1b2c3d");
    }

    @Test
    void resumeSessionsAreListedForTheIssueNewestFirst(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        AgentSessionResumeSessionRepository resumeRepository =
                new AgentSessionResumeSessionRepository(TestSqliteDatabases.newDataSource(dbDir));
        resumeRepository.record("1-174-rename-toggle", "claude", "aaaaaaaa-0000-0000-0000-000000000000",
                Instant.parse("2026-08-25T12:00:00Z"));
        resumeRepository.record("1-174-rename-toggle", "codex", "bbbbbbbb-0000-0000-0000-000000000000",
                Instant.parse("2026-08-26T12:00:00Z"));
        resumeRepository.record("1-175-other-issue", "claude", "cccccccc-0000-0000-0000-000000000000",
                Instant.parse("2026-08-25T12:00:00Z"));
        resumeRepository.record("2-174-other-project", "claude", "dddddddd-0000-0000-0000-000000000000",
                Instant.parse("2026-08-25T12:00:00Z"));

        IssueWorktreeService service = service(dbDir, repository, resumeRepository);

        assertThat(service.resumeSessionsForIssue(1, 174, "alice"))
                .extracting(AgentSessionResumeSessionRecord::resumeId)
                .containsExactly("bbbbbbbb-0000-0000-0000-000000000000", "aaaaaaaa-0000-0000-0000-000000000000");
    }

    @Test
    void resumeSessionsExcludesALegacyMainAgentSessionsConversation(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        AgentSessionResumeSessionRepository resumeRepository =
                new AgentSessionResumeSessionRepository(TestSqliteDatabases.newDataSource(dbDir));
        // #341 retired opening an agent session against the project's main checkout; a
        // conversation captured in one of these legacy agent sessions can never be
        // resumed (there is no worktree that contains it), so it never appears here.
        resumeRepository.record("1-174-main-a1b2c3d4", "claude", "aaaaaaaa-0000-0000-0000-000000000000",
                Instant.parse("2026-08-25T12:00:00Z"));
        resumeRepository.record("1-174-rename-toggle", "claude", "bbbbbbbb-0000-0000-0000-000000000000",
                Instant.parse("2026-08-25T12:00:00Z"));

        IssueWorktreeService service = service(dbDir, repository, resumeRepository);

        assertThat(service.resumeSessionsForIssue(1, 174, "alice"))
                .extracting(AgentSessionResumeSessionRecord::worktreeId)
                .containsExactly("1-174-rename-toggle");
    }

    @Test
    void resumeSessionsFollowTheAgentSessionsProjectOwnershipAndTreatAClosedAgentSessionAsVisibleToAnyone(@TempDir Path dbDir) {
        createProject(dbDir, "alice", "one"); // project 1
        createProject(dbDir, "bob", "bobs"); // project 2, owned by bob -- not alice
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        AgentSessionResumeSessionRepository resumeRepository =
                new AgentSessionResumeSessionRepository(TestSqliteDatabases.newDataSource(dbDir));
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        repository.recordAttach("2-174-bobs-agent-session", dbDir.resolve("wt1"), now, "bob");
        repository.recordAttach("1-174-alices-agent-session", dbDir.resolve("wt2"), now, "alice");
        resumeRepository.record("2-174-bobs-agent-session", "claude", "aaaaaaaa-0000-0000-0000-000000000000", now);
        resumeRepository.record("1-174-alices-agent-session", "claude", "bbbbbbbb-0000-0000-0000-000000000000", now);
        // No session record at all — the agent session was closed (#75), which deletes it.
        resumeRepository.record("1-174-closed-agent-session", "codex", "cccccccc-0000-0000-0000-000000000000", now);

        IssueWorktreeService service = service(dbDir, repository, resumeRepository);

        assertThat(service.resumeSessionsForIssue(1, 174, "alice"))
                .extracting(AgentSessionResumeSessionRecord::resumeId)
                .containsExactlyInAnyOrder("bbbbbbbb-0000-0000-0000-000000000000",
                        "cccccccc-0000-0000-0000-000000000000");
    }

    @Test
    void theSameConversationSightedInTwoAgentSessionsIsListedOnceAtItsNewestSighting(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        AgentSessionResumeSessionRepository resumeRepository =
                new AgentSessionResumeSessionRepository(TestSqliteDatabases.newDataSource(dbDir));
        resumeRepository.record("1-174-first-agent-session", "claude", "aaaaaaaa-0000-0000-0000-000000000000",
                Instant.parse("2026-08-25T12:00:00Z"));
        resumeRepository.record("1-174-second-agent-session", "claude", "aaaaaaaa-0000-0000-0000-000000000000",
                Instant.parse("2026-08-26T12:00:00Z"));

        IssueWorktreeService service = service(dbDir, repository, resumeRepository);

        assertThat(service.resumeSessionsForIssue(1, 174, "alice"))
                .extracting(AgentSessionResumeSessionRecord::worktreeId)
                .containsExactly("1-174-second-agent-session");
    }

    @Test
    void hasAnySessionsIsTrueForAWorktreeOrAAgentSessionRegardlessOfOwner(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        repository.recordAttach("1-174-bobs-session", dbDir.resolve("wt1"), now, "bob");

        IssueWorktreeService service = service(dbDir, repository);

        assertThat(service.hasAnySessions(1)).isTrue();
        assertThat(service.hasAnySessions(2)).isFalse();
    }

    @Test
    void hasAnySessionsIsTrueForAProjectAgentSessionWithNoIssue(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        repository.recordAttach("1-console-0a1b2c3d", dbDir.resolve("wt1"), now, "alice");

        IssueWorktreeService service = service(dbDir, repository);

        assertThat(service.hasAnySessions(1)).isTrue();
    }

    @Test
    void hasAnySessionsIsFalseWhenNoSessionsAreRecordedAtAll(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);

        IssueWorktreeService service = service(dbDir, repository);

        assertThat(service.hasAnySessions(1)).isFalse();
    }

    // #585: the cleanup sweep's own per-issue worktree listing no longer sources from
    // this class's persisted records at all -- it moved to
    // WorktreeCleanupSweeper#allIssueWorktrees(), which asks git directly, the same
    // way WorktreeCleanupSweeperTest already covers allProjectAgentSessionWorktrees().

    @Test
    void allWorktreeIdsSpansEveryIssueInOneProjectRegardlessOfAttacherButExcludesOtherProjectsAndNonConformingIds(
            @TempDir Path dbDir) {
        createProject(dbDir, "alice", "one"); // project 1
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), now, "alice");
        repository.recordAttach("1-175-something", dbDir.resolve("wt2"), now, "alice");
        repository.recordAttach("main", dbDir.resolve("wt3"), now, "alice");
        // #242: bob attaching to a session inside alice's own project doesn't hide
        // it from her.
        repository.recordAttach("1-175-bobs-session", dbDir.resolve("wt4"), now, "bob");
        repository.recordAttach("1-174-unclaimed-session", dbDir.resolve("wt5"), now, null);
        // A session in a project alice does not own.
        repository.recordAttach("2-174-other-project", dbDir.resolve("wt6"), now, "alice");

        IssueWorktreeService service = service(dbDir, repository);

        assertThat(service.allWorktreeIds(1, "alice")).containsExactlyInAnyOrder(
                "1-174-rename-toggle", "1-175-something", "1-175-bobs-session", "1-174-unclaimed-session");
    }

    /** A real project row (first project 1, next 2, ...) owned by {@code ownerUsername}'s account. */
    private static void createProject(Path dbDir, String ownerUsername, String slug) {
        long ownerId = userId(dbDir, ownerUsername);
        TestSqliteDatabases.newProjectRepository(dbDir).createReady("proj-" + slug, "url",
                dbDir.resolve("work-" + slug), "main", ownerId, Instant.now());
    }

    /** {@code username}'s account id, creating the account (with a fresh id) the first time it's asked for. */
    /** An administrator account — the role #394 deliberately gives no extra reach. */
    private static long adminUserId(Path dbDir, String username) {
        UserRepository userRepository = TestSqliteDatabases.newUserRepository(dbDir);
        return userRepository.findByUsername(username)
                .map(UserRecord::id)
                .orElseGet(() -> userRepository
                        .create(username, "bcrypt-hash", Instant.now(), UserRecord.Role.ADMIN).id());
    }

    private static long userId(Path dbDir, String username) {
        UserRepository userRepository = TestSqliteDatabases.newUserRepository(dbDir);
        return userRepository.findByUsername(username)
                .map(UserRecord::id)
                .orElseGet(() -> userRepository.create(username, "bcrypt-hash", Instant.now()).id());
    }

    private static IssueWorktreeService service(Path dbDir, WorktreeSessionRepository repository) {
        return new IssueWorktreeService(repository, authorization(dbDir));
    }

    private static IssueWorktreeService service(Path dbDir, WorktreeSessionRepository repository,
            AgentSessionResumeSessionRepository resumeRepository) {
        return new IssueWorktreeService(repository, resumeRepository, authorization(dbDir));
    }

    private static WorktreeSessionAuthorization authorization(Path dbDir) {
        return new WorktreeSessionAuthorization(TestSqliteDatabases.newProjectRepository(dbDir),
                TestSqliteDatabases.newUserRepository(dbDir));
    }

    @Test
    void deleteSessionsForProjectRemovesWorktreesAndAgentSessionsForThatProjectOnly(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), now, "alice");
        repository.recordAttach("1-console-0a1b2c3d", dbDir.resolve("wt2"), now, "alice");
        repository.recordAttach("2-174-other-project", dbDir.resolve("wt3"), now, "alice");

        IssueWorktreeService service = new IssueWorktreeService(repository, TestSqliteDatabases.newNoopAuthorization());
        service.deleteSessionsForProject(1);

        assertThat(service.hasAnySessions(1))
                .as("#240's cascade-delete: every session scoped to project 1 is gone")
                .isFalse();
        assertThat(service.hasAnySessions(2))
                .as("a different project's session is untouched")
                .isTrue();
    }

    @Test
    void deleteSessionsForProjectWithNoSessionsIsANoOp(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        IssueWorktreeService service = new IssueWorktreeService(repository, TestSqliteDatabases.newNoopAuthorization());

        service.deleteSessionsForProject(1);

        assertThat(service.hasAnySessions(1)).isFalse();
    }
}
