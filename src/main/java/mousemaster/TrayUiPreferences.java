package mousemaster;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Persists tray-related UI choices next to the main configuration file.
 */
final class TrayUiPreferences {

    private static final String KEY_CONSOLE_IN_TASKBAR = "console.visible.in.taskbar";

    private final Path path;

    TrayUiPreferences(Path configurationPath) {
        Path dir = configurationPath.toAbsolutePath().getParent();
        if (dir == null)
            dir = Paths.get("").toAbsolutePath();
        this.path = dir.resolve(".mousemaster-tray.properties");
    }

    Path path() {
        return path;
    }

    boolean readConsoleVisibleInTaskbar(boolean defaultValue) throws IOException {
        if (!Files.isRegularFile(path))
            return defaultValue;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return Boolean.parseBoolean(p.getProperty(KEY_CONSOLE_IN_TASKBAR,
                Boolean.toString(defaultValue)));
    }

    void writeConsoleVisibleInTaskbar(boolean visible) throws IOException {
        Properties p = new Properties();
        if (Files.isRegularFile(path)) {
            try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                p.load(r);
            }
        }
        p.setProperty(KEY_CONSOLE_IN_TASKBAR, Boolean.toString(visible));
        Files.createDirectories(path.getParent());
        try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            p.store(w, "Mousemaster tray UI preferences");
        }
    }
}
