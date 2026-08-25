package dev.locklane.engine.github;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class IssueControllerTest {

    @Test
    void listReturnsWhatTheCacheHolds(@TempDir Path root) {
        IssueController controller = controller(root, List.of(
                new GhIssue(1, "First", "OPEN", List.of(), "", "", "")));

        assertThat(controller.list()).extracting(GhIssue::number).containsExactly(1);
    }

    @Test
    void detailReturnsTheMatchingIssue(@TempDir Path root) {
        GhIssue two = new GhIssue(2, "Second", "OPEN", List.of(), "body", "", "");
        IssueController controller = controller(root, List.of(
                new GhIssue(1, "First", "OPEN", List.of(), "", "", ""), two));

        ResponseEntity<GhIssue> response = controller.detail(2);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(two);
    }

    @Test
    void detailIsNotFoundForAnUnknownIssue(@TempDir Path root) {
        IssueController controller = controller(root, List.of());

        ResponseEntity<GhIssue> response = controller.detail(404);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void issueDetailReturnsFlowStateForAKnownIssue(@TempDir Path root) {
        IssueController controller = controller(root, List.of(
                new GhIssue(1, "First", "OPEN", List.of(), "", "", "")));

        ResponseEntity<IssueDetail> response = controller.issueDetail(1);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().number()).isEqualTo(1);
    }

    @Test
    void issueDetailIsNotFoundForAnUnknownIssue(@TempDir Path root) {
        IssueController controller = controller(root, List.of());

        ResponseEntity<IssueDetail> response = controller.issueDetail(404);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static IssueController controller(Path root, List<GhIssue> issues) {
        FixedGhClient fake = new FixedGhClient(issues);
        GhIssueCache cache = new GhIssueCache(fake);
        IssueDetailService detailService = new IssueDetailService(cache, fake, root.toString());
        IssueTreeService treeService = new IssueTreeService(cache);
        return new IssueController(cache, detailService, treeService);
    }

    private static final class FixedGhClient implements GhClient {
        private final List<GhIssue> issues;

        FixedGhClient(List<GhIssue> issues) {
            this.issues = issues;
        }

        @Override
        public List<GhIssue> issues() {
            return issues;
        }

        @Override
        public List<GhPullRequest> pullRequests() {
            return List.of();
        }

        @Override
        public Optional<GhPullRequestDetail> pullRequestDetail(int number) {
            return Optional.empty();
        }
    }
}
