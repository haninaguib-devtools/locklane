package dev.locklane.engine.logging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Enforces {@code docs/architecture/logging.md}'s convention over Java source text —
 * a lightweight text scan, not a full parser, in the same spirit as the issue's own
 * checkable spec ({@code grep -rn -A3 'catch (' engine/src/main/java}): every {@code
 * catch} block either logs (the exception passed as the log call's last argument),
 * rethrows, or carries a {@code // silent: <why>} comment as its first line; every
 * class referencing {@code ProcessBuilder}, {@code @Scheduled}, or {@code new
 * Thread(} declares a logger.
 *
 * <p>Deliberately conservative: brace/string/comment-aware enough not to be fooled by
 * a log message's own literal {@code {}} placeholders, but it does not resolve
 * whether a {@code throw} inside a catch body is reachable on every path, or whether
 * a nested catch's own log call was meant for the outer one. A false negative here is
 * a missed enforcement, not a wrong one; {@link LoggingConventionScannerTest} pins
 * the classifier's behavior on small inline snippets so this class itself cannot
 * silently regress.
 */
final class LoggingConventionScanner {

    /** One place in the tree that violates the convention. */
    record Violation(String file, int line, String message) {
        @Override
        public String toString() {
            return file + ":" + line + ": " + message;
        }
    }

    private static final Pattern CATCH = Pattern.compile("catch\\s*\\(([^()]+)\\)\\s*\\{");
    private static final Pattern LOG_CALL =
            Pattern.compile("\\blog\\s*\\.\\s*(trace|debug|info|warn|error)\\s*\\(");
    private static final Pattern SILENT_COMMENT = Pattern.compile("(?im)^\\s*//\\s*silent\\s*:");

    private LoggingConventionScanner() {
    }

    /** Walks every {@code .java} file under {@code root} and reports every violation found. */
    static List<Violation> scan(Path root) throws IOException {
        List<Violation> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                violations.addAll(scanContent(root.relativize(file).toString(), Files.readString(file)));
            }
        }
        return violations;
    }

    /** The scan logic over already-read source text, independent of the filesystem — what the unit tests drive. */
    static List<Violation> scanContent(String label, String content) {
        List<Violation> violations = new ArrayList<>();

        // Comments stripped first: a class's own javadoc explaining another class's
        // ProcessBuilder/@Scheduled use (as this scanner's own file does) must never
        // be mistaken for actual usage.
        String code = withoutLiteralsAndComments(content);
        boolean needsLogger = code.contains("ProcessBuilder") || code.contains("@Scheduled")
                || code.contains("new Thread(");
        boolean hasLogger = content.contains("LoggerFactory.getLogger(");
        if (needsLogger && !hasLogger) {
            violations.add(new Violation(label, 1,
                    "references ProcessBuilder/@Scheduled/new Thread( but declares no logger "
                            + "(no `LoggerFactory.getLogger(...)`)"));
        }

        Matcher catchMatcher = CATCH.matcher(content);
        while (catchMatcher.find()) {
            String[] parts = catchMatcher.group(1).trim().split("\\s+");
            String varName = parts[parts.length - 1];
            int openBrace = catchMatcher.end() - 1;
            int closeBrace = findMatchingBrace(content, openBrace);
            if (closeBrace < 0) {
                violations.add(new Violation(label, lineOf(content, catchMatcher.start()),
                        "catch block's braces do not balance — scanner could not find its end"));
                continue;
            }
            String body = content.substring(openBrace + 1, closeBrace);
            if (!complies(body, varName)) {
                violations.add(new Violation(label, lineOf(content, catchMatcher.start()),
                        "catch (" + catchMatcher.group(1).trim() + ") neither logs " + varName
                                + " as the log call's last argument, rethrows, nor carries a "
                                + "`// silent: <why>` comment"));
            }
        }
        return violations;
    }

    private static boolean complies(String body, String varName) {
        // Anywhere in the body, not only the very first statement: an
        // InterruptedException catch conventionally resets the interrupt flag
        // (Thread.currentThread().interrupt();) before anything else, including
        // before a `// silent:` comment explaining the rest of the body.
        if (SILENT_COMMENT.matcher(body).find()) {
            return true;
        }
        if (containsWholeWord(withoutLiteralsAndComments(body), "throw")) {
            return true;
        }
        return logsAsLastArgument(body, varName);
    }

    private static boolean logsAsLastArgument(String body, String varName) {
        Matcher logMatcher = LOG_CALL.matcher(body);
        while (logMatcher.find()) {
            int argsStart = logMatcher.end();
            int argsEnd = findMatchingParen(body, argsStart - 1);
            if (argsEnd < 0) {
                continue;
            }
            String args = body.substring(argsStart, argsEnd);
            List<String> topLevelArgs = splitTopLevelArgs(args);
            if (!topLevelArgs.isEmpty() && topLevelArgs.get(topLevelArgs.size() - 1).strip().equals(varName)) {
                return true;
            }
        }
        return false;
    }

    /** Top-level comma-split of a call's argument text, respecting nested parens/brackets/braces and literals. */
    private static List<String> splitTopLevelArgs(String args) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = 0;
        int i = 0;
        while (i < args.length()) {
            char c = args.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipLiteral(args, i, c);
                continue;
            }
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                result.add(args.substring(start, i));
                start = i + 1;
            }
            i++;
        }
        if (start < args.length()) {
            result.add(args.substring(start));
        } else if (args.isEmpty()) {
            return List.of();
        }
        return result;
    }

    private static boolean containsWholeWord(String haystack, String word) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(haystack);
        return m.find();
    }

    /** {@code body} with string/char literals and comments blanked out, so a keyword inside one is never matched. */
    private static String withoutLiteralsAndComments(String body) {
        StringBuilder out = new StringBuilder(body.length());
        int i = 0;
        while (i < body.length()) {
            char c = body.charAt(i);
            if (c == '"' || c == '\'') {
                int end = skipLiteral(body, i, c);
                out.append(" ".repeat(end - i));
                i = end;
                continue;
            }
            if (c == '/' && i + 1 < body.length() && body.charAt(i + 1) == '/') {
                int nl = body.indexOf('\n', i);
                int end = nl < 0 ? body.length() : nl;
                out.append(" ".repeat(end - i));
                i = end;
                continue;
            }
            if (c == '/' && i + 1 < body.length() && body.charAt(i + 1) == '*') {
                int close = body.indexOf("*/", i + 2);
                int end = close < 0 ? body.length() : close + 2;
                out.append(" ".repeat(end - i));
                i = end;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static int findMatchingBrace(String s, int openIndex) {
        return findMatchingDelimiter(s, openIndex, '{', '}');
    }

    private static int findMatchingParen(String s, int openIndex) {
        return findMatchingDelimiter(s, openIndex, '(', ')');
    }

    private static int findMatchingDelimiter(String s, int openIndex, char open, char close) {
        int depth = 1;
        int i = openIndex + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipLiteral(s, i, c);
                continue;
            }
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                int nl = s.indexOf('\n', i);
                i = nl < 0 ? s.length() : nl + 1;
                continue;
            }
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                i = end < 0 ? s.length() : end + 2;
                continue;
            }
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    /** Index just past the closing quote of the literal starting at {@code start} (which holds {@code quote}). */
    private static int skipLiteral(String s, int start, char quote) {
        int i = start + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) {
                return i + 1;
            }
            i++;
        }
        return s.length();
    }

    private static int lineOf(String content, int index) {
        int line = 1;
        for (int i = 0; i < index && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
