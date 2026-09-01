package dev.locklane.engine.template;

/**
 * One project template (#536): a single markdown file that describes how a kind of
 * project should be built. {@code name} is the directory it was found under (matching
 * {@link TemplateStore#NAME}); {@code title} and {@code description} come from the
 * file's frontmatter and are what the Add Project dialog shows; {@code body} is
 * everything after the frontmatter — the instructions an agent reads later, and the
 * text the engine commits as {@code PROJECT_TEMPLATE.md} into a project created from
 * it. The engine never executes any of it.
 */
public record ProjectTemplate(String name, String title, String description, String body) {
}
