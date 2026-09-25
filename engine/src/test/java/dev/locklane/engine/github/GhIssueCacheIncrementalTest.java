package dev.locklane.engine.github;

import dev.locklane.engine.github.CliGhClientTest.FakeGh;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #991: a warm cache asks GitHub one conditional question per refresh and fetches only
 * what changed; a cold cache, a forced refresh, and a refresh after the engine's own
 * write each behave as documented on {@link GhIssueCache#refresh()}.
 */
class GhIssueCacheIncrementalTest {

    @Test
    void aWarmCacheWithNothingChangedMakesExactlyOneConditionalGhRequest(@TempDir Path dir) throws Exception {
        // A real CliGhClient against a fake gh that logs every invocation: the change
        // probe answers 304 to a matching If-None-Match, and 200 with that ETag otherwise.
        Path calls = dir.resolve("calls");
        Path fakeGh = FakeGh.script(dir, """
                echo "$*" >> "%s"
                if [ "$1" = "api" ] && [ "$2" = "-i" ]; then
                  if [ "$3" = "-H" ] && [ "$4" = 'If-None-Match: W/"v1"' ]; then
                    printf 'HTTP/2.0 304 Not Modified\\nEtag: W/"v1"\\n\\n'
                    echo 'gh: HTTP 304' >&2
                    exit 1
                  fi
                  printf 'HTTP/2.0 200 OK\\nEtag: W/"v1"\\n\\n[{"number": 2, "updated_at": "2026-09-01T00:00:00Z"}]'
                  exit 0
                fi
                case "$*" in
                  *pullRequests*) echo '{"data": {"repository": {"pullRequests": {"pageInfo": {"hasNextPage": false},
                      "nodes": [{"number": 2, "title": "PR", "state": "OPEN", "isDraft": false, "headRefName": "wip/1-x"}]}}}}' ;;
                  *) echo '{"data": {"repository": {"issues": {"pageInfo": {"hasNextPage": false},
                      "nodes": [{"number": 1, "title": "One", "state": "OPEN", "labels": {"nodes": []}}]}}}}' ;;
                esac
                """.formatted(calls));
        GhIssueCache cache = new GhIssueCache(new CliGhClient(dir, null, fakeGh.toString(), Duration.ofSeconds(20)));
        cache.refresh(); // cold: unconditional probe, then the full lists
        int before = Files.readAllLines(calls).size();

        boolean changed = cache.refresh();

        List<String> tick = Files.readAllLines(calls).subList(before, Files.readAllLines(calls).size());
        assertThat(tick).hasSize(1);
        assertThat(tick.get(0)).startsWith("api -i -H If-None-Match: W/\"v1\" repos/{owner}/{repo}/issues?");
        assertThat(changed).isFalse();
        assertThat(cache.status().failing()).isFalse();
        assertThat(cache.issues()).extracting(GhIssue::number).containsExactly(1);
        assertThat(cache.pullRequests()).extracting(GhPullRequest::number).containsExactly(2);
    }

    @Test
    void afterOneIssueChangesTheNextRefreshFetchesOnlyThatIssueAndMatchesAFullFetch() {
        FakeRepo repo = new FakeRepo();
        repo.issues.add(issue(1, "One", "2026-09-01T00:00:00Z"));
        repo.issues.add(issue(2, "Two", "2026-09-02T00:00:00Z"));
        repo.issues.add(issue(3, "Three", "2026-09-02T00:00:00Z"));
        // The newest item, so the watermark; GitHub's "since" is inclusive, so an item
        // updated exactly at the watermark is fetched again, which is why it is a PR here.
        repo.pullRequests.add(new Stamped<>(pr(4, "OPEN"), "2026-09-03T00:00:00Z"));
        GhIssueCache cache = new GhIssueCache(repo);
        cache.refresh();
        repo.calls.clear();

        repo.issues.set(0, issue(1, "One, renamed", "2026-09-05T00:00:00Z"));
        repo.version++;
        boolean changed = cache.refresh();

        assertThat(changed).isTrue();
        assertThat(repo.calls).containsExactly("probe W/\"1\"", "issuesSince 2026-09-03T00:00:00Z",
                "pullRequestsSince 2026-09-03T00:00:00Z");
        assertThat(repo.lastIssuesReturned).extracting(GhIssue::number).containsExactly(1);
        GhIssueCache full = new GhIssueCache(repo);
        full.refreshFully();
        assertThat(cache.issues()).isEqualTo(full.issues());
        assertThat(cache.pullRequests()).isEqualTo(full.pullRequests());
    }

    @Test
    void anIncrementalFetchPicksUpANewPullRequestAndANewIssue() {
        FakeRepo repo = new FakeRepo();
        repo.issues.add(issue(1, "One", "2026-09-01T00:00:00Z"));
        GhIssueCache cache = new GhIssueCache(repo);
        cache.refresh();

        repo.issues.add(issue(2, "Two", "2026-09-04T00:00:00Z"));
        repo.pullRequests.add(new Stamped<>(pr(3, "OPEN"), "2026-09-04T00:00:00Z"));
        repo.version++;
        cache.refresh();

        assertThat(cache.issues()).extracting(GhIssue::number).containsExactly(2, 1);
        assertThat(cache.pullRequests()).extracting(GhPullRequest::number).containsExactly(3);
    }

    @Test
    void aFailedIncrementalFetchKeepsTheOldEtagSoTheNextProbeStillSeesTheChange() {
        FakeRepo repo = new FakeRepo();
        repo.issues.add(issue(1, "One", "2026-09-01T00:00:00Z"));
        GhIssueCache cache = new GhIssueCache(repo);
        cache.refresh();
        repo.issues.set(0, issue(1, "One, renamed", "2026-09-05T00:00:00Z"));
        repo.version++;

        repo.failFetches = true;
        assertThat(cache.refresh()).isFalse();
        assertThat(cache.status().failing()).isTrue();
        assertThat(cache.issues()).extracting(GhIssue::title).containsExactly("One");

        repo.failFetches = false;
        repo.calls.clear();
        assertThat(cache.refresh()).isTrue();
        assertThat(repo.calls.get(0)).isEqualTo("probe W/\"1\"");
        assertThat(cache.issues()).extracting(GhIssue::title).containsExactly("One, renamed");
    }

    @Test
    void aForcedRefreshFetchesEverythingEvenWhenWarmAndDropsADeletedIssue() {
        FakeRepo repo = new FakeRepo();
        repo.issues.add(issue(1, "One", "2026-09-01T00:00:00Z"));
        repo.issues.add(issue(2, "Two", "2026-09-02T00:00:00Z"));
        GhIssueCache cache = new GhIssueCache(repo);
        cache.refresh();
        repo.issues.remove(1);
        repo.version++;
        repo.calls.clear();

        boolean changed = cache.refreshFully();

        assertThat(changed).isTrue();
        assertThat(repo.calls).containsExactly("probe null", "issues", "pullRequests");
        assertThat(cache.issues()).extracting(GhIssue::number).containsExactly(1);
    }

    @Test
    void aRefreshAfterTheEnginesOwnWriteNeverSettlesForA304ButStillFetchesOnlyWhatChanged() {
        FakeRepo repo = new FakeRepo();
        repo.issues.add(issue(1, "One", "2026-09-01T00:00:00Z"));
        GhIssueCache cache = new GhIssueCache(repo);
        cache.refresh();
        repo.calls.clear();

        cache.refreshAfterWrite();

        assertThat(repo.calls).containsExactly("probe null", "issuesSince 2026-09-01T00:00:00Z",
                "pullRequestsSince 2026-09-01T00:00:00Z");
    }

    private static GhIssue issue(int number, String title, String updatedAt) {
        return new GhIssue(number, title, "OPEN", List.of(), "body " + number, "2026-08-01T00:00:00Z", updatedAt);
    }

    private static GhPullRequest pr(int number, String state) {
        return new GhPullRequest(number, "PR " + number, state, false, "wip/" + number + "-x");
    }

    private record Stamped<T>(T item, String updatedAt) {
    }

    /**
     * A repo whose change probe behaves like GitHub's: the ETag is the repo's version,
     * a matching one answers not-modified, and the newest updated_at comes back with it.
     */
    private static final class FakeRepo implements GhClient {
        final List<GhIssue> issues = new ArrayList<>();
        final List<Stamped<GhPullRequest>> pullRequests = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        int version = 1;
        boolean failFetches;
        List<GhIssue> lastIssuesReturned;

        @Override
        public boolean supportsIncrementalRefresh() {
            return true;
        }

        @Override
        public ChangeProbe probeChanges(String etag) {
            calls.add("probe " + etag);
            String current = "W/\"" + version + "\"";
            if (current.equals(etag)) {
                return ChangeProbe.notModified();
            }
            String newest = null;
            for (GhIssue issue : issues) {
                newest = newest == null || issue.updatedAt().compareTo(newest) > 0 ? issue.updatedAt() : newest;
            }
            for (Stamped<GhPullRequest> pr : pullRequests) {
                newest = newest == null || pr.updatedAt().compareTo(newest) > 0 ? pr.updatedAt() : newest;
            }
            return new ChangeProbe(true, current, newest);
        }

        @Override
        public List<GhIssue> issues() {
            calls.add("issues");
            return List.copyOf(issues);
        }

        @Override
        public List<GhPullRequest> pullRequests() {
            calls.add("pullRequests");
            return pullRequests.stream().map(Stamped::item).toList();
        }

        @Override
        public List<GhIssue> issuesUpdatedSince(String since) {
            calls.add("issuesSince " + since);
            if (failFetches) {
                throw new GhUnavailableException("simulated failure", null);
            }
            lastIssuesReturned = issues.stream().filter(i -> i.updatedAt().compareTo(since) >= 0).toList();
            return lastIssuesReturned;
        }

        @Override
        public List<GhPullRequest> pullRequestsUpdatedSince(String since) {
            calls.add("pullRequestsSince " + since);
            return pullRequests.stream().filter(p -> p.updatedAt().compareTo(since) >= 0).map(Stamped::item).toList();
        }

        @Override
        public Optional<GhPullRequestDetail> pullRequestDetail(int number) {
            return Optional.empty();
        }
    }
}
