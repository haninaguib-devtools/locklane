package dev.locklane.engine.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.locklane.engine.persistence.GhAccountRepository;
import dev.locklane.engine.persistence.ProjectRecord;
import dev.locklane.engine.persistence.ProjectRepository;
import dev.locklane.engine.persistence.TestSqliteDatabases;
import dev.locklane.engine.security.EncryptionKeyProvider;
import dev.locklane.engine.security.TokenCipher;
import dev.locklane.engine.ws.EventBroadcaster;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Covers #81's done-when: each project's issue/PR fetches use that project's own token and repo. */
class ProjectGhResourcesTest {

    @Test
    void forProjectIsEmptyForAnUnknownProject(@TempDir Path dataDir) throws IOException {
        ProjectGhResources resources = resources(dataDir, TestSqliteDatabases.newProjectRepository(dataDir),
                (path, token) -> failIfCalled());

        assertThat(resources.forProject(999)).isEmpty();
    }

    @Test
    void forProjectBuildsAClientScopedToTheProjectsOwnWorkarea(@TempDir Path dataDir) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(dataDir);
        ProjectRecord project = readyProject(repository, dataDir, "myproj");
        RecordingFactory factory = new RecordingFactory();
        ProjectGhResources resources = resources(dataDir, repository, factory);

        resources.forProject(project.id());

