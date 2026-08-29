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

    /**
     * Inserts a new project in {@link ProjectStatus#CLONING}, {@code default_branch}
     * unset. {@code ownerUserId} (#239) is always the authenticated caller that
     * requested it — {@link ProjectController} never lets it be anyone else's id.
     */
    public ProjectRecord create(String name, String gitUrl, Path workareaPath, long ownerUserId, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO projects (name, git_url, workarea_path, default_branch, status, created_at, owner_user_id)
                VALUES (?, ?, ?, NULL, ?, ?, ?)
                """,
                name, gitUrl, workareaPath.toString(), ProjectStatus.CLONING.name(), now.toString(), ownerUserId);
        return findByWorkareaPath(workareaPath).orElseThrow();
    }

    /**
     * Inserts a project that is already checked out and usable (#43's bootstrap of
     * the engine's own existing checkout) — skips {@link ProjectStatus#CLONING}
     * entirely since there is nothing to clone. {@code ownerUserId} (#239) same as
     * {@link #create}.
     */
    public ProjectRecord createReady(String name, String gitUrl, Path workareaPath, String defaultBranch,
            long ownerUserId, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO projects (name, git_url, workarea_path, default_branch, status, created_at, owner_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                name, gitUrl, workareaPath.toString(), defaultBranch, ProjectStatus.READY.name(), now.toString(),
                ownerUserId);
        return findByWorkareaPath(workareaPath).orElseThrow();
    }

    public Optional<ProjectRecord> findById(long id) {
        return jdbcTemplate.query(
                "SELECT id, name, git_url, workarea_path, default_branch, status, created_at, owner_user_id "
                        + "FROM projects WHERE id = ?",
                (rs, rowNum) -> toRecord(rs),
                id
        ).stream().findFirst();
    }

    public Optional<ProjectRecord> findByWorkareaPath(Path workareaPath) {
        return jdbcTemplate.query(
                "SELECT id, name, git_url, workarea_path, default_branch, status, created_at, owner_user_id "
                        + "FROM projects WHERE workarea_path = ?",
                (rs, rowNum) -> toRecord(rs),
                workareaPath.toString()
        ).stream().findFirst();
    }

    /** Every project, regardless of owner — for an admin caller only (#239). */
    public List<ProjectRecord> findAll() {
        return jdbcTemplate.query(
                "SELECT id, name, git_url, workarea_path, default_branch, status, created_at, owner_user_id "
                        + "FROM projects",
                (rs, rowNum) -> toRecord(rs));
    }

    /** Only the projects owned by {@code ownerUserId} (#239) — an ordinary caller's view. */
    public List<ProjectRecord> findAllOwnedBy(long ownerUserId) {
        return jdbcTemplate.query(
                "SELECT id, name, git_url, workarea_path, default_branch, status, created_at, owner_user_id "
                        + "FROM projects WHERE owner_user_id = ?",
                (rs, rowNum) -> toRecord(rs),
                ownerUserId);
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

    /** Stores the encrypted GitHub token (#81) — callers encrypt/decrypt via {@code TokenCipher}; this never sees plaintext. */
    public void setGithubToken(long id, String encryptedToken) {
        jdbcTemplate.update("UPDATE projects SET github_token = ? WHERE id = ?", encryptedToken, id);
    }

    /** The stored (still encrypted) token, if any. Empty when none is set — never a blank/null string. */
    public Optional<String> findGithubToken(long id) {
        List<String> rows = jdbcTemplate.query(
                "SELECT github_token FROM projects WHERE id = ?",
                (rs, rowNum) -> rs.getString("github_token"),
                id);
        // Stream.findFirst() would NPE on a null element (it wraps via Optional.of
        // internally) -- the column is nullable, so a plain index/blank check first.
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        String token = rows.get(0);
        return (token == null || token.isBlank()) ? Optional.empty() : Optional.of(token);
    }

    private static ProjectRecord toRecord(ResultSet rs) throws SQLException {
        return new ProjectRecord(
                rs.getLong("id"),
                rs.getLong("owner_user_id"),
                rs.getString("name"),
                rs.getString("git_url"),
                Path.of(rs.getString("workarea_path")),
                rs.getString("default_branch"),
                ProjectStatus.valueOf(rs.getString("status")),
                Instant.parse(rs.getString("created_at")));
    }
}
