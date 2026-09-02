package dev.locklane.engine.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
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
        return create(name, gitUrl, workareaPath, ownerUserId, now, null);
    }

    /**
     * Same as {@link #create(String, String, Path, long, Instant)}, recording the name of
     * the project template (#536) the project is being created from — {@code null} when
     * there is none. The only place the column is ever written.
     */
    public ProjectRecord create(String name, String gitUrl, Path workareaPath, long ownerUserId, Instant now,
            String template) {
        jdbcTemplate.update("""
                INSERT INTO projects (name, git_url, workarea_path, default_branch, status, created_at, owner_user_id,
                                      template, sort_order)
                VALUES (?, ?, ?, NULL, ?, ?, ?, ?, ?)
                """,
                name, gitUrl, workareaPath.toString(), ProjectStatus.CLONING.name(), now.toString(), ownerUserId,
                template, nextSortOrder(ownerUserId));
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
                INSERT INTO projects (name, git_url, workarea_path, default_branch, status, created_at, owner_user_id,
                                      sort_order)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                name, gitUrl, workareaPath.toString(), defaultBranch, ProjectStatus.READY.name(), now.toString(),
                ownerUserId, nextSortOrder(ownerUserId));
        return findByWorkareaPath(workareaPath).orElseThrow();
    }

    /** The next free position (#541) at the end of {@code ownerUserId}'s current order — 0 for their first project. */
    private int nextSortOrder(long ownerUserId) {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT MAX(sort_order) FROM projects WHERE owner_user_id = ?", Integer.class, ownerUserId);
        return max == null ? 0 : max + 1;
    }

    public Optional<ProjectRecord> findById(long id) {
        return jdbcTemplate.query(
                "SELECT id, name, git_url, workarea_path, default_branch, status, created_at, owner_user_id, "
                        + "accent_color, template, template_seeded_at, sort_order FROM projects WHERE id = ?",
                (rs, rowNum) -> toRecord(rs),
                id
        ).stream().findFirst();
    }

    public Optional<ProjectRecord> findByWorkareaPath(Path workareaPath) {
        return jdbcTemplate.query(
                "SELECT id, name, git_url, workarea_path, default_branch, status, created_at, owner_user_id, "
                        + "accent_color, template, template_seeded_at, sort_order FROM projects WHERE workarea_path = ?",
                (rs, rowNum) -> toRecord(rs),
                workareaPath.toString()
        ).stream().findFirst();
    }

    /** Every project, regardless of owner — for an admin caller only (#239). */
    public List<ProjectRecord> findAll() {
        return jdbcTemplate.query(
                "SELECT id, name, git_url, workarea_path, default_branch, status, created_at, owner_user_id, "
                        + "accent_color, template, template_seeded_at, sort_order FROM projects",
                (rs, rowNum) -> toRecord(rs));
    }

    /**
     * Only the projects owned by {@code ownerUserId} (#239) — an ordinary caller's
     * view, in that owner's own persisted order (#541): ascending {@code sort_order},
     * {@code id} breaking a tie between two rows that (should never, but) share one.
     */
    public List<ProjectRecord> findAllOwnedBy(long ownerUserId) {
        return jdbcTemplate.query(
                "SELECT id, name, git_url, workarea_path, default_branch, status, created_at, owner_user_id, "
                        + "accent_color, template, template_seeded_at, sort_order FROM projects "
                        + "WHERE owner_user_id = ? ORDER BY sort_order, id",
                (rs, rowNum) -> toRecord(rs),
                ownerUserId);
    }

    /**
     * Persists a new order (#541) for exactly the projects in {@code orderedIds} — each
     * id's position in the list becomes its {@code sort_order}. Callers are responsible
     * for checking ownership of every id first; this does not filter by owner itself.
     */
    public void setOrder(List<Long> orderedIds) {
        List<Object[]> batchArgs = new ArrayList<>();
        for (int i = 0; i < orderedIds.size(); i++) {
            batchArgs.add(new Object[] {i, orderedIds.get(i)});
        }
        jdbcTemplate.batchUpdate("UPDATE projects SET sort_order = ? WHERE id = ?", batchArgs);
    }

    /** The GitHub account (#550) this project acts as, when one has been chosen. */
    public void setGithubAccountId(long id, long githubAccountId) {
        jdbcTemplate.update("UPDATE projects SET github_account_id = ? WHERE id = ?", githubAccountId, id);
    }

    /** Empty when the project has no chosen GitHub account — never falls back to any other identity (#550). */
    public Optional<Long> findGithubAccountId(long id) {
        // Not ResultSet#getObject(): SQLite/xerial can hand back a plain Integer for
        // an INTEGER column depending on the stored value's magnitude, and casting
        // that directly to Long throws. getLong() + wasNull() sidesteps the boxing
        // entirely.
        List<Long> rows = jdbcTemplate.query(
                "SELECT github_account_id FROM projects WHERE id = ?",
                (rs, rowNum) -> {
                    long value = rs.getLong("github_account_id");
                    return rs.wasNull() ? null : value;
                },
                id);
        if (rows.isEmpty() || rows.get(0) == null) {
            return Optional.empty();
        }
        return Optional.of(rows.get(0));
    }

    /** The names of every project still acting as {@code githubAccountId} — the 409-on-remove check (#550). */
    public List<String> findNamesReferencingGithubAccount(long githubAccountId) {
        return jdbcTemplate.query(
                "SELECT name FROM projects WHERE github_account_id = ?",
                (rs, rowNum) -> rs.getString("name"),
                githubAccountId);
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

    /** Sets or clears (#427) the project's accent color — {@code accentColor} may be {@code null}. */
    public void setAccentColor(long id, String accentColor) {
        jdbcTemplate.update("UPDATE projects SET accent_color = ? WHERE id = ?", accentColor, id);
    }

    /**
     * Records that the project's one seeded console has been launched (#537), at
     * {@code now}. Written exactly once per project, by the WebSocket attach that
     * performed the launch; never cleared.
     */
    public void markTemplateSeeded(long id, Instant now) {
        jdbcTemplate.update("UPDATE projects SET template_seeded_at = ? WHERE id = ?", now.toString(), id);
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
                Instant.parse(rs.getString("created_at")),
                rs.getString("accent_color"),
                rs.getString("template"),
                Optional.ofNullable(rs.getString("template_seeded_at")).map(Instant::parse).orElse(null),
                rs.getInt("sort_order"));
    }
}
