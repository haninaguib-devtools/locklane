package dev.locklane.engine.template;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** #536's response shape: name, title, description — never the body. */
class TemplateControllerTest {

    @Test
    void listsNameTitleAndDescriptionOnly(@TempDir Path tmp) throws IOException {
        Files.writeString(Files.createDirectories(tmp.resolve("aaa-first")).resolve("template.md"),
                "---\ntitle: AAA first\ndescription: the hint\n---\nsecret body\n");
        TemplateController controller = new TemplateController(new TemplateStore(tmp));

        TemplateController.TemplatesResponse response = controller.list();

        assertThat(response.templates()).first().isEqualTo(
                new TemplateController.TemplateSummary("aaa-first", "AAA first", "the hint"));
        assertThat(response.templates()).extracting(TemplateController.TemplateSummary::name)
                .contains("springboot-angular");
    }
}
