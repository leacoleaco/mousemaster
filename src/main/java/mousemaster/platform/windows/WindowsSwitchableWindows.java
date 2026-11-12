package mousemaster.platform.windows;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import mousemaster.AltTabWindowRules;
import mousemaster.WindowEntry;
import mousemaster.platform.SwitchableWindows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Top-level windows in Alt-Tab order. EnumWindows walks the Z-order from front to
 * back, and Windows keeps that order as most-recently-used for the windows Alt-Tab shows.
 */
final class WindowsSwitchableWindows implements SwitchableWindows {

    private static final Logger logger = LoggerFactory.getLogger(WindowsSwitchableWindows.class);

    /** Enough for a hidpi menu when the row draws the icon at 32. */
    private static final int ICON_PX = 48;
    private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
    /** Cached failure, so a file with no icon is not opened again. */
    private static final int[] NO_ICON = new int[0];
    private static final ConcurrentHashMap<String, int[]> ICONS_BY_PATH = new ConcurrentHashMap<>();
    private static volatile boolean loggedIconFailure;
    private static volatile boolean loggedWindowFailure;

    private final List<ListedWindow> windows;
    private final AtomicBoolean iconLoadStarted = new AtomicBoolean();
    /** Published by the reader thread once every program file has been parsed. */
    private volatile Map<String, int[]> loadedIcons;
    private boolean iconsApplied;

    private WindowsSwitchableWindows(List<ListedWindow> windows) {
        this.windows = windows;
    }

    static WindowsSwitchableWindows capture() {
        int self = Kernel32.INSTANCE.GetCurrentProcessId();
        Map<Integer, String> pathByPid = new HashMap<>();
        List<ListedWindow> windows = new ArrayList<>();
        User32.INSTANCE.EnumWindows((hwnd, data) -> {
            try {
                consider(hwnd, self, pathByPid, windows);
            }
            catch (Throwable failure) {
                if (!loggedWindowFailure) {
                    loggedWindowFailure = true;
                    logger.error("Could not inspect a window", failure);
                }
            }
            return true;
        }, null);
        logger.debug("Alt-Tab windows: " + windows.size());
        return new WindowsSwitchableWindows(List.copyOf(windows));
    }

