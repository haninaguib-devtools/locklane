package dev.locklane.engine.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A schema-initialized SQLite database backed by a file on disk, for tests. Public:
 * shared across test packages (e.g. {@code dev.locklane.engine.pty}), which need a
 * real repository to construct a {@code SessionRegistry} without a full Spring
 * context.
 *
 * <p>Builds its schema through the same Flyway migration path as production
 * (mirroring {@code spring.flyway.locations} in both {@code application.yml} files)
 * rather than a second, hand-rolled description of it.
 */
public final class TestSqliteDatabases {

    // Kept identical to spring.flyway.locations in both application.yml files: SQL
    // table-creation scripts under the standard db/migration/, plus the Java
    // column-adding migrations that live alongside the code they alter instead.
    private static final String[] MIGRATION_LOCATIONS = {
            "classpath:db/migration",
            "classpath:dev/locklane/engine/persistence/migration"
    };

    private TestSqliteDatabases() {
    }

    public static DataSource newDataSource(Path dbDirectory) {
        DataSource dataSource = sqliteDataSource(dbDirectory);
        migrate(dataSource, MigrationVersion.LATEST);
        return dataSource;
    }

    /**
     * A database migrated only as far as {@code version} — the shape an existing
     * installation would have had before a later migration ran. Pair with
     * {@link #migrateToLatest(DataSource)} to exercise the exact upgrade path a real
     * database takes: write rows against this partial shape first, then bring it
     * current and assert both the new columns and the old rows survived.
     */
    public static DataSource newDataSourceAtVersion(Path dbDirectory, String version) {
        DataSource dataSource = sqliteDataSource(dbDirectory);
        migrate(dataSource, MigrationVersion.fromVersion(version));
        return dataSource;
    }

    /**
     * Runs every migration after wherever {@code dataSource} currently stands —
     * exactly what production does on every startup, including against a database
     * left in an old shape by a version that predates some of these migrations.
     */
    public static void migrateToLatest(DataSource dataSource) {
        migrate(dataSource, MigrationVersion.LATEST);
    }

    private static void migrate(DataSource dataSource, MigrationVersion target) {
        // Mirrors spring.flyway.baseline-on-migrate / baseline-version in both
        // application.yml files — see the comment there for why this is needed, and
        // why baselining below every real migration still applies all of them.
        Flyway.configure()
                .dataSource(dataSource)
                .locations(MIGRATION_LOCATIONS)
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("0"))
                .target(target)
                .load()
                .migrate();
    }

    private static DataSource sqliteDataSource(Path dbDirectory) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + dbDirectory.resolve("locklane.db"));
        return dataSource;
    }

    public static WorktreeSessionRepository newRepository(Path dbDirectory) {
        return new WorktreeSessionRepository(newDataSource(dbDirectory));
    }

    public static UserRepository newUserRepository(Path dbDirectory) {
        return new UserRepository(newDataSource(dbDirectory));
    }

    public static ProjectRepository newProjectRepository(Path dbDirectory) {
        return new ProjectRepository(newDataSource(dbDirectory));
    }

    /**
     * Over the same on-disk file as {@link #newProjectRepository} given the same
     * {@code dbDirectory} — both point at {@code <dbDirectory>/locklane.db}, so a
     * test can seed a GitHub account (#550) here and reference it by id through a
     * {@link ProjectRepository} built the same way.
     */
    public static GhAccountRepository newGhAccountRepository(Path dbDirectory) {
        return new GhAccountRepository(newDataSource(dbDirectory));
    }

    public static BackupCodeRepository newBackupCodeRepository(Path dbDirectory) {
        return new BackupCodeRepository(newDataSource(dbDirectory));
    }

    /**
     * A {@link WorktreeSessionAuthorization} over a fresh, empty, throwaway
     * database of its own — for the many pre-#242 tests (worktree creation, the
     * cleanup sweep, project deletion) that only ever call it with a {@code null}
     * requestingUsername, the internal-bypass case documented on
     * {@link WorktreeSessionAuthorization#isVisibleTo(String, String)}: neither
     * backing repository is ever actually consulted, so it needs no relationship to
     * whatever project/session data the test itself set up.
     */
    public static WorktreeSessionAuthorization newNoopAuthorization() {
        try {
            Path dbDirectory = Files.createTempDirectory("noop-authorization");
            return new WorktreeSessionAuthorization(newProjectRepository(dbDirectory), newUserRepository(dbDirectory));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
