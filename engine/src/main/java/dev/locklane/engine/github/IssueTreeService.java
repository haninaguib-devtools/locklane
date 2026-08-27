package dev.locklane.engine.github;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Derives the initiative/task hierarchy from cached issue data: an issue whose body
 * carries "Part of: #&lt;n&gt;" nests under issue n, but only when n actually
 * carries the "initiative" label — an issue pointing at a non-initiative (or at
 * nothing that exists) is left standalone rather than silently dropped or nested
 * somewhere misleading (#21). Nesting is one level deep only, matching this
 * project's own two-working-levels pipeline rule (AGENTS.md). One instance per
 * project since #81 — not a Spring-managed singleton itself.
 */
public class IssueTreeService {

    private static final Pattern PART_OF = Pattern.compile("Part of:\\s*#(\\d+)");
    private static final String INITIATIVE_LABEL = "initiative";

    /** Newest-created-first, applied to the top-level list and to each initiative's children (#145). */
    private static final Comparator<GhIssue> NEWEST_FIRST = Comparator.comparing(GhIssue::createdAt).reversed();

    private final GhIssueCache cache;

    public IssueTreeService(GhIssueCache cache) {
        this.cache = cache;
    }

    public List<TreeNode> tree() {
        List<GhIssue> issues = cache.issues();
        Set<Integer> initiativeNumbers = issues.stream()
                .filter(i -> i.labels().contains(INITIATIVE_LABEL))
                .map(GhIssue::number)
                .collect(Collectors.toSet());

        Map<Integer, List<GhIssue>> childrenByInitiative = new HashMap<>();
        List<GhIssue> topLevel = new ArrayList<>();
        for (GhIssue issue : issues) {
            if (initiativeNumbers.contains(issue.number())) {
                topLevel.add(issue);
                continue;
            }
            Optional<Integer> parent = partOf(issue).filter(initiativeNumbers::contains);
            if (parent.isPresent()) {
                childrenByInitiative.computeIfAbsent(parent.get(), k -> new ArrayList<>()).add(issue);
            } else {
                topLevel.add(issue);
            }
        }
        topLevel.sort(NEWEST_FIRST);

        List<TreeNode> nodes = new ArrayList<>();
        for (GhIssue issue : topLevel) {
            if (!initiativeNumbers.contains(issue.number())) {
                nodes.add(new TreeNode(
                        issue.number(), issue.title(), "TASK", issue.state(), hasActiveBranch(issue), List.of()));
                continue;
            }
            List<GhIssue> children = childrenByInitiative.getOrDefault(issue.number(), new ArrayList<>());
            children.sort(NEWEST_FIRST);
            List<TreeNode> childNodes = children.stream()
                    .map(child -> new TreeNode(
                            child.number(), child.title(), "TASK", child.state(), hasActiveBranch(child), List.of()))
                    .toList();
            nodes.add(new TreeNode(
                    issue.number(), issue.title(), "INITIATIVE", issue.state(), hasActiveBranch(issue), childNodes));
        }
        return nodes;
    }

    /** Same signal as {@link IssueDetailService#detail(int)}'s {@code branch} field (#110). */
    private boolean hasActiveBranch(GhIssue issue) {
        return cache.pullRequestForIssue(issue.number()).isPresent();
    }

    private static Optional<Integer> partOf(GhIssue issue) {
        Matcher m = PART_OF.matcher(issue.body());
        return m.find() ? Optional.of(Integer.parseInt(m.group(1))) : Optional.empty();
    }
}