    private static void consider(WinDef.HWND hwnd, int self, Map<Integer, String> pathByPid,
                                 List<ListedWindow> windows) {
        boolean visible = User32.INSTANCE.IsWindowVisible(hwnd);
        boolean cloaked = cloaked(hwnd);
        boolean sameProcess = ownedByProcess(hwnd, self);
        int exStyle = User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE);
        boolean toolWindow = (exStyle & ExtendedUser32.WS_EX_TOOLWINDOW) != 0;
        boolean appWindow = (exStyle & ExtendedUser32.WS_EX_APPWINDOW) != 0;
        String className = className(hwnd);
        String title = windowTitle(hwnd);
        if (!AltTabWindowRules.included(visible, cloaked, sameProcess, toolWindow, appWindow,
                representative(hwnd), className, title))
            return;
        String executable = executablePath(processId(hwnd), pathByPid);
        if (endsWithIgnoreCase(executable, "ApplicationFrameHost.exe")) {
            WinDef.HWND hosted = coreWindow(hwnd);
            if (hosted != null) {
                String hostedPath = executablePath(processId(hosted), pathByPid);
                if (hostedPath != null)
                    executable = hostedPath;
            }
        }
        windows.add(new ListedWindow(hwnd,
                AltTabWindowRules.displayTitle(title, ExtendedUser32.INSTANCE.IsIconic(hwnd)),
                fileName(executable), executable));
    }

    @Override
    public List<WindowEntry> entries() {
        return windows.stream()
                      .map(window -> new WindowEntry(window.title, window.processName, null))
                      .toList();
    }

    @Override
    public List<int[]> icons() {
        List<int[]> icons = new ArrayList<>(windows.size());
        for (ListedWindow window : windows)
            icons.add(window.icon);
        return Collections.unmodifiableList(icons);
    }

    @Override
    public int iconPixelSize() {
        return ICON_PX;
    }

    /**
     * Program files are parsed on a reader thread, so a slow disk does not hold up the
     * list. This method only takes in what that thread has finished.
     */
    @Override
    public boolean drainIcons() {
        startIconLoad();
        Map<String, int[]> loaded = loadedIcons;
        if (loaded == null || iconsApplied)
            return false;
        iconsApplied = true;
        boolean changed = false;
        for (ListedWindow window : windows) {
            String path = window.executablePath;
            if (path == null || path.isEmpty())
                continue;
            int[] argb = loaded.get(path);
            if (argb == null || argb.length == 0)
                continue;
            window.icon = argb;
            changed = true;
        }
        return changed;
    }

    private void startIconLoad() {
        if (windows.isEmpty() || !iconLoadStarted.compareAndSet(false, true))
            return;
        List<ListedWindow> snapshot = windows;
        Thread thread = new Thread(() -> loadedIcons = readIcons(snapshot), "window-icons");
        thread.setDaemon(true);
        thread.start();
    }

    private static Map<String, int[]> readIcons(List<ListedWindow> windows) {
        Map<String, int[]> byPath = new HashMap<>();
        for (ListedWindow window : windows) {
            String path = window.executablePath;
            if (path == null || path.isEmpty() || byPath.containsKey(path))
                continue;
            int[] cached = ICONS_BY_PATH.get(path);
            if (cached != null) {
                byPath.put(path, cached);
                continue;
            }
            int[] read = NO_ICON;
            try {
                int[] pixels = ExeIcon.read(path, ICON_PX);
                if (pixels != null)
                    read = pixels;
            }
            catch (Throwable failure) {
                logIconFailure(failure);
            }
            ICONS_BY_PATH.put(path, read);
            byPath.put(path, read);
        }
        return Map.copyOf(byPath);
    }

    private static void logIconFailure(Throwable failure) {
        if (loggedIconFailure)
            return;
        loggedIconFailure = true;
        logger.error("Could not read program icons", failure);
    }

    @Override
    public boolean activate(int index) {
        if (index < 0 || index >= windows.size())
            return false;
        WinDef.HWND hwnd = windows.get(index).hwnd;
        if (!User32.INSTANCE.IsWindow(hwnd))
            return false;
        boolean focused = focus(hwnd);
        if (!focused)
            logger.debug("Could not focus window " + index + ": " + windows.get(index).title);
        return focused;
    }

    /**
     * A background process is not allowed to take the foreground. Attaching to the
     * foreground thread is what lets SetForegroundWindow succeed after the g key.
     */
    private static boolean focus(WinDef.HWND hwnd) {
        ExtendedUser32.INSTANCE.AllowSetForegroundWindow(-1);
        if (ExtendedUser32.INSTANCE.IsIconic(hwnd))
            User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_RESTORE);
        WinDef.DWORD current = new WinDef.DWORD(Kernel32.INSTANCE.GetCurrentThreadId());
        WinDef.HWND foreground = User32.INSTANCE.GetForegroundWindow();
        int foregroundThreadId = foreground == null ? 0 :
                User32.INSTANCE.GetWindowThreadProcessId(foreground, null);
        int targetThreadId = User32.INSTANCE.GetWindowThreadProcessId(hwnd, null);
        boolean attachedForeground = false;
        boolean attachedTarget = false;
        if (foregroundThreadId != 0 && foregroundThreadId != current.intValue())
            attachedForeground = User32.INSTANCE.AttachThreadInput(current,
                    new WinDef.DWORD(foregroundThreadId), true);
        if (targetThreadId != 0 && targetThreadId != current.intValue() &&
            targetThreadId != foregroundThreadId)
            attachedTarget = User32.INSTANCE.AttachThreadInput(current,
                    new WinDef.DWORD(targetThreadId), true);
        User32.INSTANCE.BringWindowToTop(hwnd);
        User32.INSTANCE.SetForegroundWindow(hwnd);
        ExtendedUser32.INSTANCE.SwitchToThisWindow(hwnd, true);
        if (attachedTarget)
            User32.INSTANCE.AttachThreadInput(current, new WinDef.DWORD(targetThreadId), false);
        if (attachedForeground)
            User32.INSTANCE.AttachThreadInput(current,
                    new WinDef.DWORD(foregroundThreadId), false);
        return same(User32.INSTANCE.GetForegroundWindow(), hwnd);
    }

    /** The window Alt-Tab would show for this owner chain, which is this hwnd. */
    private static boolean representative(WinDef.HWND hwnd) {
        WinDef.HWND root = User32.INSTANCE.GetAncestor(hwnd, WinUser.GA_ROOTOWNER);
        if (root == null)
            root = hwnd;
        WinDef.HWND walk = root;
        for (int guard = 0; guard < 16; guard++) {
            WinDef.HWND popup = ExtendedUser32.INSTANCE.GetLastActivePopup(walk);
            if (popup == null || same(popup, walk))
                return same(hwnd, walk);
            if (User32.INSTANCE.IsWindowVisible(popup))
                return same(hwnd, popup);
            walk = popup;
        }
        return same(hwnd, walk);
    }

    /** The real app window hosted by ApplicationFrameHost, when this frame has one. */
    private static WinDef.HWND coreWindow(WinDef.HWND frame) {
        WinDef.HWND child = User32.INSTANCE.FindWindowEx(
                frame, null, "Windows.UI.Core.CoreWindow", null);
        if (child != null)
            return child;
        WinDef.HWND walk = null;
        for (int guard = 0; guard < 16; guard++) {
            walk = User32.INSTANCE.FindWindowEx(frame, walk, null, null);
            if (walk == null)
                return null;
            child = User32.INSTANCE.FindWindowEx(walk, null, "Windows.UI.Core.CoreWindow", null);
            if (child != null)
                return child;
        }
        return null;
    }

    private static boolean cloaked(WinDef.HWND hwnd) {
        IntByReference cloaked = new IntByReference();
        Dwmapi.INSTANCE.DwmGetWindowAttribute(hwnd, Dwmapi.DWMWA_CLOAKED, cloaked, 4);
        return cloaked.getValue() != 0;
    }

    private static boolean ownedByProcess(WinDef.HWND hwnd, int processId) {
        return processId(hwnd) == processId;
    }

    private static int processId(WinDef.HWND hwnd) {
        IntByReference pid = new IntByReference();
        User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);
        return pid.getValue();
    }

    private static String executablePath(int pid, Map<Integer, String> cache) {
        if (pid == 0)
            return null;
        if (cache.containsKey(pid))
            return cache.get(pid);
        String path = queryExecutablePath(pid);
        cache.put(pid, path);
        return path;
    }

    private static String queryExecutablePath(int pid) {
        WinNT.HANDLE process = Kernel32.INSTANCE.OpenProcess(
                PROCESS_QUERY_LIMITED_INFORMATION, false, pid);
        if (process == null)
            return null;
        try {
            char[] buffer = new char[2048];
            IntByReference size = new IntByReference(buffer.length);
            if (!Kernel32.INSTANCE.QueryFullProcessImageName(process, 0, buffer, size))
                return null;
            return Native.toString(buffer);
        }
        finally {
            Kernel32.INSTANCE.CloseHandle(process);
        }
    }

    private static String fileName(String path) {
        if (path == null || path.isEmpty())
            return "";
        int slash = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static boolean endsWithIgnoreCase(String path, String suffix) {
        return path != null && path.length() >= suffix.length() &&
               path.regionMatches(true, path.length() - suffix.length(), suffix, 0, suffix.length());
    }

    private static String className(WinDef.HWND hwnd) {
        char[] buffer = new char[256];
        int length = User32.INSTANCE.GetClassName(hwnd, buffer, buffer.length);
        if (length <= 0)
            return "";
        return new String(buffer, 0, length);
    }

    private static String windowTitle(WinDef.HWND hwnd) {
        char[] buffer = new char[512];
        int length = User32.INSTANCE.GetWindowText(hwnd, buffer, buffer.length);
        if (length <= 0)
            return "";
        return new String(buffer, 0, length);
    }

    private static boolean same(WinDef.HWND a, WinDef.HWND b) {
        if (a == null || b == null)
            return a == b;
        Pointer aPointer = a.getPointer();
        Pointer bPointer = b.getPointer();
        if (aPointer == null || bPointer == null)
            return aPointer == bPointer;
        return Pointer.nativeValue(aPointer) == Pointer.nativeValue(bPointer);
    }

    private static final class ListedWindow {
        private final WinDef.HWND hwnd;
        private final String title;
        private final String processName;
        private final String executablePath;
        /** Set on the main thread by {@link #drainIcons()}. */
        private int[] icon;

        private ListedWindow(WinDef.HWND hwnd, String title, String processName,
                             String executablePath) {
            this.hwnd = hwnd;
            this.title = title;
            this.processName = processName;
            this.executablePath = executablePath;
        }
    }

}
