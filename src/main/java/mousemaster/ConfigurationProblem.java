package mousemaster;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a configuration parse failure into a short message that names the property
 * key and the line in the file, for the tray balloon and the startup dialog.
 */
final class ConfigurationProblem {

    private static final Pattern[] KEY_PATTERNS = {
            Pattern.compile("^\\[([^\\]]+)]"),
            Pattern.compile("Invalid property key ([^:\\s]+)"),
            Pattern.compile("property key ([^:\\s]+)"),
            Pattern.compile("Property ([^\\s]+) is defined twice"),
            Pattern.compile("Invalid property ([^=:\\s]+)")
    };

    private ConfigurationProblem() {
    }

    record Location(String key, List<Integer> lines, String detail) {

        String summary(boolean keptPreviousConfiguration) {
            StringBuilder text = new StringBuilder();
            if (keptPreviousConfiguration)
                text.append("配置没有生效，仍在使用上一份成功加载的配置。");
            else
                text.append("配置无法加载，程序无法继续启动。");
            if (key != null && !key.isBlank())
                text.append("\n键：").append(key);
            if (lines != null && !lines.isEmpty()) {
                text.append("\n行号：");
                for (int i = 0; i < lines.size(); i++) {
                    if (i > 0)
                        text.append("、");
                    text.append(lines.get(i));
                }
            }
            if (detail != null && !detail.isBlank())
                text.append("\n").append(abbreviate(detail));
            return text.toString();
        }

        private static String abbreviate(String detail) {
            String oneLine = detail.strip().replaceAll("\\s+", " ");
            int limit = 180;
            if (oneLine.length() <= limit)
                return oneLine;
            return oneLine.substring(0, limit - 1) + "…";
        }
    }

    static Location locate(Path file, Throwable error) {
        String detail = error.getMessage() == null ? error.toString() : error.getMessage();
        String key = explicitKey(detail);
        List<Integer> lines = key == null ? List.of() : lineNumbers(file, key);
        if (key == null || lines.isEmpty()) {
            String contained = longestKeyContainedIn(detail, propertyKeys(file));
            if (contained != null) {
                List<Integer> containedLines = lineNumbers(file, contained);
                if (!containedLines.isEmpty() && (key == null || lines.isEmpty())) {
                    key = contained;
                    lines = containedLines;
                }
            }
        }
        String shown = detail;
        if (key != null && shown.startsWith("[" + key + "] "))
            shown = shown.substring(key.length() + 3);
        return new Location(key, lines, shown);
    }

    private static String explicitKey(String detail) {
        for (Pattern pattern : KEY_PATTERNS) {
            Matcher matcher = pattern.matcher(detail);
            if (matcher.find())
                return matcher.group(1);
        }
        return null;
    }

    private static String longestKeyContainedIn(String detail, List<String> keys) {
        String best = null;
        for (String key : keys) {
            if (key.isEmpty() || !detail.contains(key))
                continue;
            if (best == null || key.length() > best.length())
                best = key;
        }
        return best;
    }

    private static List<String> propertyKeys(Path file) {
        List<String> keys = new ArrayList<>();
        for (String raw : lines(file)) {
            String key = keyOnLine(raw);
            if (key != null)
                keys.add(key);
        }
        return keys;
    }

    private static List<Integer> lineNumbers(Path file, String key) {
        List<Integer> numbers = new ArrayList<>();
        int number = 0;
        for (String raw : lines(file)) {
            number++;
            String lineKey = keyOnLine(raw);
            if (key.equals(lineKey))
                numbers.add(number);
        }
        return numbers;
    }

    private static String keyOnLine(String raw) {
        String line = raw.strip();
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("!"))
            return null;
        int separator = line.indexOf('=');
        if (separator <= 0)
            return null;
        String key = line.substring(0, separator).strip();
        return key.isEmpty() ? null : key;
    }

    private static List<String> lines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }
    }
}
