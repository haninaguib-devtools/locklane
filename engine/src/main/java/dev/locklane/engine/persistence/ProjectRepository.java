package dev.locklane.engine.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Durable project state in SQLite (#42) — survives a server restart. */
@Repository
public class ProjectRepository {

    private final JdbcTemplate jdbcTemplate;

    public ProjectRepository(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /** Inserts a new project in {@link ProjectStatus#CLONING}, {@code default_branch} unset. */
    public ProjectRecord create(String name, String gitUrl, Path workareaPath, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO projects (name, git_url, workarea_path, default_branch, status, created_at)
                VALUES (?, ?, ?, NULL, ?, ?)
                """,
                name, gitUrl, workareaPath.toString(), ProjectStatus.CLONING.name(), now.toString());
        return findByWorkareaPath(workareaPath).orElseThrow();
    }

    public Optional<ProjectRecord> findById(long id) {
        return jdbcTemplate.query(
                "SELECT id, name, git_url, workarea_path, default_branch, status, created_at FROM projects WHERE id = ?",
                (rs, rowNum) -> toRecord(rs),
                id
        ).stream().findFirst();
    }

    public Optional<ProjectRecord> findByWorkareaPath(Path workareaPath) {
        return jdbcTemplate.query(
                "SELECT id, name, git_url, workarea_path, default_branch, status, created_at "
                        + "FROM projects WHERE workarea_path = ?",
                (rs, rowNum) -> toRecord(rs),
                workareaPath.toString()
        ).stream().findFirst();
    }

    public List<ProjectRecord> findAll() {
        return jdbcTemplate.query(
                "SELECT id, name, git_url, workarea_path, default_branch, status, created_at FROM projects",
                (rs, rowNum) -> toRecord(rs));
    }

    /** Moves a project back to {@link ProjectStatus#CLONING}, clearing any previous default branch. */
    public void markCloning(long id) {
        jdbcTemplate.update("UPDATE projects SET status = ?, default_branch = NULL WHERE id = ?",
                ProjectStatus.CLONING.name(), id);
    }

    public void markReady(long id, String defaultBranch) {
        jdbcTemplate.update("UPDATE projects SET status = ?, default_branch = ? WHERE id = ?",
                ProjectStatus.READY.name(), defaultBranch, id);
    }

    public void markFailed(long id) {
        jdbcTemplate.update("UPDATE projects SET status = ? WHERE id = ?", ProjectStatus.FAILED.name(), id);
    }

    public void delete(long id) {
        jdbcTemplate.update("DELETE FROM projects WHERE id = ?", id);
    }

    private static ProjectRecord toRecord(ResultSet rs) throws SQLException {
        return new ProjectRecord(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("git_url"),
                Path.of(rs.getString("workarea_path")),
                rs.getString("default_branch"),
                ProjectStatus.valueOf(rs.getString("status")),
                Instant.parse(rs.getString("created_at")));
    }
}
