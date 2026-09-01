package dev.locklane.engine.template;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The project templates available on this host (#536), read fresh on every call so a
 * template dropped into the host directory while the engine runs shows up on the next
 * open of the Add Project dialog — the same per-request stance as the {@code gh}
 * account listing (#532).
 *
 * <p>Two sources, merged by name with the host winning: the built-ins shipped on the
 * engine classpath under {@code templates/<name>/template.md}, and the operator's own
 * under {@code ${locklane.data-dir}/templates/<name>/template.md}. A host directory
 * that does not exist, an entry whose name fails {@link #NAME}, or a file that cannot
 * be read or has no usable frontmatter is skipped, never an error — the rest are still
 * listed.
 *
 * <p>A template name from a request is only ever a key into {@link #list()}; nothing
 * here joins request text onto a path. Every host path is built from a directory
 * entry the store itself enumerated and validated against {@link #NAME}.
 */
@Service
public class TemplateStore {

    private static final Logger log = LoggerFactory.getLogger(TemplateStore.class);

    /** The shape a template directory name must have to be listed at all. */
    static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]*$");

    static final String TEMPLATE_FILE = "template.md";

    /** The classpath built-ins — {@code classpath*} so nested-jar entries resolve in the packaged engine too. */
    private static final String BUILT_IN_PATTERN = "classpath*:templates/*/" + TEMPLATE_FILE;

    private static final String FRONTMATTER_FENCE = "---";

    private final Path hostDirectory;
    private final ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    @Autowired
    public TemplateStore(@Value("${locklane.data-dir}") String dataDir) {
        this(Path.of(dataDir).resolve("templates"));
    }

    /** Test seam: the host template directory directly, so a test can point it at a temporary one. */
    TemplateStore(Path hostDirectory) {
        this.hostDirectory = hostDirectory.normalize();
    }

    /** Every listable template, host entries replacing built-ins of the same name, sorted by title. */
    public List<ProjectTemplate> list() {
        Map<String, ProjectTemplate> byName = new LinkedHashMap<>();
        for (ProjectTemplate template : builtIns()) {
            byName.put(template.name(), template);
        }
        for (ProjectTemplate template : hostTemplates()) {
            byName.put(template.name(), template);
        }
        return byName.values().stream()
                .sorted(Comparator.comparing(ProjectTemplate::title, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(ProjectTemplate::name))
                .toList();
    }

    /** The template of that exact name, resolved through {@link #list()} — empty for anything not listed. */
    public Optional<ProjectTemplate> find(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return list().stream().filter(template -> template.name().equals(name)).findFirst();
    }

    private List<ProjectTemplate> builtIns() {
        List<ProjectTemplate> found = new ArrayList<>();
        Resource[] resources;
        try {
            resources = resolver.getResources(BUILT_IN_PATTERN);
        } catch (IOException e) {
            log.warn("Could not enumerate the built-in project templates", e);
            return found;
        }
        for (Resource resource : resources) {
            try {
                String name = builtInName(resource);
                if (!NAME.matcher(name).matches()) {
                    continue;
                }
                String text = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                parse(name, text).ifPresent(found::add);
            } catch (IOException | RuntimeException e) {
                log.warn("Skipping unreadable built-in project template {}", resource, e);
            }
        }
        return found;
    }

    /** The directory segment just above {@code template.md} in the resource's path. */
    private static String builtInName(Resource resource) throws IOException {
        String path = resource.getURL().getPath();
        String withoutFile = path.endsWith("/" + TEMPLATE_FILE)
                ? path.substring(0, path.length() - TEMPLATE_FILE.length() - 1) : path;
        int slash = withoutFile.lastIndexOf('/');
        return slash < 0 ? withoutFile : withoutFile.substring(slash + 1);
    }

    private List<ProjectTemplate> hostTemplates() {
        List<ProjectTemplate> found = new ArrayList<>();
        if (!Files.isDirectory(hostDirectory)) {
            return found;
        }
        try (Stream<Path> entries = Files.list(hostDirectory)) {
            for (Path entry : entries.sorted().toList()) {
                String name = entry.getFileName().toString();
                if (!NAME.matcher(name).matches() || !Files.isDirectory(entry)) {
                    continue;
                }
                Path file = entry.resolve(TEMPLATE_FILE);
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                try {
                    parse(name, Files.readString(file, StandardCharsets.UTF_8)).ifPresent(found::add);
                } catch (IOException | RuntimeException e) {
                    log.warn("Skipping unreadable project template {}", file, e);
                }
            }
        } catch (IOException e) {
            log.warn("Could not list the project template directory {}", hostDirectory, e);
        }
        return found;
    }

    /**
     * Splits a template file into its frontmatter and body. The frontmatter is the
     * block between a leading {@code ---} line and the next one, holding
     * {@code key: value} lines (a value may be wrapped in single or double quotes).
     * {@code title} is required; {@code description} defaults to empty. Anything else
     * — no opening fence, no closing fence, no title — makes the file unusable, and
     * the result is empty so the caller skips it.
     */
    static Optional<ProjectTemplate> parse(String name, String text) {
        String[] lines = text.split("\\R", -1);
        if (lines.length == 0 || !lines[0].strip().equals(FRONTMATTER_FENCE)) {
            return Optional.empty();
        }
        int close = -1;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].strip().equals(FRONTMATTER_FENCE)) {
                close = i;
                break;
            }
        }
        if (close < 0) {
            return Optional.empty();
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 1; i < close; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            fields.put(line.substring(0, colon).strip(), unquote(line.substring(colon + 1).strip()));
        }
        String title = fields.get("title");
        if (title == null || title.isBlank()) {
            return Optional.empty();
        }
        String body = String.join("\n", List.of(lines).subList(close + 1, lines.length));
        return Optional.of(new ProjectTemplate(name, title, fields.getOrDefault("description", ""),
                body.stripLeading()));
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
