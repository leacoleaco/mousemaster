package mousemaster;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses and writes a mousemaster properties file while preserving blank lines and comments,
 * using the same line-continuation rules as {@link PropertiesReader}.
 */
public final class ConfigurationFileDocument {

    public interface Line {
    }

    public record BlankLine() implements Line {
    }

    /**
     * Original line text (without trailing newline), including leading spaces if any.
     */
    public record CommentLine(String rawLine) implements Line {
    }

    /**
     * A line that could not be parsed as {@code key=value}; written back unchanged.
     */
    public record OrphanLine(String rawMergedText) implements Line {
    }

    public static final class PropertyEntry {
        public final String key;
        public String value;

        public PropertyEntry(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }

    public record PropertyLine(PropertyEntry entry) implements Line {
    }

    private ConfigurationFileDocument() {
    }

    public static List<Line> parse(Path path) throws IOException {
        return parseLines(Files.readAllLines(path, StandardCharsets.UTF_8));
    }

    public static List<Line> parseLines(List<String> fileLines) {
        List<Line> result = new ArrayList<>();
        StringBuilder property = new StringBuilder();
        for (String rawLine : fileLines) {
            String trimmed = rawLine.strip();
            if (property.isEmpty()) {
                if (rawLine.isBlank()) {
                    result.add(new BlankLine());
                    continue;
                }
                if (trimmed.startsWith("#") || trimmed.startsWith("!")) {
                    result.add(new CommentLine(rawLine));
                    continue;
                }
                if (trimmed.endsWith("\\")) {
                    property.append(trimmed, 0, trimmed.length() - 1);
                    continue;
                }
                property.append(trimmed);
                flushProperty(result, property);
                continue;
            }
            // Continuation of a property — match PropertiesReader: blank physical lines are skipped
            if (rawLine.isBlank())
                continue;
            if (trimmed.endsWith("\\")) {
                property.append(trimmed, 0, trimmed.length() - 1);
                continue;
            }
            property.append(trimmed);
            flushProperty(result, property);
        }
        if (!property.isEmpty()) {
            flushProperty(result, property);
        }
        return result;
    }

    private static void flushProperty(List<Line> result, StringBuilder property) {
        String full = property.toString();
        property.setLength(0);
        int eq = full.indexOf('=');
        if (eq < 0) {
            result.add(new OrphanLine(full.strip()));
            return;
        }
        String key = full.substring(0, eq).strip();
        String value = full.substring(eq + 1).strip();
        result.add(new PropertyLine(new PropertyEntry(key, value)));
    }

    public static List<String> toLines(List<Line> document) {
        List<String> out = new ArrayList<>(document.size());
        for (Line line : document) {
            if (line instanceof BlankLine)
                out.add("");
            else if (line instanceof CommentLine c)
                out.add(c.rawLine());
            else if (line instanceof OrphanLine o)
                out.add(o.rawMergedText());
            else if (line instanceof PropertyLine p)
                out.add(p.entry().key + "=" + p.entry().value);
            else
                throw new IllegalStateException("Unknown line type: " + line);
        }
        return out;
    }

    public static void write(Path path, List<Line> document) throws IOException {
        List<String> lines = toLines(document);
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    public static List<PropertyEntry> propertyEntriesInOrder(List<Line> document) {
        List<PropertyEntry> list = new ArrayList<>();
        for (Line line : document) {
            if (line instanceof PropertyLine pl)
                list.add(pl.entry());
        }
        return list;
    }

    /**
     * @return list of keys that appear more than once (same rules as the main parser).
     */
    public static List<String> findDuplicateKeys(List<Line> document) {
        Set<String> seen = new HashSet<>();
        List<String> dups = new ArrayList<>();
        for (Line line : document) {
            if (line instanceof PropertyLine pl) {
                String k = pl.entry().key;
                if (!seen.add(k))
                    dups.add(k);
            }
        }
        return dups;
    }

    public static String categoryOfPropertyKey(String key) {
        if (key == null || key.isBlank())
            return "（空键）";
        int i = key.indexOf('.');
        if (i < 0)
            return "（顶层）";
        return key.substring(0, i);
    }

    /**
     * Joins consecutive {@link CommentLine}s immediately above a property (no blank or other line
     * in between) into a single hint string for the UI.
     */
    public static String hintFromPrecedingComments(List<Line> document, int propertyDocumentIndex) {
        List<String> parts = new ArrayList<>();
        for (int i = propertyDocumentIndex - 1; i >= 0; i--) {
            Line L = document.get(i);
            if (L instanceof BlankLine)
                break;
            if (L instanceof CommentLine c) {
                String t = c.rawLine().strip();
                if (t.startsWith("#"))
                    t = t.substring(1).strip();
                else if (t.startsWith("!"))
                    t = t.substring(1).strip();
                if (!t.isEmpty())
                    parts.addFirst(t);
            } else
                break;
        }
        return String.join(" ", parts);
    }
}
