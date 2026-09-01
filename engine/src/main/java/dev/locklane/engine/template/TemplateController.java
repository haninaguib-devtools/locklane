package dev.locklane.engine.template;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Serves the Add Project dialog's "template" pull-down (#536) the templates found on
 * this host — name, title, and description only; the body stays on the engine, which
 * commits it into the new repository itself. Gated as authenticated in
 * {@code SecurityConfig}, account-scoped like {@code /api/agents/installed} and
 * {@code /api/github/accounts}: it describes the host, not a project.
 */
@RestController
@RequestMapping("/api/templates")
public class TemplateController {

    private final TemplateStore store;

    public TemplateController(TemplateStore store) {
        this.store = store;
    }

    @GetMapping
    public TemplatesResponse list() {
        return new TemplatesResponse(store.list().stream()
                .map(t -> new TemplateSummary(t.name(), t.title(), t.description()))
                .toList());
    }

    record TemplatesResponse(List<TemplateSummary> templates) {
    }

    record TemplateSummary(String name, String title, String description) {
    }
}
