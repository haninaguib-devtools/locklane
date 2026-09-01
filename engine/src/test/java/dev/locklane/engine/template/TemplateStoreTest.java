package dev.locklane.engine.template;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** #536's template listing over a temporary host directory, merged with the real classpath built-ins. */
class TemplateStoreTest {

    private static final String BUILT_IN = "springboot-angular";

    @Test
    void listsTheBuiltInsWhenTheHostDirectoryDoesNotExist(@TempDir Path tmp) {
        TemplateStore store = new TemplateStore(tmp.resolve("no-such-dir"));

        List<ProjectTemplate> templates = store.list();

        assertThat(templates).extracting(ProjectTemplate::name).contains(BUILT_IN);
        ProjectTemplate builtIn = store.find(BUILT_IN).orElseThrow();
        assertThat(builtIn.title()).isEqualTo("Spring Boot + Angular");
        assertThat(builtIn.description()).isNotBlank();
        assertThat(builtIn.body()).startsWith("# Spring Boot + Angular").doesNotContain("---\ntitle:");
    }

    @Test
    void mergesHostTemplatesWithTheBuiltInsSortedByTitle(@TempDir Path tmp) throws IOException {
        writeTemplate(tmp, "zeta-node", "A Node server", "Express and a Dockerfile", "# Node\n\nBuild it.\n");
        writeTemplate(tmp, "alpha-cli", "Zero-dependency CLI", "", "# CLI\n");
        TemplateStore store = new TemplateStore(tmp);

        List<ProjectTemplate> templates = store.list();

        assertThat(templates).extracting(ProjectTemplate::name).containsExactly("zeta-node", BUILT_IN, "alpha-cli");
        assertThat(templates).extracting(ProjectTemplate::title)
                .containsExactly("A Node server", "Spring Boot + Angular", "Zero-dependency CLI");
        assertThat(store.find("zeta-node")).isPresent().get()
                .satisfies(t -> {
                    assertThat(t.description()).isEqualTo("Express and a Dockerfile");
                    assertThat(t.body()).isEqualTo("# Node\n\nBuild it.\n");
                });
        assertThat(store.find("alpha-cli").orElseThrow().description()).isEmpty();
    }

    @Test
    void aHostTemplateReplacesTheBuiltInOfTheSameName(@TempDir Path tmp) throws IOException {
        writeTemplate(tmp, BUILT_IN, "My own stack", "overrides the built-in", "# Mine\n");
        TemplateStore store = new TemplateStore(tmp);

        List<ProjectTemplate> templates = store.list();

        assertThat(templates).filteredOn(t -> t.name().equals(BUILT_IN)).hasSize(1)
                .first().satisfies(t -> {
                    assertThat(t.title()).isEqualTo("My own stack");
                    assertThat(t.body()).isEqualTo("# Mine\n");
                });
    }

    @Test
    void skipsBadNamesUnreadableFilesAndEntriesWithoutATemplateFile(@TempDir Path tmp) throws IOException {
        writeTemplate(tmp, "good-one", "Good", "", "ok\n");
        writeTemplate(tmp, "Bad_Name", "Never listed", "", "x\n");
        writeTemplate(tmp, "-leading-dash", "Never listed", "", "x\n");
        Files.writeString(Files.createDirectories(tmp.resolve("no-frontmatter")).resolve("template.md"),
                "# just markdown\n");
        Files.writeString(Files.createDirectories(tmp.resolve("no-title")).resolve("template.md"),
                "---\ndescription: only\n---\nbody\n");
        Files.writeString(Files.createDirectories(tmp.resolve("unclosed")).resolve("template.md"),
                "---\ntitle: never closed\nbody\n");
        Files.createDirectories(tmp.resolve("empty-dir"));
        Files.writeString(tmp.resolve("stray-file"), "not a directory");
        TemplateStore store = new TemplateStore(tmp);

        List<ProjectTemplate> templates = store.list();

        assertThat(templates).extracting(ProjectTemplate::name).containsExactly("good-one", BUILT_IN);
        assertThat(store.find("Bad_Name")).isEmpty();
        assertThat(store.find("no-title")).isEmpty();
        assertThat(store.find(null)).isEmpty();
    }

    @Test
    void parseReadsQuotedValuesAndStripsTheFrontmatterFromTheBody() {
        Optional<ProjectTemplate> parsed = TemplateStore.parse("x", """
                ---
                title: "Quoted: title"
                description: 'single quoted'
                ---

                # Body

                text
                """);

        assertThat(parsed).isPresent().get().satisfies(t -> {
            assertThat(t.title()).isEqualTo("Quoted: title");
            assertThat(t.description()).isEqualTo("single quoted");
            assertThat(t.body()).isEqualTo("# Body\n\ntext\n");
        });
    }

    @Test
    void parseRejectsAFileThatDoesNotOpenWithAFence() {
        assertThat(TemplateStore.parse("x", "title: nope\n---\nbody")).isEmpty();
        assertThat(TemplateStore.parse("x", "")).isEmpty();
    }

    private static void writeTemplate(Path hostDir, String name, String title, String description, String body)
            throws IOException {
        Path dir = Files.createDirectories(hostDir.resolve(name));
        Files.writeString(dir.resolve("template.md"),
                "---\ntitle: " + title + "\ndescription: " + description + "\n---\n" + body);
    }
}
