package dev.locklane.engine.persistence;

import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * A schema-initialized SQLite database backed by a file on disk, for tests. Public:
 * shared across test packages (e.g. {@code dev.locklane.engine.pty}), which need a
 * real repository to construct a {@code SessionRegistry} without a full Spring
 * context.
 */
public final class TestSqliteDatabases {

    private TestSqliteDatabases() {
    }

    public static DataSource newDataSource(Path dbDirectory) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + dbDirectory.resolve("locklane.db"));
        new JdbcTemplate(dataSource).execute(readSchema());
        return dataSource;
    }

    public static WorktreeSessionRepository newRepository(Path dbDirectory) {
        return new WorktreeSessionRepository(newDataSource(dbDirectory));
    }

    private static String readSchema() {
        try (InputStream in = TestSqliteDatabases.class.getResourceAsStream("/schema.sql")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Could not read /schema.sql from the test classpath", e);
        }
    }
}
