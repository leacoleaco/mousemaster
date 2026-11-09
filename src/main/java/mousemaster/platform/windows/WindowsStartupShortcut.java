package mousemaster.platform.windows;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Startup-folder shortcut, matching {@code windows/launcher/install-shortcuts.ps1}:
 * {@code Mousemaster.lnk} aimed at {@code Mousemaster.exe}, window style minimized,
 * without the RunAsAdmin flag the desktop shortcut uses.
 */
public final class WindowsStartupShortcut {

    private static final String LINK_NAME = "Mousemaster.lnk";

    private WindowsStartupShortcut() {
    }

    static Path linkPath() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank())
            return null;
        return Path.of(appData, "Microsoft", "Windows", "Start Menu", "Programs",
                "Startup", LINK_NAME);
    }

    public static boolean isEnabled() {
        Path link = linkPath();
        return link != null && Files.isRegularFile(link);
    }

    public static void setEnabled(boolean enabled, Path configurationPath) throws IOException {
        Path link = linkPath();
        if (link == null)
            throw new IOException("找不到启动文件夹");
        if (!enabled) {
            Files.deleteIfExists(link);
            return;
        }
        Path executable = executableFor(configurationPath);
        if (executable == null)
            throw new IOException("找不到 Mousemaster.exe，无法创建开机自启快捷方式");
        Files.createDirectories(link.getParent());
        createShortcut(link, executable);
    }

    private static Path executableFor(Path configurationPath) {
        Path configDir = configurationPath.toAbsolutePath().getParent();
        if (configDir != null) {
            Path besideConfig = configDir.resolve("Mousemaster.exe");
            if (Files.isRegularFile(besideConfig))
                return besideConfig;
        }
        Path besideWorkingDirectory = Path.of("").toAbsolutePath().resolve("Mousemaster.exe");
        if (Files.isRegularFile(besideWorkingDirectory))
            return besideWorkingDirectory;
        return ProcessHandle.current().info().command()
                            .map(Path::of)
                            .filter(path -> path.getFileName() != null &&
                                            path.getFileName().toString()
                                                .equalsIgnoreCase("Mousemaster.exe"))
                            .filter(Files::isRegularFile)
                            .orElse(null);
    }

    private static void createShortcut(Path link, Path executable) throws IOException {
        Path workingDirectory = executable.getParent() == null
                ? Path.of("").toAbsolutePath()
                : executable.getParent();
        String script = "$shell = New-Object -ComObject WScript.Shell; "
                + "$shortcut = $shell.CreateShortcut(" + ps(link) + "); "
                + "$shortcut.TargetPath = " + ps(executable) + "; "
                + "$shortcut.WorkingDirectory = " + ps(workingDirectory) + "; "
                + "$shortcut.WindowStyle = 7; "
                + "$shortcut.Description = 'Mousemaster'; "
                + "$shortcut.IconLocation = " + ps(executable + ",0") + "; "
                + "$shortcut.Save()";
        ProcessBuilder builder = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-WindowStyle", "Hidden",
                "-ExecutionPolicy", "Bypass",
                "-Command", script);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        byte[] output;
        try (InputStream in = process.getInputStream()) {
            output = in.readAllBytes();
        }
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("创建开机自启快捷方式超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("创建开机自启快捷方式被中断", e);
        }
        if (process.exitValue() != 0) {
            String message = new String(output, StandardCharsets.UTF_8).strip();
            if (message.isEmpty())
                message = "powershell 退出码 " + process.exitValue();
            throw new IOException(message);
        }
    }

    private static String ps(Path path) {
        return ps(path.toAbsolutePath().toString());
    }

    private static String ps(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