        assertThat(factory.lastPath).isEqualTo(project.workareaPath());
    }

    @Test
    void forProjectPassesNullTokenWhenNoneIsStored(@TempDir Path dataDir) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(dataDir);
        ProjectRecord project = readyProject(repository, dataDir, "myproj");
        RecordingFactory factory = new RecordingFactory();
        ProjectGhResources resources = resources(dataDir, repository, factory);

        resources.forProject(project.id());

        assertThat(factory.lastToken).isNull();
    }

    @Test
    void forProjectDecryptsTheStoredTokenBeforePassingItToTheClientFactory(@TempDir Path dataDir) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(dataDir);
        ProjectRecord project = readyProject(repository, dataDir, "myproj");
        TokenCipher cipher = new TokenCipher(new EncryptionKeyProvider(dataDir.toString()));
        GhAccountRepository ghAccountRepository = TestSqliteDatabases.newGhAccountRepository(dataDir);
        GhAccount account = ghAccountRepository.insert(1L, "work", cipher.encrypt("ghp_secret"), Set.of("repo"),
                Instant.now());
        repository.setGithubAccountId(project.id(), account.id());
        RecordingFactory factory = new RecordingFactory();
        ProjectGhResources resources = new ProjectGhResources(repository, ghAccountRepository, cipher, factory);

        resources.forProject(project.id());

        assertThat(factory.lastToken).isEqualTo("ghp_secret");
    }

    @Test
    void forProjectReturnsTheSameContextOnRepeatedCalls(@TempDir Path dataDir) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(dataDir);
        ProjectRecord project = readyProject(repository, dataDir, "myproj");
        RecordingFactory factory = new RecordingFactory();
        ProjectGhResources resources = resources(dataDir, repository, factory);

        ProjectGhContext first = resources.forProject(project.id()).orElseThrow();
        ProjectGhContext second = resources.forProject(project.id()).orElseThrow();

        assertThat(second).isSameAs(first);
        assertThat(factory.callCount).isEqualTo(1);
    }

    @Test
    void twoProjectsGetIndependentContexts(@TempDir Path dataDir) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(dataDir);
        ProjectRecord projectA = readyProject(repository, dataDir, "a");
        ProjectRecord projectB = readyProject(repository, dataDir, "b");
        RecordingFactory factory = new RecordingFactory();
        ProjectGhResources resources = resources(dataDir, repository, factory);

        ProjectGhContext contextA = resources.forProject(projectA.id()).orElseThrow();
        ProjectGhContext contextB = resources.forProject(projectB.id()).orElseThrow();

        assertThat(contextA).isNotSameAs(contextB);
        assertThat(factory.callCount).isEqualTo(2);
    }

    @Test
    void evictForcesTheNextLookupToRebuild(@TempDir Path dataDir) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(dataDir);
        ProjectRecord project = readyProject(repository, dataDir, "myproj");
        RecordingFactory factory = new RecordingFactory();
        ProjectGhResources resources = resources(dataDir, repository, factory);
        ProjectGhContext first = resources.forProject(project.id()).orElseThrow();

        resources.evict(project.id());
        ProjectGhContext second = resources.forProject(project.id()).orElseThrow();

        assertThat(second).isNotSameAs(first);
        assertThat(factory.callCount).isEqualTo(2);
    }

    @Test
    void refreshAllRefreshesEveryBuiltProjectsCache(@TempDir Path dataDir) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(dataDir);
        ProjectRecord project = readyProject(repository, dataDir, "myproj");
        AtomicInteger fetchCount = new AtomicInteger();
        RecordingGhClient client = new RecordingGhClient(fetchCount);
        ProjectGhResources resources = resources(dataDir, repository, (path, token) -> client);
        resources.forProject(project.id());

        resources.refreshAll();

        assertThat(fetchCount.get()).isEqualTo(1);
    }

    @Test
    void refreshAllBroadcastsIssuesChangedWhenTheCachedStateMoves(@TempDir Path dataDir) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(dataDir);
        ProjectRecord project = readyProject(repository, dataDir, "myproj");
        VariableGhClient client = new VariableGhClient();
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        ProjectGhResources resources = new ProjectGhResources(repository, TestSqliteDatabases.newGhAccountRepository(dataDir),
                new TokenCipher(new EncryptionKeyProvider(dataDir.toString())), broadcaster, (path, token) -> client);
        resources.forProject(project.id());
        resources.refreshAll(); // warms the cache -- moving from unknown to known always "changes"

        client.setIssues(List.of(new GhIssue(1, "New issue", "OPEN", List.of(), "", "", "")));
        resources.refreshAll();

        verify(broadcaster, times(2)).broadcast("issuesChanged", Map.of("projectId", project.id()));
    }

    @Test
    void refreshAllBroadcastsNothingOnAnUnchangedRefresh(@TempDir Path dataDir) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(dataDir);
        ProjectRecord project = readyProject(repository, dataDir, "myproj");
        VariableGhClient client = new VariableGhClient();
        client.setIssues(List.of(new GhIssue(1, "First", "OPEN", List.of(), "", "", "")));
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        ProjectGhResources resources = new ProjectGhResources(repository, TestSqliteDatabases.newGhAccountRepository(dataDir),
                new TokenCipher(new EncryptionKeyProvider(dataDir.toString())), broadcaster, (path, token) -> client);
        resources.forProject(project.id());
        resources.refreshAll(); // warms the cache

        resources.refreshAll(); // same data as before -- nothing moved

        verify(broadcaster, times(1)).broadcast("issuesChanged", Map.of("projectId", project.id()));
    }

    @Test
    void refreshAllNeverBroadcastsForAProjectThatWasNeverLookedUp(@TempDir Path dataDir) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(dataDir);
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        ProjectGhResources resources = new ProjectGhResources(repository, TestSqliteDatabases.newGhAccountRepository(dataDir),
                new TokenCipher(new EncryptionKeyProvider(dataDir.toString())), broadcaster, (path, token) -> new VariableGhClient());

        resources.refreshAll();

        verifyNoInteractions(broadcaster);
    }

    private static ProjectRecord readyProject(ProjectRepository repository, Path dataDir, String name) {
        return repository.createReady(name, "url", dataDir.resolve(name), "main", 1L, Instant.now());
    }

    private static ProjectGhResources resources(Path dataDir, ProjectRepository repository,
            java.util.function.BiFunction<Path, String, GhClient> factory) throws IOException {
        return new ProjectGhResources(repository, TestSqliteDatabases.newGhAccountRepository(dataDir),
                new TokenCipher(new EncryptionKeyProvider(dataDir.toString())), factory);
    }

    private static GhClient failIfCalled() {
        throw new AssertionError("the client factory should never run for an unknown project");
    }

    private static final class RecordingFactory implements java.util.function.BiFunction<Path, String, GhClient> {
        Path lastPath;
        String lastToken;
        int callCount;

        @Override
        public GhClient apply(Path path, String token) {
            lastPath = path;
            lastToken = token;
            callCount++;
            return new RecordingGhClient(new AtomicInteger());
        }
    }

    private static final class RecordingGhClient implements GhClient {
        private final AtomicInteger fetchCount;

        RecordingGhClient(AtomicInteger fetchCount) {
            this.fetchCount = fetchCount;
        }

        @Override
        public List<GhIssue> issues() {
            fetchCount.incrementAndGet();
            return List.of();
        }

        @Override
        public List<GhPullRequest> pullRequests() {
            return List.of();
        }

        @Override
        public Optional<GhPullRequestDetail> pullRequestDetail(int number) {
            return Optional.empty();
        }
    }

    private static final class VariableGhClient implements GhClient {
        private List<GhIssue> issues = List.of();

        void setIssues(List<GhIssue> issues) {
            this.issues = issues;
        }

        @Override
        public List<GhIssue> issues() {
            return issues;
        }

        @Override
        public List<GhPullRequest> pullRequests() {
            return List.of();
        }

        @Override
        public Optional<GhPullRequestDetail> pullRequestDetail(int number) {
            return Optional.empty();
        }
    }
}
