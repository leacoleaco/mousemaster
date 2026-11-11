package mousemaster.platform.windows;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Login launch of {@code Mousemaster.exe}.
 * <p>
 * A Startup-folder shortcut cannot satisfy {@code requireAdministrator}: Windows
 * skips it at logon instead of showing UAC. An elevated process therefore
 * registers a logon scheduled task that runs at the highest privileges. A
 * non-elevated java launch still uses {@code Mousemaster.lnk}.
 */
public final class WindowsStartupShortcut {

    private static final String LINK_NAME = "Mousemaster.lnk";
    private static final String TASK_NAME = "Mousemaster";

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
        return hasStartupFolderLink() || hasElevatedTask();
    }

    public static boolean hasStartupFolderLink() {
        Path link = linkPath();
        return link != null && Files.isRegularFile(link);
    }

    public static boolean hasElevatedTask() {
        return elevatedTaskExists();
    }

    public static void setEnabled(boolean enabled, Path configurationPath) throws IOException {
        Path link = linkPath();
        if (link == null)
            throw new IOException("找不到启动文件夹");
        if (!enabled) {
            Files.deleteIfExists(link);
            deleteElevatedTask();
            return;
        }
        Path executable = executableFor(configurationPath);
        if (executable == null)
            throw new IOException("找不到 Mousemaster.exe，无法创建开机自启快捷方式");
        if (WindowsProcessElevation.isElevated()) {
            createElevatedLogonTask(executable);
            Files.deleteIfExists(link);
            return;
        }
        deleteElevatedTask();
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
        runPowershell(script);
    }

    private static void createElevatedLogonTask(Path executable) throws IOException {
        Path workingDirectory = executable.getParent() == null
                ? Path.of("").toAbsolutePath()
                : executable.getParent();
        String script = "$user = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name; "
                + "$action = New-ScheduledTaskAction -Execute " + ps(executable)
                + " -WorkingDirectory " + ps(workingDirectory) + "; "
                + "$trigger = New-ScheduledTaskTrigger -AtLogOn -User $user; "
                + "$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Highest; "
                + "$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries; "
                + "Register-ScheduledTask -TaskName " + ps(TASK_NAME)
                + " -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null";
        runPowershell(script);
    }

    private static boolean elevatedTaskExists() {
        ProcessBuilder builder = new ProcessBuilder("schtasks.exe", "/Query", "/TN", TASK_NAME);
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            try (InputStream in = process.getInputStream()) {
                in.readAllBytes();
            }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void deleteElevatedTask() {
        ProcessBuilder builder = new ProcessBuilder(
                "schtasks.exe", "/Delete", "/TN", TASK_NAME, "/F");
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            try (InputStream in = process.getInputStream()) {
                in.readAllBytes();
            }
            if (!process.waitFor(10, TimeUnit.SECONDS))
                process.destroyForcibly();
        } catch (IOException ignored) {
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void runPowershell(String script) throws IOException {
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
                throw new IOException("创建开机自启项超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("创建开机自启项被中断", e);
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
