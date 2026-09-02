package dev.locklane.engine.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
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

    @Test
    void aProjectCreatedWithoutATemplateHasNone(@TempDir Path dbDir) {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(dbDir);

        ProjectRecord created = repository.create("foo", "url", dbDir.resolve("foo"), 1L, Instant.now());

        assertThat(created.template()).isNull();
        assertThat(repository.findById(created.id())).isPresent().get()
                .extracting(ProjectRecord::template).isNull();
    }

    @Test
    void aProjectCreatedFromATemplateRemembersItsName(@TempDir Path dbDir) {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(dbDir);

        ProjectRecord created = repository.create("foo", "url", dbDir.resolve("foo"), 1L, Instant.now(),
                "springboot-angular");

        assertThat(created.template()).isEqualTo("springboot-angular");
        assertThat(repository.findAllOwnedBy(1L)).extracting(ProjectRecord::template)
                .containsExactly("springboot-angular");
        assertThat(repository.findByWorkareaPath(dbDir.resolve("foo"))).isPresent().get()
                .extracting(ProjectRecord::template).isEqualTo("springboot-angular");
    }

    @Test
    void markTemplateSeededRecordsTheInstantOnce(@TempDir Path dbDir) {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(dbDir);
        ProjectRecord created = repository.create("foo", "url", dbDir.resolve("foo"), 1L, Instant.now(),
                "springboot-angular");
        assertThat(created.templateSeededAt()).isNull();

        repository.markTemplateSeeded(created.id(), Instant.parse("2026-09-01T12:00:00Z"));

        assertThat(repository.findById(created.id())).isPresent().get()
                .extracting(ProjectRecord::templateSeededAt).isEqualTo(Instant.parse("2026-09-01T12:00:00Z"));
        assertThat(repository.findAllOwnedBy(1L)).extracting(ProjectRecord::templateSeededAt)
                .containsExactly(Instant.parse("2026-09-01T12:00:00Z"));
    }

    @Test
    void aCreatedProjectStartsWithNoAccentColor(@TempDir Path dbDir) {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(dbDir);

        ProjectRecord created = repository.create("foo", "url", dbDir.resolve("foo"), 1L, Instant.now());

        assertThat(created.accentColor()).isNull();
    }

    @Test
    void setAccentColorRoundTripsThroughTheRepository(@TempDir Path dbDir) {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(dbDir);
        ProjectRecord created = repository.create("foo", "url", dbDir.resolve("foo"), 1L, Instant.now());

        repository.setAccentColor(created.id(), "#c15f3c");

        assertThat(repository.findById(created.id())).isPresent().get()
                .extracting(ProjectRecord::accentColor).isEqualTo("#c15f3c");
    }

    @Test
    void setAccentColorCanClearItBackToNull(@TempDir Path dbDir) {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(dbDir);
        ProjectRecord created = repository.create("foo", "url", dbDir.resolve("foo"), 1L, Instant.now());
        repository.setAccentColor(created.id(), "#c15f3c");

        repository.setAccentColor(created.id(), null);

        assertThat(repository.findById(created.id())).isPresent().get()
                .extracting(ProjectRecord::accentColor).isNull();
    }

    @Test
    void projectsAreCreatedWithSequentialSortOrderPerOwner(@TempDir Path dbDir) {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(dbDir);

        ProjectRecord first = repository.create("a", "url-a", dbDir.resolve("a"), 1L, Instant.now());
        ProjectRecord second = repository.create("b", "url-b", dbDir.resolve("b"), 1L, Instant.now());
        // A different owner's own sequence starts over at 0 -- one owner's projects
        // never compete for the same positions as another's.
        ProjectRecord othersFirst = repository.create("c", "url-c", dbDir.resolve("c"), 2L, Instant.now());

        assertThat(first.sortOrder()).isEqualTo(0);
        assertThat(second.sortOrder()).isEqualTo(1);
        assertThat(othersFirst.sortOrder()).isEqualTo(0);
    }

    @Test
    void findAllOwnedByReturnsProjectsInSortOrder(@TempDir Path dbDir) {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(dbDir);
        ProjectRecord a = repository.create("a", "url-a", dbDir.resolve("a"), 1L, Instant.now());
        ProjectRecord b = repository.create("b", "url-b", dbDir.resolve("b"), 1L, Instant.now());
        ProjectRecord c = repository.create("c", "url-c", dbDir.resolve("c"), 1L, Instant.now());

        repository.setOrder(List.of(c.id(), a.id(), b.id()));

        assertThat(repository.findAllOwnedBy(1L)).extracting(ProjectRecord::name)
                .containsExactly("c", "a", "b");
    }

    @Test
    void setOrderAssignsEachIdsIndexAsItsNewSortOrder(@TempDir Path dbDir) {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(dbDir);
        ProjectRecord a = repository.create("a", "url-a", dbDir.resolve("a"), 1L, Instant.now());
        ProjectRecord b = repository.create("b", "url-b", dbDir.resolve("b"), 1L, Instant.now());

        repository.setOrder(List.of(b.id(), a.id()));

        assertThat(repository.findById(b.id())).isPresent().get().extracting(ProjectRecord::sortOrder).isEqualTo(0);
        assertThat(repository.findById(a.id())).isPresent().get().extracting(ProjectRecord::sortOrder).isEqualTo(1);
    }
}
