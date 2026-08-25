package dev.locklane.engine.github;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers #4's and #16's done-when directly, against a fake {@link GhClient} — no
 * real gh process, no Spring context, no timing dependency on the scheduled refresh.
 */
class GhIssueCacheTest {

    @Test
    void aColdCacheFallsBackToALiveFetch() {
        FakeGhClient fake = new FakeGhClient(List.of(issue(1, "First", "OPEN")), List.of());
        GhIssueCache cache = new GhIssueCache(fake);

        List<GhIssue> result = cache.issues();

        assertThat(result).extracting(GhIssue::number).containsExactly(1);
        assertThat(fake.issueCallCount()).isEqualTo(1);
    }

    @Test
    void aWarmCacheDoesNotRefetchOnEveryCall() {
        FakeGhClient fake = new FakeGhClient(List.of(issue(1, "First", "OPEN")), List.of());
        GhIssueCache cache = new GhIssueCache(fake);

        cache.refresh();
        cache.issues();
        cache.issues();
        cache.issues();

        // One call from refresh(); issues() served all three reads from memory.
        assertThat(fake.issueCallCount()).isEqualTo(1);
    }

    @Test
    void refreshReplacesThePreviouslyCachedData() {
        FakeGhClient fake = new FakeGhClient(List.of(issue(1, "First", "OPEN")), List.of());
        GhIssueCache cache = new GhIssueCache(fake);
        cache.refresh();

        fake.setIssues(List.of(issue(1, "First", "OPEN"), issue(2, "Second", "OPEN")));
        cache.refresh();

        assertThat(cache.issues()).extracting(GhIssue::number).containsExactly(1, 2);
    }

    @Test
    void aFailedRefreshKeepsServingTheLastGoodData() {
        FakeGhClient fake = new FakeGhClient(List.of(issue(1, "First", "OPEN")), List.of());
        GhIssueCache cache = new GhIssueCache(fake);
        cache.refresh(); // warms the cache with issue 1

        fake.failNextCall();
        cache.refresh(); // this attempt fails

        assertThat(cache.issues()).extracting(GhIssue::number).containsExactly(1);
    }

    @Test
    void issueLooksUpByNumberAndIsEmptyWhenUnknown() {
        FakeGhClient fake = new FakeGhClient(List.of(issue(1, "First", "OPEN"), issue(2, "Second", "OPEN")), List.of());
        GhIssueCache cache = new GhIssueCache(fake);

        assertThat(cache.issue(2)).map(GhIssue::title).contains("Second");
        assertThat(cache.issue(99)).isEmpty();
    }

    @Test
    void pullRequestForIssueMatchesOnTheWipBranchConvention() {
        FakeGhClient fake = new FakeGhClient(List.of(), List.of(
                pr(10, "OPEN", "wip/2-some-slug"),
                pr(11, "OPEN", "wip/3-other-slug")));
        GhIssueCache cache = new GhIssueCache(fake);

        assertThat(cache.pullRequestForIssue(2)).map(GhPullRequest::number).contains(10);
        assertThat(cache.pullRequestForIssue(3)).map(GhPullRequest::number).contains(11);
        assertThat(cache.pullRequestForIssue(99)).isEmpty();
    }

    @Test
    void pullRequestForIssueIgnoresBranchesThatDoNotMatchTheConvention() {
        FakeGhClient fake = new FakeGhClient(List.of(), List.of(
                pr(10, "OPEN", "some-random-branch"),
                pr(11, "OPEN", "fix/typo")));
        GhIssueCache cache = new GhIssueCache(fake);

        assertThat(cache.pullRequestForIssue(10)).isEmpty();
    }

    @Test
    void pullRequestForIssuePicksTheNewestWhenMoreThanOneMatches() {
        FakeGhClient fake = new FakeGhClient(List.of(), List.of(
                pr(10, "CLOSED", "wip/2-first-attempt"),
                pr(20, "OPEN", "wip/2-second-attempt")));
        GhIssueCache cache = new GhIssueCache(fake);

        assertThat(cache.pullRequestForIssue(2)).map(GhPullRequest::number).contains(20);
    }

    private static GhIssue issue(int number, String title, String state) {
        return new GhIssue(number, title, state, List.of(), "", "", "");
    }

    private static GhPullRequest pr(int number, String state, String headRefName) {
        return new GhPullRequest(number, "PR " + number, state, false, headRefName);
    }

    private static final class FakeGhClient implements GhClient {
        private List<GhIssue> issues;
        private final List<GhPullRequest> pullRequests;
        private final AtomicInteger issueCalls = new AtomicInteger();
        private boolean failNext;

        FakeGhClient(List<GhIssue> issues, List<GhPullRequest> pullRequests) {
            this.issues = issues;
            this.pullRequests = pullRequests;
        }

        void setIssues(List<GhIssue> issues) {
            this.issues = issues;
        }

        void failNextCall() {
            this.failNext = true;
        }

        int issueCallCount() {
            return issueCalls.get();
        }

        @Override
        public List<GhIssue> issues() {
            issueCalls.incrementAndGet();
            if (failNext) {
                failNext = false;
                throw new GhUnavailableException("simulated failure", null);
            }
            return issues;
        }

        @Override
        public List<GhPullRequest> pullRequests() {
            return pullRequests;
        }

        @Override
        public Optional<GhPullRequestDetail> pullRequestDetail(int number) {
            return Optional.empty();
        }
    }
}
