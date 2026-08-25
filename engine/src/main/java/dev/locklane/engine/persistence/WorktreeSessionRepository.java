package dev.locklane.engine.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Durable worktree/session state in SQLite — survives a server restart, unlike the
 * in-memory session map in {@code dev.locklane.engine.pty.SessionRegistry}.
 */
@Repository
public class WorktreeSessionRepository {

    private final JdbcTemplate jdbcTemplate;

    public WorktreeSessionRepository(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /** Records that a worktree was attached to, inserting it the first time it is seen. */
    public void recordAttach(String worktreeId, Path workingDirectory, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO worktree_sessions (worktree_id, working_directory, created_at, last_attached_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(worktree_id) DO UPDATE SET last_attached_at = excluded.last_attached_at
                """,
                worktreeId, workingDirectory.toString(), now.toString(), now.toString());
    }

    public Optional<WorktreeSessionRecord> find(String worktreeId) {
        return jdbcTemplate.query(
                """
                SELECT worktree_id, working_directory, created_at, last_attached_at
                FROM worktree_sessions WHERE worktree_id = ?
                """,
                (rs, rowNum) -> toRecord(rs),
                worktreeId
        ).stream().findFirst();
    }

    public List<WorktreeSessionRecord> findAll() {
        return jdbcTemplate.query(
                "SELECT worktree_id, working_directory, created_at, last_attached_at FROM worktree_sessions",
                (rs, rowNum) -> toRecord(rs));
    }

    private static WorktreeSessionRecord toRecord(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new WorktreeSessionRecord(
                rs.getString("worktree_id"),
                Path.of(rs.getString("working_directory")),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("last_attached_at")));
    }
}
