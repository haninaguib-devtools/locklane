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
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        // The sqlite-jdbc driver only runs the first statement of a multi-statement
        // string passed to Statement.execute() — schema.sql needs each CREATE TABLE
        // run individually, unlike Boot's own script-init (spring.sql.init.mode),
        // which already splits on ";" correctly. Comment lines are stripped first so
        // a ";" inside a comment (schema.sql has one) is never mistaken for a
        // statement boundary.
        for (String statement : withoutCommentLines(readSchema()).split(";")) {
            if (!statement.isBlank()) {
                jdbcTemplate.execute(statement.trim());
            }
        }
        return dataSource;
    }

    private static String withoutCommentLines(String sql) {
        return sql.lines()
                .filter(line -> !line.strip().startsWith("--"))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
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

    private static String readSchema() {
        try (InputStream in = TestSqliteDatabases.class.getResourceAsStream("/schema.sql")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Could not read /schema.sql from the test classpath", e);
        }
    }
}
