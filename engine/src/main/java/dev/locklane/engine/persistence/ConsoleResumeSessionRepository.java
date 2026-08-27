package dev.locklane.engine.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;

/**
 * Durable store of the Claude/Codex resume ids captured from console output (#102) —
 * survives a server restart, like {@link WorktreeSessionRepository}. Rows are never
 * deleted when a console session is closed: outliving the console process is the
 * point, so a past conversation stays resumable (#101). One row per distinct
 * (console, resume id); seeing the same id again only refreshes {@code captured_at}.
 */
@Repository
public class ConsoleResumeSessionRepository {

    private final JdbcTemplate jdbcTemplate;

    public ConsoleResumeSessionRepository(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    public void record(String worktreeId, String tool, String resumeId, Instant capturedAt) {
        jdbcTemplate.update("""
                INSERT INTO console_resume_sessions (worktree_id, tool, resume_id, captured_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(worktree_id, resume_id)
                DO UPDATE SET tool = excluded.tool, captured_at = excluded.captured_at
                """,
                worktreeId, tool, resumeId, capturedAt.toString());
    }

    /** Every resume id captured in this console, oldest sighting first. */
    public List<ConsoleResumeSessionRecord> findByWorktree(String worktreeId) {
        return jdbcTemplate.query(
                """
                SELECT worktree_id, tool, resume_id, captured_at
                FROM console_resume_sessions WHERE worktree_id = ? ORDER BY captured_at
                """,
                (rs, rowNum) -> toRecord(rs),
                worktreeId);
    }

    public List<ConsoleResumeSessionRecord> findAll() {
        return jdbcTemplate.query(
                "SELECT worktree_id, tool, resume_id, captured_at FROM console_resume_sessions ORDER BY captured_at",
                (rs, rowNum) -> toRecord(rs));
    }

    private static ConsoleResumeSessionRecord toRecord(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ConsoleResumeSessionRecord(
                rs.getString("worktree_id"),
                rs.getString("tool"),
                rs.getString("resume_id"),
                Instant.parse(rs.getString("captured_at")));
    }
}
