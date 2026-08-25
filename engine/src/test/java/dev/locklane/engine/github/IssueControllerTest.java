package dev.locklane.engine.github;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IssueControllerTest {

    @Test
    void listReturnsWhatTheCacheHolds() {
        GhIssueCache cache = new GhIssueCache(new FixedGhClient(List.of(
                new GhIssue(1, "First", "OPEN", List.of(), "", "", ""))));
        IssueController controller = new IssueController(cache);

        assertThat(controller.list()).extracting(GhIssue::number).containsExactly(1);
    }

    @Test
    void detailReturnsTheMatchingIssue() {
        GhIssue two = new GhIssue(2, "Second", "OPEN", List.of(), "body", "", "");
        GhIssueCache cache = new GhIssueCache(new FixedGhClient(List.of(
                new GhIssue(1, "First", "OPEN", List.of(), "", "", ""), two)));
        IssueController controller = new IssueController(cache);

        ResponseEntity<GhIssue> response = controller.detail(2);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(two);
    }

    @Test
    void detailIsNotFoundForAnUnknownIssue() {
        GhIssueCache cache = new GhIssueCache(new FixedGhClient(List.of()));
        IssueController controller = new IssueController(cache);

        ResponseEntity<GhIssue> response = controller.detail(404);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
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
    }
}
