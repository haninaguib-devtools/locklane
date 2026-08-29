package dev.locklane.engine.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectRepositoryTest {

    @Test
    void aCreatedProjectStartsCloningWithNoDefaultBranch(@TempDir Path dbDir) {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(dbDir);
        Path workarea = dbDir.resolve("workareas/1/foo");

        ProjectRecord created = repository.create("foo", "https://example.com/foo.git", workarea, 1L, Instant.now());

        assertThat(created.name()).isEqualTo("foo");
        assertThat(created.gitUrl()).isEqualTo("https://example.com/foo.git");
        assertThat(created.workareaPath()).isEqualTo(workarea);
        assertThat(created.ownerUserId()).isEqualTo(1L);
        assertThat(created.status()).isEqualTo(ProjectStatus.CLONING);
        assertThat(created.defaultBranch()).isNull();
    }

    @Test
    void markReadySetsStatusAndDefaultBranch(@TempDir Path dbDir) {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(dbDir);
        ProjectRecord created = repository.create("foo", "url", dbDir.resolve("foo"), 1L, Instant.now());

        repository.markReady(created.id(), "master");

        ProjectRecord found = repository.findById(created.id()).orElseThrow();
        assertThat(found.status()).isEqualTo(ProjectStatus.READY);
        assertThat(found.defaultBranch()).isEqualTo("master");
    }

    @Test
    void markFailedSetsStatusOnly(@TempDir Path dbDir) {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(dbDir);
        ProjectRecord created = repository.create("foo", "url", dbDir.resolve("foo"), 1L, Instant.now());

        repository.markFailed(created.id());

        assertThat(repository.findById(created.id())).isPresent().get()
                .extracting(ProjectRecord::status).isEqualTo(ProjectStatus.FAILED);
    }

    @Test
    void markCloningClearsAPreviousDefaultBranch(@TempDir Path dbDir) {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(dbDir);
        ProjectRecord created = repository.create("foo", "url", dbDir.resolve("foo"), 1L, Instant.now());
        repository.markReady(created.id(), "main");

        repository.markCloning(created.id());

        ProjectRecord found = repository.findById(created.id()).orElseThrow();
        assertThat(found.status()).isEqualTo(ProjectStatus.CLONING);
        assertThat(found.defaultBranch()).isNull();
    }

    @Test
    void findByWorkareaPathFindsAnExistingProject(@TempDir Path dbDir) {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(dbDir);
        Path workarea = dbDir.resolve("bar");
        repository.create("bar", "url", workarea, 1L, Instant.now());

        Optional<ProjectRecord> found = repository.findByWorkareaPath(workarea);

        assertThat(found).isPresent().get().extracting(ProjectRecord::name).isEqualTo("bar");
    }

    @Test
    void findByWorkareaPathIsEmptyForAnUnusedPath(@TempDir Path dbDir) {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(dbDir);

        assertThat(repository.findByWorkareaPath(dbDir.resolve("never-created"))).isEmpty();
    }

    @Test
    void findAllReturnsEveryProjectRegardlessOfOwner(@TempDir Path dbDir) {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(dbDir);
        repository.create("a", "url-a", dbDir.resolve("a"), 1L, Instant.now());
        repository.create("b", "url-b", dbDir.resolve("b"), 2L, Instant.now());

        assertThat(repository.findAll()).extracting(ProjectRecord::name).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void findAllOwnedByFiltersToOnlyThatOwnersProjects(@TempDir Path dbDir) {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(dbDir);
        repository.create("mine", "url-a", dbDir.resolve("a"), 1L, Instant.now());
        repository.create("theirs", "url-b", dbDir.resolve("b"), 2L, Instant.now());

        assertThat(repository.findAllOwnedBy(1L)).extracting(ProjectRecord::name).containsExactly("mine");
        assertThat(repository.findAllOwnedBy(2L)).extracting(ProjectRecord::name).containsExactly("theirs");
        assertThat(repository.findAllOwnedBy(999L)).isEmpty();
    }

    @Test
    void deleteForgetsTheProject(@TempDir Path dbDir) {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(dbDir);
        ProjectRecord created = repository.create("foo", "url", dbDir.resolve("foo"), 1L, Instant.now());

        repository.delete(created.id());

        assertThat(repository.findById(created.id())).isEmpty();
    }

    @Test
    void deletingAnUnknownProjectIsANoOp(@TempDir Path dbDir) {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(dbDir);

        repository.delete(999);
    }
}
