package mousemaster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationProblemTest {

    @TempDir
    Path directory;

    @Test
    void namesTheKeyAndLineFromABracketedParseError() throws Exception {
        Path file = directory.resolve("mousemaster.properties");
        Files.writeString(file, """
                # comment
                idle-mode.mouse.max-velocity=100

                idle-mode.indicator.color=#fff
                """, StandardCharsets.UTF_8);
        ConfigurationProblem.Location location = ConfigurationProblem.locate(file,
                new IllegalArgumentException(
                        "[idle-mode.mouse.max-velocity] expected a number"));

        assertEquals("idle-mode.mouse.max-velocity", location.key());
        assertEquals(java.util.List.of(2), location.lines());
        String summary = location.summary(true);
        assertTrue(summary.contains("仍在使用上一份成功加载的配置"));
        assertTrue(summary.contains("键：idle-mode.mouse.max-velocity"));
        assertTrue(summary.contains("行号：2"));
        assertTrue(summary.contains("expected a number"));
    }

    @Test
    void reportsEveryLineWhenAKeyIsDefinedTwice() throws Exception {
        Path file = directory.resolve("mousemaster.properties");
        Files.writeString(file, """
                foo.bar=1
                other=2
                foo.bar=3
                """, StandardCharsets.UTF_8);
        ConfigurationProblem.Location location = ConfigurationProblem.locate(file,
                new IllegalArgumentException("Property foo.bar is defined twice"));

        assertEquals("foo.bar", location.key());
        assertEquals(java.util.List.of(1, 3), location.lines());
        assertTrue(location.summary(false).contains("行号：1、3"));
        assertTrue(location.summary(false).contains("程序无法继续启动"));
    }

    @Test
    void keepsTheMessageWhenNoKeyCanBeLocated() throws Exception {
        Path file = directory.resolve("mousemaster.properties");
        Files.writeString(file, "idle-mode.indicator.color=#fff\n", StandardCharsets.UTF_8);
        ConfigurationProblem.Location location = ConfigurationProblem.locate(file,
                new IllegalArgumentException("Definition of mode missing-mode is missing"));

        assertNull(location.key());
        assertTrue(location.lines().isEmpty());
        assertTrue(location.summary(true).contains("Definition of mode missing-mode is missing"));
    }
}
