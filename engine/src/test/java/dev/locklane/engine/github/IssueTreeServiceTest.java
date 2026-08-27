package dev.locklane.engine.github;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Covers #21's done-when: initiatives nest their direct children, edge cases handled explicitly. */
class IssueTreeServiceTest {

    @Test
    void anInitiativesDirectChildrenNestBeneathIt() {
        IssueTreeService service = service(
                initiative(1, "Rebuild the app"),
                task(2, "Server piece", partOf(1)),
                task(3, "Client piece", partOf(1)));

        List<TreeNode> tree = service.tree();

        assertThat(tree).hasSize(1);
        TreeNode initiative = tree.get(0);
        assertThat(initiative.kind()).isEqualTo("INITIATIVE");
        assertThat(initiative.children()).extracting(TreeNode::number).containsExactlyInAnyOrder(2, 3);
        assertThat(initiative.children()).allMatch(c -> c.kind().equals("TASK"));
    }

    @Test
    void aTaskWithNoPartOfIsStandalone() {
        IssueTreeService service = service(task(9, "Loose end", ""));

        List<TreeNode> tree = service.tree();

        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).number()).isEqualTo(9);
        assertThat(tree.get(0).children()).isEmpty();
    }

    @Test
    void aTaskPointingAtANonInitiativeIsStandaloneNotDropped() {
        // #21's explicit done-when: Part of: pointing at something that isn't
        // actually labeled "initiative" must not silently vanish.
        IssueTreeService service = service(
                task(1, "Just a task, not an initiative"),
                task(2, "Points at #1", partOf(1)));

        List<TreeNode> tree = service.tree();

        assertThat(tree).extracting(TreeNode::number).containsExactlyInAnyOrder(1, 2);
        assertThat(tree).allMatch(n -> n.children().isEmpty());
    }

    @Test
    void aTaskPointingAtANonexistentIssueIsStandalone() {
        IssueTreeService service = service(task(5, "Orphaned Part of", partOf(999)));

        List<TreeNode> tree = service.tree();

        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).number()).isEqualTo(5);
        assertThat(tree.get(0).children()).isEmpty();
    }

    @Test
    void anInitiativeWithNoChildrenStillAppearsWithAnEmptyChildrenList() {
        IssueTreeService service = service(initiative(1, "Lonely initiative"));

        List<TreeNode> tree = service.tree();

        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).kind()).isEqualTo("INITIATIVE");
        assertThat(tree.get(0).children()).isEmpty();
    }

    @Test
    void closedIssuesStillAppearWithTheirState() {
        IssueTreeService service = service(closedTask(4, "Shipped already"));

        List<TreeNode> tree = service.tree();

        assertThat(tree.get(0).state()).isEqualTo("CLOSED");
    }

    @Test
    void topLevelNodesInterleaveNewestFirstRegardlessOfKind() {
        // #145: no more initiative-first grouping -- a newer standalone task sorts
        // above an older initiative.
        IssueTreeService service = service(
                createdAt(initiative(1, "Older initiative"), "2024-01-01T00:00:00Z"),
                createdAt(task(2, "Newer standalone task"), "2024-06-01T00:00:00Z"));

        List<TreeNode> tree = service.tree();

        assertThat(tree).extracting(TreeNode::number).containsExactly(2, 1);
    }

    @Test
    void initiativeChildrenAlsoSortNewestFirst() {
        IssueTreeService service = service(
                initiative(1, "Initiative"),
                createdAt(task(2, "Older child", partOf(1)), "2024-01-01T00:00:00Z"),
                createdAt(task(3, "Newer child", partOf(1)), "2024-06-01T00:00:00Z"));

        List<TreeNode> tree = service.tree();

        assertThat(tree.get(0).children()).extracting(TreeNode::number).containsExactly(3, 2);
    }

    @Test
    void hasActiveBranchReflectsWhetherAWipPrExistsForTheIssue() {
        FakeGhClient fake = new FakeGhClient(List.of(task(2, "Has a PR"), task(3, "No PR")));
        fake.pullRequests = List.of(new GhPullRequest(1, "Has a PR", "OPEN", false, "wip/2-has-a-pr"));
        GhIssueCache cache = new GhIssueCache(fake);
        cache.refresh();

        List<TreeNode> tree = new IssueTreeService(cache).tree();

        assertThat(tree).filteredOn(n -> n.number() == 2).extracting(TreeNode::hasActiveBranch).containsExactly(true);
        assertThat(tree).filteredOn(n -> n.number() == 3).extracting(TreeNode::hasActiveBranch).containsExactly(false);
    }

    @Test
    void hasActiveBranchIsCheckedOnInitiativesAndTheirChildrenAlike() {
        FakeGhClient fake = new FakeGhClient(List.of(initiative(1, "Initiative"), task(2, "Child", partOf(1))));
        fake.pullRequests = List.of(new GhPullRequest(1, "Child", "OPEN", false, "wip/2-child"));
        GhIssueCache cache = new GhIssueCache(fake);
        cache.refresh();

        List<TreeNode> tree = new IssueTreeService(cache).tree();

        assertThat(tree.get(0).hasActiveBranch()).isFalse();
        assertThat(tree.get(0).children().get(0).hasActiveBranch()).isTrue();
    }

    private static GhIssue createdAt(GhIssue issue, String createdAt) {
        return new GhIssue(
                issue.number(), issue.title(), issue.state(), issue.labels(), issue.body(), createdAt, issue.updatedAt());
    }

    private static String partOf(int number) {
        return "Part of: #" + number;
    }

    private static GhIssue initiative(int number, String title) {
        return new GhIssue(number, title, "OPEN", List.of("initiative"), "", "", "");
    }

    private static GhIssue task(int number, String title, String body) {
        return new GhIssue(number, title, "OPEN", List.of(), body, "", "");
    }

    private static GhIssue task(int number, String title) {
        return task(number, title, "");
    }

    private static GhIssue closedTask(int number, String title) {
        return new GhIssue(number, title, "CLOSED", List.of(), "", "", "");
    }

    private static IssueTreeService service(GhIssue... issues) {
        FakeGhClient fake = new FakeGhClient(List.of(issues));
        GhIssueCache cache = new GhIssueCache(fake);
        cache.refresh();
        return new IssueTreeService(cache);
    }

    private static final class FakeGhClient implements GhClient {
        private final List<GhIssue> issues;
        private List<GhPullRequest> pullRequests = List.of();

        FakeGhClient(List<GhIssue> issues) {
            this.issues = issues;
        }

        @Override
        public List<GhIssue> issues() {
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
