package mousemaster.platform.windows;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.BaseTSD;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Top-level windows in Alt-Tab order. EnumWindows walks the Z-order from front to
 * back, and Windows keeps that order as most-recently-used for the windows Alt-Tab shows.
 */
final class WindowsSwitchableWindows implements SwitchableWindows {

    private static final Logger logger = LoggerFactory.getLogger(WindowsSwitchableWindows.class);

    /** Enough for a hidpi menu when the row draws the icon at 32. */
    private static final int ICON_PX = 48;
    private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
    private static final int GCLP_HICON = -14;
    private static final int GCLP_HICONSM = -34;

    private final List<ListedWindow> windows;
    private final long generation = System.nanoTime();
    private Map<String, int[]> icons = Map.of();
    private boolean iconsLoaded;
    private boolean exeIconsUnavailable;

    private WindowsSwitchableWindows(List<ListedWindow> windows) {
        this.windows = windows;
    }

    static WindowsSwitchableWindows capture() {
        int self = Kernel32.INSTANCE.GetCurrentProcessId();
        Map<Integer, String> pathByPid = new HashMap<>();
        List<ListedWindow> windows = new ArrayList<>();
        User32.INSTANCE.EnumWindows((hwnd, data) -> {
            consider(hwnd, self, pathByPid, windows);
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
                      .map(window -> new WindowEntry(window.title, window.processName, window.iconName))
                      .toList();
    }

    @Override
    public Map<String, int[]> icons() {
        return icons;
    }

    @Override
    public int iconPixelSize() {
        return ICON_PX;
    }

    @Override
    public void loadIcons() {
        if (iconsLoaded)
            return;
        iconsLoaded = true;
        Map<String, String> nameByExecutable = new HashMap<>();
        Map<String, int[]> loaded = new HashMap<>();
        int shown = 0;
        for (ListedWindow window : windows) {
            String cached = window.executablePath == null ? null
                    : nameByExecutable.get(window.executablePath);
            if (cached != null) {
                window.iconName = cached;
                shown++;
                continue;
            }
            int[] pixels = exeIcon(window.executablePath);
            if (pixels == null)
                pixels = windowIcon(window.hwnd);
            if (pixels == null)
                continue;
            String name = "window-icon-" + Long.toUnsignedString(generation) + "-" + loaded.size();
            loaded.put(name, pixels);
            window.iconName = name;
            if (window.executablePath != null)
                nameByExecutable.put(window.executablePath, name);
            shown++;
        }
        icons = Map.copyOf(loaded);
        logger.debug("Window icons " + shown + "/" + windows.size());
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

    /** The program icon stored in the executable. The returned pixels are not owned as an icon. */
    private int[] exeIcon(String path) {
        if (path == null || path.isEmpty() || exeIconsUnavailable)
            return null;
        WinDef.HICON[] extracted = new WinDef.HICON[1];
        try {
            int count = ExtendedUser32.INSTANCE.PrivateExtractIconsW(
                    new WString(path), 0, ICON_PX, ICON_PX, extracted, null, 1, 0);
            if (count <= 0 || extracted[0] == null)
                return null;
            return WindowIconFile.pixels(extracted[0], ICON_PX);
        }
        catch (LinkageError error) {
            exeIconsUnavailable = true;
            logger.debug("PrivateExtractIconsW is unavailable", error);
            return null;
        }
        finally {
            if (extracted[0] != null)
                User32.INSTANCE.DestroyIcon(extracted[0]);
        }
    }

    /** The icon the window class registered, when the executable has none. */
    private static int[] windowIcon(WinDef.HWND hwnd) {
        int[] large = WindowIconFile.pixels(classIcon(hwnd, GCLP_HICON), ICON_PX);
        if (large != null)
            return large;
        return WindowIconFile.pixels(classIcon(hwnd, GCLP_HICONSM), ICON_PX);
    }

    private static WinDef.HICON classIcon(WinDef.HWND hwnd, int index) {
        BaseTSD.ULONG_PTR value = User32.INSTANCE.GetClassLongPtr(hwnd, index);
        if (value == null || value.longValue() == 0)
            return null;
        return new WinDef.HICON(new Pointer(value.longValue()));
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
        private String iconName;

        private ListedWindow(WinDef.HWND hwnd, String title, String processName,
                             String executablePath) {
            this.hwnd = hwnd;
            this.title = title;
            this.processName = processName;
            this.executablePath = executablePath;
        }
    }

}
