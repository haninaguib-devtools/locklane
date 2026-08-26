package dev.locklane.engine.github;

/** One project's GitHub-data wiring, bundled together so it is built and torn down as a unit (#81). */
public record ProjectGhContext(
        GhClient client,
        GhIssueCache cache,
        IssueDetailService detailService,
        IssueTreeService treeService) {
}
