package dev.locklane.engine.github;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers #4's done-when directly, against a fake {@link GhClient} — no real gh
 * process, no Spring context, no timing dependency on the scheduled refresh.
 */
class GhIssueCacheTest {

    @Test
    void aColdCacheFallsBackToALiveFetch() {
        FakeGhClient fake = new FakeGhClient(List.of(issue(1, "First")));
        GhIssueCache cache = new GhIssueCache(fake);

        List<GhIssue> result = cache.issues();

        assertThat(result).extracting(GhIssue::number).containsExactly(1);
        assertThat(fake.callCount()).isEqualTo(1);
    }

    @Test
    void aWarmCacheDoesNotRefetchOnEveryCall() {
        FakeGhClient fake = new FakeGhClient(List.of(issue(1, "First")));
        GhIssueCache cache = new GhIssueCache(fake);

        cache.refresh();
        cache.issues();
        cache.issues();
        cache.issues();

        // One call from refresh(); issues() served all three reads from memory.
        assertThat(fake.callCount()).isEqualTo(1);
    }

    @Test
    void refreshReplacesThePreviouslyCachedData() {
        FakeGhClient fake = new FakeGhClient(List.of(issue(1, "First")));
        GhIssueCache cache = new GhIssueCache(fake);
        cache.refresh();

        fake.setIssues(List.of(issue(1, "First"), issue(2, "Second")));
        cache.refresh();

        assertThat(cache.issues()).extracting(GhIssue::number).containsExactly(1, 2);
    }

    @Test
    void aFailedRefreshKeepsServingTheLastGoodData() {
        FakeGhClient fake = new FakeGhClient(List.of(issue(1, "First")));
        GhIssueCache cache = new GhIssueCache(fake);
        cache.refresh(); // warms the cache with issue 1

        fake.failNextCall();
        cache.refresh(); // this attempt fails

        assertThat(cache.issues()).extracting(GhIssue::number).containsExactly(1);
    }

    @Test
    void issueLooksUpByNumberAndIsEmptyWhenUnknown() {
        FakeGhClient fake = new FakeGhClient(List.of(issue(1, "First"), issue(2, "Second")));
        GhIssueCache cache = new GhIssueCache(fake);

        assertThat(cache.issue(2)).map(GhIssue::title).contains("Second");
        assertThat(cache.issue(99)).isEmpty();
    }

    private static GhIssue issue(int number, String title) {
        return new GhIssue(number, title, "OPEN", List.of(), "", "", "");
    }

    private static final class FakeGhClient implements GhClient {
        private List<GhIssue> issues;
        private final AtomicInteger calls = new AtomicInteger();
        private boolean failNext;

        FakeGhClient(List<GhIssue> issues) {
            this.issues = issues;
        }

        void setIssues(List<GhIssue> issues) {
            this.issues = issues;
        }

        void failNextCall() {
            this.failNext = true;
        }

        int callCount() {
            return calls.get();
        }

        @Override
        public List<GhIssue> issues() {
            calls.incrementAndGet();
            if (failNext) {
                failNext = false;
                throw new GhUnavailableException("simulated failure", null);
            }
            return issues;
        }
    }
}
