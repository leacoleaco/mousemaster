package mousemaster.platform.windows;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import mousemaster.MousemasterApplication;
import mousemaster.platform.Console;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * A console-subsystem executable gets a terminal from Windows before {@code main} runs.
 * On Windows 11 that terminal is often Windows Terminal, and {@code ShowWindow(SW_HIDE)}
 * hides only the hidden console host, so a cmd button stays on the taskbar.
 * The packaged exe is marked WINDOWS subsystem and does not get that terminal.
 * Checking "在任务栏显示控制台" allocates one; unchecking frees it, which closes the terminal.
 */
public class WindowsConsole implements Console {

    private static final String PREFERENCE_KEY = "console.visible.in.taskbar";
    private static final int CP_UTF8 = 65001;
    private static final int WS_EX_TOOLWINDOW = 0x00000080;
    private static final int WS_EX_APPWINDOW = 0x00040000;

    /** True after {@link #AllocConsole()} in this process, so hide can free it. */
    private static boolean allocated;
    /**
     * The user can close the console window. Closing it must end the process.
     * False while the console is freed on purpose.
     */
    private static volatile boolean closeShouldExit;
    private static PrintStream consoleOut;
    private static PrintStream consoleErr;

    public static boolean closeShouldExit() {
        return closeShouldExit;
    }

    /**
     * Apply the saved tray checkbox before the first log line. Missing preferences
     * stay hidden, matching {@code MousemasterTray}.
     */
    public static void prepare(Path configurationPath) {
        if (Boolean.getBoolean("mousemaster.keepConsole") || savedVisible(configurationPath))
            showConsole();
        else
            hideConsole();
    }

    /** Version and license text have nowhere to go unless a console exists. */
    public static void ensureForMessages() {
        showConsole();
    }

    @Override
    public void show() {
        showConsole();
    }

    @Override
    public void hide() {
        hideConsole();
    }

    private static boolean savedVisible(Path configurationPath) {
        Path path = configurationPath == null
                ? Path.of("").toAbsolutePath()
                : configurationPath.toAbsolutePath();
        Path dir = path.getParent();
        if (dir == null)
            dir = Path.of("").toAbsolutePath();
        Path prefs = dir.resolve(".mousemaster-tray.properties");
        if (!Files.isRegularFile(prefs))
            return false;
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(prefs, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            return false;
        }
        return Boolean.parseBoolean(properties.getProperty(PREFERENCE_KEY, "false"));
    }

    private static void showConsole() {
        if (Kernel32.INSTANCE.GetConsoleWindow() == null) {
            if (!Kernel32.INSTANCE.AllocConsole())
                return;
            allocated = true;
            Kernel32.INSTANCE.SetConsoleOutputCP(CP_UTF8);
            Kernel32.INSTANCE.SetConsoleCP(CP_UTF8);
            Kernel32.INSTANCE.SetConsoleTitle("mousemaster");
            // Java captured stdout before this console existed.
            attachLogging();
        }
        WinDef.HWND hwnd = Kernel32.INSTANCE.GetConsoleWindow();
        if (hwnd != null) {
            setTaskbarButton(hwnd, true);
            User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_SHOW);
        }
        closeShouldExit = true;
    }

    private static void hideConsole() {
        WinDef.HWND hwnd = Kernel32.INSTANCE.GetConsoleWindow();
        if (hwnd == null) {
            closeShouldExit = false;
            detachLogging();
            return;
        }
        // Freeing a console this process does not share closes Windows Terminal.
        // A console shared with cmd (start_dev) is only hidden; freeing it would
        // leave that cmd window up and detach its output.
        if (allocated || soleConsoleProcess()) {
            closeShouldExit = false;
            detachLogging();
            Kernel32.INSTANCE.FreeConsole();
            allocated = false;
            return;
        }
        setTaskbarButton(hwnd, false);
        User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_HIDE);
        closeShouldExit = true;
    }

    private static boolean soleConsoleProcess() {
        int[] processIds = new int[16];
        int count = ExtendedKernel32.INSTANCE.GetConsoleProcessList(processIds, processIds.length);
        return count == 1;
    }

    private static void setTaskbarButton(WinDef.HWND hwnd, boolean visible) {
        int style = User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE);
        if (visible)
            style = (style | WS_EX_APPWINDOW) & ~WS_EX_TOOLWINDOW;
        else
            style = (style | WS_EX_TOOLWINDOW) & ~WS_EX_APPWINDOW;
        User32.INSTANCE.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, style);
        User32.INSTANCE.SetWindowPos(hwnd, null, 0, 0, 0, 0,
                WinUser.SWP_NOMOVE | WinUser.SWP_NOSIZE | WinUser.SWP_NOZORDER |
                WinUser.SWP_FRAMECHANGED);
        User32.INSTANCE.ShowWindow(hwnd, visible ? WinUser.SW_SHOW : WinUser.SW_HIDE);
    }

    private static void attachLogging() {
        try {
            OutputStream logback = new FileOutputStream("CONOUT$");
            PrintStream out = new PrintStream(new FileOutputStream("CONOUT$"), true, StandardCharsets.UTF_8);
            PrintStream err = new PrintStream(new FileOutputStream("CONOUT$"), true, StandardCharsets.UTF_8);
            PrintStream previousOut = consoleOut;
            PrintStream previousErr = consoleErr;
            System.setOut(out);
            System.setErr(err);
            MousemasterApplication.setConsoleLogStream(logback);
            closeQuietly(previousOut);
            closeQuietly(previousErr);
            consoleOut = out;
            consoleErr = err;
        } catch (IOException ignored) {
            // The console window is up; logging keeps the previous stream.
        }
    }

    private static void detachLogging() {
        closeQuietly(consoleOut);
        closeQuietly(consoleErr);
        consoleOut = null;
        consoleErr = null;
        OutputStream sink = OutputStream.nullOutputStream();
        System.setOut(new PrintStream(sink, true));
        System.setErr(new PrintStream(OutputStream.nullOutputStream(), true));
        MousemasterApplication.setConsoleLogStream(OutputStream.nullOutputStream());
    }

    private static void closeQuietly(OutputStream stream) {
        if (stream == null)
            return;
        try {
            stream.close();
        } catch (IOException ignored) {
        }
    }
}
