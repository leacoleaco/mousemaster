package mousemaster.platform.windows;

import mousemaster.*;

import com.sun.jna.*;
import com.sun.jna.platform.win32.*;
import com.sun.jna.platform.win32.COM.Unknown;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import mousemaster.platform.UiAutomation;
import mousemaster.platform.UiAutomation.TextExtent;
import mousemaster.platform.UiAutomation.UiElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public class WindowsUiAutomation implements UiAutomation {

    private static final Logger logger = LoggerFactory.getLogger(WindowsUiAutomation.class);

    private static final Guid.CLSID CLSID_CUIAutomation =
            new Guid.CLSID("FF48DBA4-60EF-4201-AA87-54103EEF594E");
    private static final Guid.IID IID_IUIAutomation =
            new Guid.IID("30CBE57D-D9D0-452A-AB13-7AC5AC4825EE");

    private static final int UIA_BoundingRectanglePropertyId = 30001;
    private static final int UIA_ControlTypePropertyId = 30003;
    private static final int UIA_NamePropertyId = 30005;
    private static final int UIA_IsOffscreenPropertyId = 30022;
    private static final int UIA_IsEnabledPropertyId = 30010;
    private static final int UIA_IsKeyboardFocusablePropertyId = 30009;
    private static final int UIA_IsInvokePatternAvailablePropertyId = 30031;
    private static final int UIA_IsExpandCollapsePatternAvailablePropertyId = 30028;
    private static final int UIA_IsTogglePatternAvailablePropertyId = 30041;
    private static final int UIA_IsSelectionItemPatternAvailablePropertyId = 30036;

    private static final int UIA_ButtonControlTypeId = 50000;

    /** The patterns of a control that does something when it is clicked. */
    private static final int[] CLICK_PATTERN_PROPERTY_IDS = {
            UIA_IsInvokePatternAvailablePropertyId,
            UIA_IsExpandCollapsePatternAvailablePropertyId,
            UIA_IsTogglePatternAvailablePropertyId,
            UIA_IsSelectionItemPatternAvailablePropertyId
    };

    private static final int TreeScope_Children = 2;
    private static final int TreeScope_Descendants = 4;

    private static final int UIA_TextPatternId = 10014;
    private static final Guid.IID IID_IUIAutomationTextPattern =
            new Guid.IID("32EBA289-3583-42C9-9C59-3B6D9A1E9B6A");
    /** How many parents to climb looking for a control that exposes its text. */
    private static final int TEXT_PATTERN_PARENT_LIMIT = 32;
    private static final int TEXT_UNIT_CHARACTER = 0;
    private static final int TEXT_UNIT_WORD = 2;
    private static final int TEXT_UNIT_LINE = 3;
    private static final int TEXT_UNIT_PARAGRAPH = 4;
    /** A paragraph longer than this is too much to pick words from. */
    private static final int TEXT_LIMIT_PARAGRAPH = 4_000;
    private static final int TEXT_PATTERN_RANGE_ENDPOINT_END = 1;
    /** The control cannot select text. A failed query is treated the same way. */
    private static final int SUPPORTED_TEXT_SELECTION_NONE = 0;
    private static final int TEXT_LIMIT_WORD = 256;
    private static final int TEXT_LIMIT_LINE = 4_000;
    private static final int TEXT_LIMIT_ALL = 200_000;

    // VARIANT constants
    private static final short VT_BOOL = 0x000B;
    private static final short VT_I4 = 3;
    private static final short VT_BSTR = 8;
    private static final short VARIANT_TRUE = -1;
    private static final short VARIANT_FALSE = 0;

    private static Pointer automation;
    private static UIAutomationCondition cachedCondition;
    /** Every control in the window, not only the ones a hint can click. */
    private static UIAutomationCondition controlViewCondition;
    private static UIAutomationCacheRequest cachedCacheRequest;
    /** Read by both query threads. */
    private static final Set<Integer> preWarmedProcessIds = ConcurrentHashMap.newKeySet();

    private static final ExecutorService queryExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "uia-query");
                t.setDaemon(true);
                return t;
            });
    /**
     * The windows the pointer is not on are scanned on a thread of their own. A scan that
     * was cancelled still finishes the window it is in, which would otherwise hold up
     * the next scan of the window under the pointer.
     */
    private static final ExecutorService otherWindowsQueryExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "uia-other-windows-query");
                t.setDaemon(true);
                return t;
            });
    /** COM is initialized once on each query thread. */
    private static final ThreadLocal<Boolean> backgroundComInitialized =
            ThreadLocal.withInitial(() -> false);

    private static Memory createBoolVariantTrue() {
        Memory variant = new Memory(16);
        variant.clear();
        variant.setShort(0, VT_BOOL);
        variant.setShort(8, VARIANT_TRUE);
        return variant;
    }

    private static Memory createBoolVariantFalse() {
        Memory variant = new Memory(16);
        variant.clear();
        variant.setShort(0, VT_BOOL);
        variant.setShort(8, VARIANT_FALSE);
        return variant;
    }

    private static Memory createIntVariant(int value) {
        Memory variant = new Memory(16);
        variant.clear();
        variant.setShort(0, VT_I4);
        variant.setInt(8, value);
        return variant;
    }

    private static void ensureInitialized() {
        if (automation != null)
            return;
        // COM should already be initialized by Qt (STA).
        // Call defensively; tolerate S_FALSE (already initialized).
        WinNT.HRESULT hr = Ole32.INSTANCE.CoInitializeEx(Pointer.NULL,
                Ole32.COINIT_APARTMENTTHREADED);
        int hrCode = hr.intValue();
        if (hrCode != W32Errors.S_OK.intValue() &&
            hrCode != W32Errors.S_FALSE.intValue()) {
            logger.warn("CoInitializeEx failed: 0x{}", Integer.toHexString(hrCode));
        }
        PointerByReference pAutomation = new PointerByReference();
        hr = Ole32.INSTANCE.CoCreateInstance(CLSID_CUIAutomation, null,
                WTypes.CLSCTX_INPROC_SERVER, IID_IUIAutomation, pAutomation);
        if (W32Errors.FAILED(hr))
            throw new RuntimeException(
                    "Failed to create IUIAutomation: 0x" +
                    Integer.toHexString(hr.intValue()));
        automation = pAutomation.getValue();
        UIAutomation uia = new UIAutomation(automation);
        cachedCondition = buildUiAutomationCondition(uia);
        if (cachedCondition == null) {
            logger.warn("Failed to create conditions, " +
                         "falling back to TrueCondition");
            cachedCondition = uia.createTrueCondition();
        }
        cachedCacheRequest = uia.createCacheRequest();
        if (cachedCacheRequest != null) {
            cachedCacheRequest.addProperty(UIA_BoundingRectanglePropertyId);
            cachedCacheRequest.addProperty(UIA_ControlTypePropertyId);
            cachedCacheRequest.addProperty(UIA_NamePropertyId);
            // What tells a control that reacts to a click from one that is only focusable.
            for (int clickPatternPropertyId : CLICK_PATTERN_PROPERTY_IDS)
                cachedCacheRequest.addProperty(clickPatternPropertyId);
        }
        controlViewCondition = uia.controlViewCondition();
        if (controlViewCondition == null)
            logger.warn("Failed to create the control view condition");
    }

    /**
     * IsOffscreen=false AND IsEnabled=true
     * AND (IsKeyboardFocusable OR IsInvokePatternAvailable OR ControlType=Button
     *      OR IsExpandCollapsePatternAvailable OR IsTogglePatternAvailable
     *      OR IsSelectionItemPatternAvailable OR ControlType=ScrollBar)
     */
    private static UIAutomationCondition buildUiAutomationCondition(UIAutomation uia) {
        Memory boolTrue = createBoolVariantTrue();
        Memory boolFalse = createBoolVariantFalse();
        UIAutomationCondition focusable = null, invokable = null,
                button = null, expandCollapse = null, toggle = null,
                selectionItem = null, scrollBar = null, onscreen = null, enabled = null;
        try {
            // A scroll bar cannot be focused or invoked, and is still operated.
            scrollBar = uia.createPropertyCondition(
                    UIA_ControlTypePropertyId,
                    createIntVariant(UiElement.Control.SCROLL_BAR.windowsControlTypeId()));
            focusable = uia.createPropertyCondition(
                    UIA_IsKeyboardFocusablePropertyId, boolTrue);
            invokable = uia.createPropertyCondition(
                    UIA_IsInvokePatternAvailablePropertyId, boolTrue);
            button = uia.createPropertyCondition(
                    UIA_ControlTypePropertyId,
                    createIntVariant(UIA_ButtonControlTypeId));
            expandCollapse = uia.createPropertyCondition(
                    UIA_IsExpandCollapsePatternAvailablePropertyId, boolTrue);
            toggle = uia.createPropertyCondition(
                    UIA_IsTogglePatternAvailablePropertyId, boolTrue);
            selectionItem = uia.createPropertyCondition(
                    UIA_IsSelectionItemPatternAvailablePropertyId, boolTrue);
            onscreen = uia.createPropertyCondition(
                    UIA_IsOffscreenPropertyId, boolFalse);
            enabled = uia.createPropertyCondition(
                    UIA_IsEnabledPropertyId, boolTrue);
            if (focusable == null || invokable == null ||
                button == null || expandCollapse == null ||
                toggle == null || selectionItem == null ||
                scrollBar == null || onscreen == null || enabled == null)
                return null;
            UIAutomationCondition or1 =
                    uia.createOrCondition(focusable, invokable);
            if (or1 == null)
                return null;
            UIAutomationCondition or2 =
                    uia.createOrCondition(or1, button);
            or1.Release();
            if (or2 == null)
                return null;
            UIAutomationCondition or3 =
                    uia.createOrCondition(or2, expandCollapse);
            or2.Release();
            if (or3 == null)
                return null;
            UIAutomationCondition or4 =
                    uia.createOrCondition(or3, toggle);
            or3.Release();
            if (or4 == null)
                return null;
            UIAutomationCondition or5 =
                    uia.createOrCondition(or4, selectionItem);
            or4.Release();
            if (or5 == null)
                return null;
            UIAutomationCondition or6 =
                    uia.createOrCondition(or5, scrollBar);
            or5.Release();
            if (or6 == null)
                return null;
            UIAutomationCondition and1 =
                    uia.createAndCondition(or6, onscreen);
            or6.Release();
            if (and1 == null)
                return null;
            UIAutomationCondition result =
                    uia.createAndCondition(and1, enabled);
            and1.Release();
            return result;
        } finally {
            if (focusable != null)
                focusable.Release();
            if (invokable != null)
                invokable.Release();
            if (button != null)
                button.Release();
            if (expandCollapse != null)
                expandCollapse.Release();
            if (toggle != null)
                toggle.Release();
            if (selectionItem != null)
                selectionItem.Release();
            if (scrollBar != null)
                scrollBar.Release();
            if (onscreen != null)
                onscreen.Release();
            if (enabled != null)
                enabled.Release();
        }
    }

    private static Rectangle rectangle(WinDef.RECT rect) {
        return new Rectangle(rect.left, rect.top, rect.right - rect.left,
                rect.bottom - rect.top);
    }

    /**
     * The foreground window and the popups that belong with it.
     * Thread-windows that aren't owned by the foreground window and aren't a popup on a
     * monitor the foreground window covers are skipped. Without this filter,
     * Chromium- and Gecko-based browsers which share a UI thread across multiple top-level
     * browser windows, would surface elements from unrelated browser windows on other
     * monitors and scatter hints onto the wrong screen.
     */
    private static List<HWND> windowsOf(HWND foregroundHwnd) {
        int threadId = User32.INSTANCE.GetWindowThreadProcessId(foregroundHwnd, null);
        long foregroundKey = Pointer.nativeValue(foregroundHwnd.getPointer());
        Set<Long> foregroundMonitors = monitorsIntersectingWindow(foregroundHwnd);
        List<HWND> windows = new ArrayList<>();
        windows.add(foregroundHwnd);
        ExtendedUser32.INSTANCE.EnumThreadWindows(threadId, (hwnd, data) -> {
            if (Pointer.nativeValue(hwnd.getPointer()) != foregroundKey &&
                User32.INSTANCE.IsWindowVisible(hwnd) &&
                shouldIncludeThreadWindow(hwnd, foregroundKey, foregroundMonitors))
                windows.add(hwnd);
            return true;
        }, null);
        return windows;
    }

    private static List<UiElement> queryUiElementsOfWindowAndChildren(HWND foregroundHwnd) {
        List<HWND> windows = windowsOf(foregroundHwnd);
        List<UiElement> uiElements = new ArrayList<>();
        long before = System.nanoTime();
        for (HWND window : windows) {
            queryUiElementsOfWindow(window, uiElements);
        }
        logger.debug("Found {} UI elements in HWND {} with {} windows in {}ms",
                uiElements.size(), Pointer.nativeValue(foregroundHwnd.getPointer()),
                windows.size(), (long) ((System.nanoTime() - before) / 1e6));
        return uiElements;
    }

    private static Set<Long> monitorsIntersectingWindow(HWND hwnd) {
        // GetWindowRect includes the invisible resize border, which for a maximized window
        // hangs over the neighboring monitors.
        WinDef.RECT windowRect = WindowsOverlay.windowRectExcludingShadow(hwnd);
        Set<Long> monitors = new HashSet<>();
        User32.INSTANCE.EnumDisplayMonitors(null, windowRect,
                (hMonitor, hdcMonitor, lprcMonitor, dwData) -> {
                    monitors.add(Pointer.nativeValue(hMonitor.getPointer()));
                    return 1;
                }, null);
        return monitors;
    }

    private static boolean shouldIncludeThreadWindow(HWND hwnd, long foregroundKey,
                                                     Set<Long> foregroundMonitors) {
        // Another browser window is not a popup.
        if ((User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_STYLE) &
             WinUser.WS_POPUP) == 0)
            return false;
        // Owned popups (menus, dropdowns, dialogs anchored to the focused window)
        // are kept regardless of which monitor they land on.
        HWND owner = User32.INSTANCE.GetWindow(hwnd, new WinDef.DWORD(User32.GW_OWNER));
        if (owner != null &&
            Pointer.nativeValue(owner.getPointer()) == foregroundKey)
            return true;
        WinDef.RECT rect = new WinDef.RECT();
        if (!User32.INSTANCE.GetWindowRect(hwnd, rect))
            return false;
        WinUser.HMONITOR monitor = User32.INSTANCE.MonitorFromRect(rect,
                WinUser.MONITOR_DEFAULTTONULL);
        if (monitor == null)
            return false;
        return foregroundMonitors.contains(Pointer.nativeValue(monitor.getPointer()));
    }

    /**
     * Queries UI elements of every visible window that intersects the area, front to back.
     * Elements covered by a window that is drawn over theirs are left out: a hint is
     * only kept where the click it performs would reach the element.
     */
    private static List<UiElement> queryUiElementsOfWindowsInArea(Rectangle area) {
        return queryUiElementsOfWindowsInArea(area, Set.of(), () -> false);
    }

    /**
     * The skipped windows are not walked, and still cover what is behind them. Once
     * cancelled, no other window is walked: what was found is of no use to the caller.
     */
    private static List<UiElement> queryUiElementsOfWindowsInArea(Rectangle area,
                                                                  Set<Long> skippedWindows,
                                                                  BooleanSupplier cancelled) {
        int currentProcessId = Kernel32.INSTANCE.GetCurrentProcessId();
        IntByReference processId = new IntByReference();
        List<HWND> windows = new ArrayList<>();
        List<Rectangle> windowRectanglesInArea = new ArrayList<>();
        // EnumWindows walks top-level windows front to back.
        User32.INSTANCE.EnumWindows((hwnd, data) -> {
            if (!User32.INSTANCE.IsWindowVisible(hwnd) ||
                ExtendedUser32.INSTANCE.IsIconic(hwnd) ||
                isCloaked(hwnd) ||
                // A click-through window does not receive the clicks the hints perform,
                // and does not hide what is behind it.
                (User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE) &
                 ExtendedUser32.WS_EX_TRANSPARENT) != 0)
                return true;
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, processId);
            if (processId.getValue() == currentProcessId)
                return true;
            Rectangle windowRectangleInArea =
                    rectangle(WindowsOverlay.windowRectExcludingShadow(hwnd))
                            .intersection(area);
            if (windowRectangleInArea.isEmpty())
                return true;
            windows.add(hwnd);
            windowRectanglesInArea.add(windowRectangleInArea);
            return true;
        }, null);
        List<UiElement> uiElements = new ArrayList<>();
        long before = System.nanoTime();
        int walkedWindows = 0;
        for (int windowIndex = 0; windowIndex < windows.size(); windowIndex++) {
            if (cancelled.getAsBoolean()) {
                logger.debug("Stopped looking for UI elements in area {} after {} of {} windows",
                        area, walkedWindows, windows.size());
                return uiElements;
            }
            Rectangle windowRectangleInArea = windowRectanglesInArea.get(windowIndex);
            HWND window = windows.get(windowIndex);
            if (skippedWindows.contains(Pointer.nativeValue(window.getPointer())))
                continue;
            Point center = windowRectangleInArea.center();
            // Walking a window that holds nothing a hint could reach is where the query
            // spends its time. Its center says whether it is drawn over at all, each
            // element being kept on the click it is there for anyway.
            if (windowRectanglesInArea.subList(0, windowIndex).stream()
                                      .anyMatch(rectangle -> rectangle.contains(
                                              windowRectangleInArea)) &&
                !clickReaches(window, center.x(), center.y()))
                continue;
            walkedWindows++;
            queryUiElementsOfWindow(window, windowRectangleInArea, uiElements);
        }
        logger.debug("Found {} UI elements in {} of {} windows of area {} in {}ms",
                uiElements.size(), walkedWindows, windows.size(), area,
                (long) ((System.nanoTime() - before) / 1e6));
        return uiElements;
    }

    /** Windows of another virtual desktop, and suspended UWP apps, are cloaked. */
    private static boolean isCloaked(HWND hwnd) {
        IntByReference cloaked = new IntByReference();
        Dwmapi.INSTANCE.DwmGetWindowAttribute(hwnd, Dwmapi.DWMWA_CLOAKED, cloaked, 4);
        return cloaked.getValue() != 0;
    }

    private static void queryUiElementsOfWindow(HWND hwnd,
                                                List<UiElement> uiElements) {
        WinDef.RECT windowRect = new WinDef.RECT();
        if (!User32.INSTANCE.GetWindowRect(hwnd, windowRect))
            return;
        if (User32.INSTANCE.MonitorFromRect(windowRect,
                WinUser.MONITOR_DEFAULTTONULL) == null)
            // The window is not withi n a screen.
            return;
        queryUiElementsOfWindow(hwnd, rectangle(windowRect), uiElements);
    }

    /** A Chromium browser exposes the page trees of all its windows once the children of one
     *  content window are asked for, and building them takes a moment. */
    private static void preWarmUiElements(HWND hwnd) {
        IntByReference processId = new IntByReference();
        User32.INSTANCE.GetWindowThreadProcessId(hwnd, processId);
        if (!preWarmedProcessIds.add(processId.getValue()))
            return;
        UIAutomation uia = new UIAutomation(automation);
        List<HWND> windows = new ArrayList<>();
        windows.add(hwnd);
        for (HWND child = User32.INSTANCE.GetWindow(hwnd,
                new WinDef.DWORD(User32.GW_CHILD)); child != null;
             child = User32.INSTANCE.GetWindow(child,
                     new WinDef.DWORD(User32.GW_HWNDNEXT)))
            windows.add(child);
        long before = System.nanoTime();
        for (HWND window : windows) {
            UIAutomationElement element = uia.elementFromHandle(window);
            if (element == null)
                continue;
            UIAutomationElementArray children = element.findAllBuildCache(
                    TreeScope_Children, cachedCondition, cachedCacheRequest);
            if (children != null)
                children.Release();
            element.Release();
        }
        logger.debug("Pre-warmed the UI elements of HWND {} with {} windows in {}ms",
                Pointer.nativeValue(hwnd.getPointer()), windows.size(),
                (long) ((System.nanoTime() - before) / 1e6));
    }

    private static void queryUiElementsOfWindow(HWND hwnd, Rectangle elementBounds,
                                                List<UiElement> uiElements) {
        preWarmUiElements(hwnd);
        double scale = WindowsScreen.findActiveScreen(new WinDef.POINT(
                elementBounds.x() + elementBounds.width() / 2,
                elementBounds.y() + elementBounds.height() / 2)).scale();
        UIAutomation uia = new UIAutomation(automation);
        UIAutomationElement root = null;
        UIAutomationElementArray array = null;
        try {
            root = uia.elementFromHandle(hwnd);
            if (root == null)
                return;
            long beforeQuery = System.nanoTime();
            int elementCountBeforeQuery = uiElements.size();
            array = root.findAllBuildCache(TreeScope_Descendants,
                    cachedCondition, cachedCacheRequest);
            if (array != null)
                collectElements(array, hwnd, elementBounds, scale, uiElements);
            logger.trace("Found {} UI elements in HWND {} in {}ms",
                    uiElements.size() - elementCountBeforeQuery,
                    Pointer.nativeValue(hwnd.getPointer()),
                    (long) ((System.nanoTime() - beforeQuery) / 1e6));
        }
        finally {
            if (array != null)
                array.Release();
            if (root != null)
                root.Release();
        }
    }

    /**
     * Whether a click at that point lands on that window, rather than on one drawn over it.
     * A rectangle cannot answer this: the window the Start menu opens spans the work area
     * but shows the windows behind it everywhere but its own panel.
     */
    private static boolean clickReaches(HWND hwnd, double x, double y) {
        WinDef.POINT.ByValue point = new WinDef.POINT.ByValue();
        point.x = (int) Math.round(x);
        point.y = (int) Math.round(y);
        HWND clicked = ExtendedUser32.INSTANCE.WindowFromPoint(point);
        if (clicked == null)
            return false;
        HWND root = User32.INSTANCE.GetAncestor(clicked, WinUser.GA_ROOT);
        if (root == null)
            root = clicked;
        if (hwnd.equals(root))
            return true;
        // Our own overlays are drawn over everything and hidden before a hint is clicked.
        IntByReference processId = new IntByReference();
        User32.INSTANCE.GetWindowThreadProcessId(root, processId);
        return processId.getValue() == Kernel32.INSTANCE.GetCurrentProcessId();
    }

    private static void collectElements(UIAutomationElementArray array, HWND hwnd,
                                        Rectangle elementBounds,
                                        double scale,
                                        List<UiElement> uiElements) {
        int length = array.getLength();
        for (int i = 0; i < length; i++) {
            UIAutomationElement element = array.getElement(i);
            if (element == null)
                continue;
            try {
                WinDef.RECT rect = element.getCachedBoundingRectangle();
                if (rect == null)
                    continue;
                int width = rect.right - rect.left;
                int height = rect.bottom - rect.top;
                if (width <= 0 || height <= 0)
                    continue;
                int controlTypeId = element.getCachedControlType();
                UiElement.Control control =
                        UiElement.Control.fromWindowsControlType(controlTypeId);
                UiElement.Role typeRole = UiElement.Role.of(control, false);
                UiElement uiElement = new UiElement(
                        new Rectangle(rect.left, rect.top, width, height),
                        // Only a type that is not clicked by itself needs the patterns read.
                        typeRole == UiElement.Role.OTHER ?
                                UiElement.Role.of(control, element.isCachedClickable()) :
                                typeRole,
                        control,
                        element.getCachedName());
                if (!elementBounds.contains(uiElement.centerX(), uiElement.centerY()))
                    continue;
                if (!clickReaches(hwnd, uiElement.centerX(), uiElement.centerY()))
                    continue;
                if (UiAutomation.isTooCloseToExistingUiElements(uiElements,
                        uiElement.centerX(), uiElement.centerY(), scale))
                    continue;
                uiElements.add(uiElement);
            }
            finally {
                element.Release();
            }
        }
    }

    /**
     * Every control of the foreground window whose center lies in that window.
     * Panels that a hint would skip are kept: a menu item moves the pointer there.
     */
    private static UiAutomation.WindowControls queryControlView(HWND foreground) {
        WinDef.RECT windowRect = WindowsOverlay.windowRectExcludingShadow(foreground);
        Rectangle window = rectangle(windowRect);
        if (window.width() <= 0 || window.height() <= 0)
            return new UiAutomation.WindowControls(new Rectangle(0, 0, 0, 0), List.of());
        preWarmUiElements(foreground);
        List<UiElement> elements = new ArrayList<>();
        long before = System.nanoTime();
        UIAutomation uia = new UIAutomation(automation);
        for (HWND hwnd : windowsOf(foreground)) {
            UIAutomationElement root = null;
            UIAutomationElementArray array = null;
            try {
                root = uia.elementFromHandle(hwnd);
                if (root == null)
                    continue;
                array = root.findAllBuildCache(TreeScope_Descendants,
                        controlViewCondition, cachedCacheRequest);
                if (array == null)
                    continue;
                int length = array.getLength();
                for (int i = 0; i < length; i++) {
                    UIAutomationElement element = array.getElement(i);
                    if (element == null)
                        continue;
                    try {
                        WinDef.RECT rect = element.getCachedBoundingRectangle();
                        if (rect == null)
                            continue;
                        int width = rect.right - rect.left;
                        int height = rect.bottom - rect.top;
                        if (width <= 0 || height <= 0 || width > 20_000 || height > 20_000)
                            continue;
                        Rectangle bounds = new Rectangle(rect.left, rect.top, width, height);
                        if (!window.contains(bounds.center().x(), bounds.center().y()))
                            continue;
                        int controlTypeId = element.getCachedControlType();
                        UiElement.Control control =
                                UiElement.Control.fromWindowsControlType(controlTypeId);
                        elements.add(new UiElement(bounds,
                                control == null ? null : control.role(),
                                control,
                                element.getCachedName()));
                    }
                    finally {
                        element.Release();
                    }
                }
            }
            finally {
                if (array != null)
                    array.Release();
                if (root != null)
                    root.Release();
            }
        }
        logger.debug("Found {} controls in the active window in {}ms",
                elements.size(), (long) ((System.nanoTime() - before) / 1e6));
        return new UiAutomation.WindowControls(window, elements);
    }

    /**
     * Starts an asynchronous UI element query on a background thread.
     */
    @Override
    public Future<List<UiElement>> startFindActiveWindowUiElements() {
        return startFindWindowUiElements(User32.INSTANCE.GetForegroundWindow());
    }

    @Override
    public Future<UiAutomation.WindowControls> startFindActiveWindowControls() {
        ensureInitialized();
        HWND hwnd = User32.INSTANCE.GetForegroundWindow();
        long hwndKey = hwnd != null ? Pointer.nativeValue(hwnd.getPointer()) : 0;
        return queryExecutor.submit(() -> {
            if (hwndKey == 0 || controlViewCondition == null || cachedCacheRequest == null)
                return new UiAutomation.WindowControls(new Rectangle(0, 0, 0, 0), List.of());
            initializeBackgroundCom();
            return queryControlView(new HWND(new Pointer(hwndKey)));
        });
    }

    @Override
    public Future<List<UiElement>> startFindMouseWindowUiElements(int x, int y) {
        return startFindWindowUiElements(mouseWindow(x, y));
    }

    /** The top-level window under the point, the active window where there is none. */
    private static HWND mouseWindow(int x, int y) {
        HWND hwnd = WindowsOverlay.topLevelWindowAt(x, y);
        return hwnd != null ? hwnd : User32.INSTANCE.GetForegroundWindow();
    }

    @Override
    public Future<List<UiElement>> startFindOtherWindowsUiElements(int x, int y,
                                                                   Rectangle area) {
        ensureInitialized();
        HWND hwnd = mouseWindow(x, y);
        long hwndKey = hwnd != null ? Pointer.nativeValue(hwnd.getPointer()) : 0;
        AtomicBoolean cancelled = new AtomicBoolean();
        // FutureTask.cancel leaves a task that is running alone: the flag is what the
        // scan reads between two windows.
        FutureTask<List<UiElement>> task = new FutureTask<List<UiElement>>(() -> {
            if (cachedCondition == null || cachedCacheRequest == null)
                return List.of();
            initializeBackgroundCom();
            Set<Long> mouseWindows = new HashSet<>();
            if (hwndKey != 0)
                for (HWND window : windowsOf(new HWND(new Pointer(hwndKey))))
                    mouseWindows.add(Pointer.nativeValue(window.getPointer()));
            return queryUiElementsOfWindowsInArea(area, mouseWindows, cancelled::get);
        }) {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                cancelled.set(true);
                return super.cancel(mayInterruptIfRunning);
            }
        };
        otherWindowsQueryExecutor.execute(task);
        return task;
    }

    private Future<List<UiElement>> startFindWindowUiElements(HWND hwnd) {
        ensureInitialized();
        long hwndKey = hwnd != null ? Pointer.nativeValue(hwnd.getPointer()) : 0;
        return queryExecutor.submit(() -> {
            if (hwndKey == 0 || cachedCondition == null ||
                cachedCacheRequest == null)
                return List.of();
            initializeBackgroundCom();
            return queryUiElementsOfWindowAndChildren(
                    new HWND(new Pointer(hwndKey)));
        });
    }

    @Override
    public Future<List<UiElement>> startFindUiElementsInArea(Rectangle area) {
        ensureInitialized();
        return queryExecutor.submit(() -> {
            if (cachedCondition == null || cachedCacheRequest == null)
                return List.of();
            initializeBackgroundCom();
            return queryUiElementsOfWindowsInArea(area);
        });
    }

    /**
     * Read the text at the pointer from the control's text pattern, select it when the
     * control allows a selection, and put the plain text on the clipboard. The read runs
     * on the UI Automation thread: doing it on the keyboard-hook thread freezes that
     * thread and Windows then removes the hook. A control with no text pattern, and this
     * process's own windows, complete as false so the caller can select with clicks.
     */
    @Override
    public Future<Boolean> startCopyText(TextExtent extent) {
        try {
            ensureInitialized();
            if (automation == null)
                return CompletableFuture.completedFuture(false);
            WinDef.POINT point = new WinDef.POINT();
            if (!User32.INSTANCE.GetCursorPos(point))
                return CompletableFuture.completedFuture(false);
            int x = point.x;
            int y = point.y;
            return queryExecutor.submit(() -> {
                initializeBackgroundCom();
                try {
                    boolean copied = copyTextAt(x, y, extent);
                    if (copied)
                        logger.info("Copied the {} at {},{} from the text pattern",
                                extent.name().toLowerCase(), x, y);
                    else
                        logger.debug("No text pattern at {},{}, selecting the {} with clicks",
                                x, y, extent.name().toLowerCase());
                    return copied;
                }
                catch (Throwable t) {
                    logger.warn("Reading the text under the pointer failed, selecting it with clicks", t);
                    return false;
                }
            });
        }
        catch (RuntimeException e) {
            logger.warn("Reading the text under the pointer failed, selecting it with clicks", e);
            return CompletableFuture.completedFuture(false);
        }
    }

    private static boolean copyTextAt(int x, int y, TextExtent extent) {
        UIAutomation uia = new UIAutomation(automation);
        UIAutomationElement element = uia.elementFromPoint(x, y);
        if (element == null)
            return false;
        int self = Kernel32.INSTANCE.GetCurrentProcessId();
        if (element.processId() == self) {
            element.Release();
            return false;
        }
        UIAutomationTreeWalker walker = uia.rawViewWalker();
        try {
            for (int depth = 0; element != null && depth < TEXT_PATTERN_PARENT_LIMIT; depth++) {
                if (element.processId() != self && copyElementText(element, x, y, extent))
                    return true;
                if (walker == null)
                    return false;
                UIAutomationElement parent = walker.parent(element);
                element.Release();
                element = parent;
            }
            return false;
        }
        finally {
            if (element != null)
                element.Release();
            if (walker != null)
                walker.Release();
        }
    }

    private static boolean copyElementText(UIAutomationElement element, int x, int y,
                                           TextExtent extent) {
        UIAutomationTextPattern pattern = element.textPattern();
        if (pattern == null)
            return false;
        UIAutomationTextRange range = null;
        try {
            range = extent == TextExtent.ALL ? pattern.documentRange() :
                    pattern.rangeFromPoint(x, y);
            if (range == null)
                return false;
            if (extent != TextExtent.ALL && !range.expandToEnclosingUnit(textUnit(extent)))
                return false;
            if (extent == TextExtent.WORD)
                range.trimTrailingWhitespace();
            String text = range.text(textLimit(extent));
            if (extent == TextExtent.WORD)
                text = stripTrailingWhitespace(text);
            if (text == null || text.isEmpty())
                return false;
            if (pattern.supportedTextSelection() != SUPPORTED_TEXT_SELECTION_NONE)
                range.select();
            return copyToClipboard(text);
        }
        finally {
            if (range != null)
                range.Release();
            pattern.Release();
        }
    }

    /**
     * The paragraph at the pointer, read from the control's text pattern like
     * {@link #startCopyText}, on the same thread. Nothing is selected and the clipboard
     * is not written. Null where there is no text pattern.
     */
    @Override
    public Future<String> startReadText() {
        try {
            ensureInitialized();
            if (automation == null)
                return CompletableFuture.completedFuture(null);
            WinDef.POINT point = new WinDef.POINT();
            if (!User32.INSTANCE.GetCursorPos(point))
                return CompletableFuture.completedFuture(null);
            int x = point.x;
            int y = point.y;
            return queryExecutor.submit(() -> {
                initializeBackgroundCom();
                try {
                    String text = readTextAt(x, y);
                    if (text == null)
                        logger.info("No text pattern at {},{} to read a paragraph from", x, y);
                    else
                        logger.info("Read {} characters at {},{} from the text pattern",
                                text.length(), x, y);
                    return text;
                }
                catch (Throwable t) {
                    logger.warn("Reading the paragraph under the pointer failed", t);
                    return null;
                }
            });
        }
        catch (RuntimeException e) {
            logger.warn("Reading the paragraph under the pointer failed", e);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static String readTextAt(int x, int y) {
        UIAutomation uia = new UIAutomation(automation);
        UIAutomationElement element = uia.elementFromPoint(x, y);
        if (element == null)
            return null;
        int self = Kernel32.INSTANCE.GetCurrentProcessId();
        if (element.processId() == self) {
            element.Release();
            return null;
        }
        UIAutomationTreeWalker walker = uia.rawViewWalker();
        try {
            for (int depth = 0; element != null && depth < TEXT_PATTERN_PARENT_LIMIT; depth++) {
                if (element.processId() != self) {
                    String text = readElementText(element, x, y);
                    if (text != null)
                        return text;
                }
                if (walker == null)
                    return null;
                UIAutomationElement parent = walker.parent(element);
                element.Release();
                element = parent;
            }
            return null;
        }
        finally {
            if (element != null)
                element.Release();
            if (walker != null)
                walker.Release();
        }
    }

    /**
     * A control that has no paragraphs answers with a larger unit, up to the whole
     * document. Past what can be picked from, the line is read instead.
     */
    private static String readElementText(UIAutomationElement element, int x, int y) {
        UIAutomationTextPattern pattern = element.textPattern();
        if (pattern == null)
            return null;
        try {
            String paragraph = rangeText(pattern, x, y, TEXT_UNIT_PARAGRAPH,
                    TEXT_LIMIT_PARAGRAPH + 1);
            if (paragraph != null && !paragraph.isBlank() &&
                paragraph.length() <= TEXT_LIMIT_PARAGRAPH)
                return paragraph;
            String line = rangeText(pattern, x, y, TEXT_UNIT_LINE, TEXT_LIMIT_LINE);
            if (line != null && !line.isBlank())
                return line;
            if (paragraph == null || paragraph.isBlank())
                return null;
            return paragraph.substring(0, Math.min(paragraph.length(), TEXT_LIMIT_PARAGRAPH));
        }
        finally {
            pattern.Release();
        }
    }

    private static String rangeText(UIAutomationTextPattern pattern, int x, int y, int unit,
                                    int limit) {
        UIAutomationTextRange range = pattern.rangeFromPoint(x, y);
        if (range == null)
            return null;
        try {
            if (!range.expandToEnclosingUnit(unit))
                return null;
            return range.text(limit);
        }
        finally {
            range.Release();
        }
    }

    private static int textUnit(TextExtent extent) {
        return extent == TextExtent.WORD ? TEXT_UNIT_WORD : TEXT_UNIT_LINE;
    }

    private static int textLimit(TextExtent extent) {
        return switch (extent) {
            case WORD -> TEXT_LIMIT_WORD;
            case LINE -> TEXT_LIMIT_LINE;
            case ALL -> TEXT_LIMIT_ALL;
        };
    }

    /** The word unit includes the space after the word. A word that is only space is empty. */
    private static String stripTrailingWhitespace(String text) {
        if (text == null)
            return null;
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1)))
            end--;
        return text.substring(0, end);
    }

    private static boolean copyToClipboard(String text) {
        long bytes = (text.length() + 1L) * 2L;
        Pointer memory = ExtendedKernel32.INSTANCE.GlobalAlloc(
                ExtendedKernel32.GMEM_MOVEABLE, bytes);
        if (memory == null)
            return false;
        boolean accepted = false;
        boolean opened = false;
        try {
            Pointer locked = ExtendedKernel32.INSTANCE.GlobalLock(memory);
            if (locked == null)
                return false;
            locked.setWideString(0, text);
            ExtendedKernel32.INSTANCE.GlobalUnlock(memory);
            opened = ExtendedUser32.INSTANCE.OpenClipboard(null);
            if (!opened)
                return false;
            if (!ExtendedUser32.INSTANCE.EmptyClipboard())
                return false;
            accepted = ExtendedUser32.INSTANCE.SetClipboardData(
                    ExtendedUser32.CF_UNICODETEXT, memory) != null;
            return accepted;
        }
        finally {
            if (opened)
                ExtendedUser32.INSTANCE.CloseClipboard();
            if (!accepted)
                Kernel32.INSTANCE.GlobalFree(memory);
        }
    }

    private static void initializeBackgroundCom() {
        if (backgroundComInitialized.get())
            return;
        Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_MULTITHREADED);
        backgroundComInitialized.set(true);
    }

    // COM wrappers

    private static class UIAutomation extends Unknown {

        UIAutomation(Pointer p) {
            super(p);
        }

        // IUIAutomation::ElementFromPoint — vtable index 7.
        // POINT is passed by value. A packed long is not a POINT to this call and
        // corrupts the stack.
        UIAutomationElement elementFromPoint(int x, int y) {
            PointerByReference element = new PointerByReference();
            WinNT.HRESULT hr = (WinNT.HRESULT) _invokeNativeObject(7,
                    new Object[]{getPointer(), new WinDef.POINT.ByValue(x, y), element},
                    WinNT.HRESULT.class);
            if (W32Errors.FAILED(hr) || element.getValue() == null)
                return null;
            return new UIAutomationElement(element.getValue());
        }

        // IUIAutomation::get_ControlViewCondition — vtable index 18.
        // 16 is get_RawViewWalker and 20 is CreateCacheRequest.
        UIAutomationCondition controlViewCondition() {
            PointerByReference condition = new PointerByReference();
            WinNT.HRESULT hr = (WinNT.HRESULT) _invokeNativeObject(18,
                    new Object[]{getPointer(), condition},
                    WinNT.HRESULT.class);
            if (W32Errors.FAILED(hr) || condition.getValue() == null) {
                logger.warn("get_ControlViewCondition failed: 0x{}",
                        Integer.toHexString(hr.intValue()));
                return null;
            }
            return new UIAutomationCondition(condition.getValue());
        }

        // IUIAutomation::get_RawViewWalker — vtable index 16
        UIAutomationTreeWalker rawViewWalker() {
            PointerByReference walker = new PointerByReference();
            WinNT.HRESULT hr = (WinNT.HRESULT) _invokeNativeObject(16,
                    new Object[]{getPointer(), walker},
                    WinNT.HRESULT.class);
            if (W32Errors.FAILED(hr) || walker.getValue() == null)
                return null;
            return new UIAutomationTreeWalker(walker.getValue());
        }

        // IUIAutomation::ElementFromHandle — vtable index 6
        UIAutomationElement elementFromHandle(HWND hwnd) {
            PointerByReference ppElement = new PointerByReference();
            WinNT.HRESULT hr = (WinNT.HRESULT) _invokeNativeObject(6,
                    new Object[]{getPointer(), hwnd.getPointer(), ppElement},
                    WinNT.HRESULT.class);
            if (W32Errors.FAILED(hr) || ppElement.getValue() == null)
                return null;
            return new UIAutomationElement(ppElement.getValue());
        }

        // IUIAutomation::CreateCacheRequest — vtable index 20
        UIAutomationCacheRequest createCacheRequest() {
            PointerByReference ppRequest = new PointerByReference();
            WinNT.HRESULT hr = (WinNT.HRESULT) _invokeNativeObject(20,
                    new Object[]{getPointer(), ppRequest},
                    WinNT.HRESULT.class);
            if (W32Errors.FAILED(hr) || ppRequest.getValue() == null)
                return null;
            return new UIAutomationCacheRequest(ppRequest.getValue());
        }

        // IUIAutomation::CreateTrueCondition — vtable index 21
        UIAutomationCondition createTrueCondition() {
            PointerByReference ppCondition = new PointerByReference();
            WinNT.HRESULT hr = (WinNT.HRESULT) _invokeNativeObject(21,
                    new Object[]{getPointer(), ppCondition},
                    WinNT.HRESULT.class);
            if (W32Errors.FAILED(hr) || ppCondition.getValue() == null)
                return null;
            return new UIAutomationCondition(ppCondition.getValue());
        }

        // IUIAutomation::CreatePropertyCondition — vtable index 23
        // HRESULT CreatePropertyCondition(PROPERTYID, VARIANT, IUIAutomationCondition**)
        // On x64, VARIANT (16 bytes) is passed by hidden pointer.
        UIAutomationCondition createPropertyCondition(int propertyId,
                                                       Memory variant) {
            PointerByReference ppCondition = new PointerByReference();
            WinNT.HRESULT hr = (WinNT.HRESULT) _invokeNativeObject(23,
                    new Object[]{getPointer(), propertyId, variant,
                            ppCondition},
                    WinNT.HRESULT.class);
            if (W32Errors.FAILED(hr) || ppCondition.getValue() == null) {
                logger.warn("CreatePropertyCondition({}) failed: 0x{}",
                        propertyId,
                        Integer.toHexString(hr.intValue()));
                return null;
            }
            return new UIAutomationCondition(ppCondition.getValue());
        }

        // IUIAutomation::CreateAndCondition — vtable index 25
        UIAutomationCondition createAndCondition(UIAutomationCondition c1,
                                                  UIAutomationCondition c2) {
            PointerByReference ppCondition = new PointerByReference();
            WinNT.HRESULT hr = (WinNT.HRESULT) _invokeNativeObject(25,
                    new Object[]{getPointer(), c1.getPointer(),
                            c2.getPointer(), ppCondition},
                    WinNT.HRESULT.class);
            if (W32Errors.FAILED(hr) || ppCondition.getValue() == null) {
                logger.warn("CreateAndCondition failed: 0x{}",
                        Integer.toHexString(hr.intValue()));
                return null;
            }
            return new UIAutomationCondition(ppCondition.getValue());
        }

        // IUIAutomation::CreateOrCondition — vtable index 28
        // (25=CreateAndCondition, 26=..FromArray, 27=..FromNativeArray, 28=CreateOrCondition)
        UIAutomationCondition createOrCondition(UIAutomationCondition c1,
                                                 UIAutomationCondition c2) {
            PointerByReference ppCondition = new PointerByReference();
            WinNT.HRESULT hr = (WinNT.HRESULT) _invokeNativeObject(28,
                    new Object[]{getPointer(), c1.getPointer(),
                            c2.getPointer(), ppCondition},
                    WinNT.HRESULT.class);
            if (W32Errors.FAILED(hr) || ppCondition.getValue() == null) {
                logger.warn("CreateOrCondition failed: 0x{}",
                        Integer.toHexString(hr.intValue()));
                return null;
            }
            return new UIAutomationCondition(ppCondition.getValue());
        }

    }

    private static class UIAutomationElement extends Unknown {

        UIAutomationElement(Pointer p) {
            super(p);
        }

        // IUIAutomationElement::GetCurrentPattern — vtable index 16.
        // The result is an IUnknown. QueryInterface for the text pattern before
        // calling its methods; the IUnknown vtable does not have them.
        UIAutomationTextPattern textPattern() {
            PointerByReference pattern = new PointerByReference();
            WinNT.HRESULT hr = (WinNT.HRESULT) _invokeNativeObject(16,
                    new Object[]{getPointer(), UIA_TextPatternId, pattern},
                    WinNT.HRESULT.class);
            if (W32Errors.FAILED(hr) || pattern.getValue() == null)
                return null;
            Unknown unknown = new Unknown(pattern.getValue());
            try {
                PointerByReference queried = new PointerByReference();
                hr = unknown.QueryInterface(new Guid.REFIID(IID_IUIAutomationTextPattern),
                        queried);
                if (W32Errors.FAILED(hr) || queried.getValue() == null)
                    return null;
                return new UIAutomationTextPattern(queried.getValue());
            }
            finally {
                unknown.Release();
            }
        }

        // IUIAutomationElement::get_CurrentProcessId — vtable index 20
        int processId() {
            IntByReference processId = new IntByReference();
            WinNT.HRESULT hr = (WinNT.HRESULT) _invokeNativeObject(20,
                    new Object[]{getPointer(), processId},
                    WinNT.HRESULT.class);
            if (W32Errors.FAILED(hr))
                return -1;
            return processId.getValue();
        }

        // IUIAutomationElement::FindAllBuildCache — vtable index 8
        UIAutomationElementArray findAllBuildCache(
                int scope, UIAutomationCondition condition,
                UIAutomationCacheRequest cacheRequest) {
            PointerByReference ppArray = new PointerByReference();
            WinNT.HRESULT hr = (WinNT.HRESULT) _invokeNativeObject(8,
                    new Object[]{getPointer(), scope,
                            condition.getPointer(),
                            cacheRequest.getPointer(), ppArray},
                    WinNT.HRESULT.class);
            if (W32Errors.FAILED(hr) || ppArray.getValue() == null)
                return null;
            return new UIAutomationElementArray(ppArray.getValue());
        }

        // IUIAutomationElement::GetCachedPropertyValue — vtable index 12
        /** The control's Name, or null when it has none. The caller matches tags against it. */
        String getCachedName() {
            Memory variant = new Memory(24);
            variant.clear();
            WinNT.HRESULT hr = (WinNT.HRESULT) _invokeNativeObject(12,
                    new Object[]{getPointer(), UIA_NamePropertyId, variant},
                    WinNT.HRESULT.class);
            if (W32Errors.FAILED(hr) || variant.getShort(0) != VT_BSTR)
                return null;
            Pointer pointer = variant.getPointer(8);
            if (pointer == null)
                return null;
            WTypes.BSTR bstr = new WTypes.BSTR(pointer);
            try {
                String value = bstr.getValue();
                if (value == null || value.isBlank())
                    return null;
                return value;
            }
            finally {
                OleAuto.INSTANCE.SysFreeString(bstr);
            }
        }

        int getCachedControlType() {
            Memory variant = new Memory(24);
            variant.clear();
            WinNT.HRESULT hr = (WinNT.HRESULT) _invokeNativeObject(12,
                    new Object[]{getPointer(), UIA_ControlTypePropertyId, variant},
                    WinNT.HRESULT.class);
            if (W32Errors.FAILED(hr) || variant.getShort(0) != VT_I4)
                return 0;
            return variant.getInt(8);
        }

        /** Whether one of the cached click patterns is available. False when none was cached. */
        boolean isCachedClickable() {
            for (int propertyId : CLICK_PATTERN_PROPERTY_IDS) {
                Memory variant = new Memory(24);
                variant.clear();
                WinNT.HRESULT hr = (WinNT.HRESULT) _invokeNativeObject(12,
                        new Object[]{getPointer(), propertyId, variant},
                        WinNT.HRESULT.class);
                if (!W32Errors.FAILED(hr) && variant.getShort(0) == VT_BOOL &&
                    variant.getShort(8) != VARIANT_FALSE)
                    return true;
            }
            return false;
        }

        // IUIAutomationElement::get_CachedBoundingRectangle — vtable index 75
        WinDef.RECT getCachedBoundingRectangle() {
            WinDef.RECT rect = new WinDef.RECT();
            WinNT.HRESULT hr = (WinNT.HRESULT) _invokeNativeObject(75,
                    new Object[]{getPointer(), rect},
                    WinNT.HRESULT.class);
            if (W32Errors.FAILED(hr))
                return null;
            return rect;
        }
    }

    private static class UIAutomationElementArray extends Unknown {

        UIAutomationElementArray(Pointer p) {
            super(p);
        }

        // IUIAutomationElementArray::get_Length — vtable index 3
        int getLength() {
            IntByReference pRetVal = new IntByReference();
            WinNT.HRESULT hr = (WinNT.HRESULT) _invokeNativeObject(3,
                    new Object[]{getPointer(), pRetVal},
                    WinNT.HRESULT.class);
            if (W32Errors.FAILED(hr))
                return 0;
            return pRetVal.getValue();
        }

        // IUIAutomationElementArray::GetElement — vtable index 4
        UIAutomationElement getElement(int index) {
            PointerByReference ppElement = new PointerByReference();
            WinNT.HRESULT hr = (WinNT.HRESULT) _invokeNativeObject(4,
                    new Object[]{getPointer(), index, ppElement},
                    WinNT.HRESULT.class);
            if (W32Errors.FAILED(hr) || ppElement.getValue() == null)
                return null;
            return new UIAutomationElement(ppElement.getValue());
        }
    }

    private static class UIAutomationCondition extends Unknown {

        UIAutomationCondition(Pointer p) {
            super(p);
        }
    }

    private static class UIAutomationCacheRequest extends Unknown {

        UIAutomationCacheRequest(Pointer p) {
            super(p);
        }

        // IUIAutomationCacheRequest::AddProperty — vtable index 3
        void addProperty(int propertyId) {
            WinNT.HRESULT hr = (WinNT.HRESULT) _invokeNativeObject(3,
                    new Object[]{getPointer(), propertyId},
                    WinNT.HRESULT.class);
            if (W32Errors.FAILED(hr))
                logger.warn("AddProperty({}) failed: 0x{}",
                        propertyId, Integer.toHexString(hr.intValue()));
        }
    }

    private static class UIAutomationTreeWalker extends Unknown {

        UIAutomationTreeWalker(Pointer p) {
            super(p);
        }

        // IUIAutomationTreeWalker::GetParentElement — vtable index 3
        UIAutomationElement parent(UIAutomationElement element) {
            PointerByReference parent = new PointerByReference();
            WinNT.HRESULT hr = (WinNT.HRESULT) _invokeNativeObject(3,
                    new Object[]{getPointer(), element.getPointer(), parent},
                    WinNT.HRESULT.class);
            if (W32Errors.FAILED(hr) || parent.getValue() == null)
                return null;
            return new UIAutomationElement(parent.getValue());
        }
    }

    private static class UIAutomationTextPattern extends Unknown {

        UIAutomationTextPattern(Pointer p) {
            super(p);
        }

        // IUIAutomationTextPattern::RangeFromPoint — vtable index 3
        UIAutomationTextRange rangeFromPoint(int x, int y) {
            PointerByReference range = new PointerByReference();
            WinNT.HRESULT hr = (WinNT.HRESULT) _invokeNativeObject(3,
                    new Object[]{getPointer(), new WinDef.POINT.ByValue(x, y), range},
                    WinNT.HRESULT.class);
            if (W32Errors.FAILED(hr) || range.getValue() == null)
                return null;
            return new UIAutomationTextRange(range.getValue());
        }

        // IUIAutomationTextPattern::get_DocumentRange — vtable index 7
        UIAutomationTextRange documentRange() {
            PointerByReference range = new PointerByReference();
            WinNT.HRESULT hr = (WinNT.HRESULT) _invokeNativeObject(7,
                    new Object[]{getPointer(), range},
                    WinNT.HRESULT.class);
            if (W32Errors.FAILED(hr) || range.getValue() == null)
                return null;
            return new UIAutomationTextRange(range.getValue());
        }

        // IUIAutomationTextPattern::get_SupportedTextSelection — vtable index 8
        int supportedTextSelection() {
            IntByReference supported = new IntByReference(SUPPORTED_TEXT_SELECTION_NONE);
            WinNT.HRESULT hr = (WinNT.HRESULT) _invokeNativeObject(8,
                    new Object[]{getPointer(), supported},
                    WinNT.HRESULT.class);
            if (W32Errors.FAILED(hr))
                return SUPPORTED_TEXT_SELECTION_NONE;
            return supported.getValue();
        }
    }

    private static class UIAutomationTextRange extends Unknown {

        UIAutomationTextRange(Pointer p) {
            super(p);
        }

        // IUIAutomationTextRange::ExpandToEnclosingUnit — vtable index 6
        boolean expandToEnclosingUnit(int unit) {
            WinNT.HRESULT hr = (WinNT.HRESULT) _invokeNativeObject(6,
                    new Object[]{getPointer(), unit},
                    WinNT.HRESULT.class);
            return !W32Errors.FAILED(hr);
        }

        // IUIAutomationTextRange::GetText — vtable index 12
        String text(int maxLength) {
            PointerByReference result = new PointerByReference();
            WinNT.HRESULT hr = (WinNT.HRESULT) _invokeNativeObject(12,
                    new Object[]{getPointer(), maxLength, result},
                    WinNT.HRESULT.class);
            Pointer value = result.getValue();
            if (W32Errors.FAILED(hr) || value == null)
                return null;
            WTypes.BSTR bstr = new WTypes.BSTR(value);
            try {
                return bstr.getValue();
            }
            finally {
                OleAuto.INSTANCE.SysFreeString(bstr);
            }
        }

        // IUIAutomationTextRange::MoveEndpointByUnit — vtable index 14
        void trimTrailingWhitespace() {
            String value = text(TEXT_LIMIT_WORD);
            if (value == null || value.isEmpty())
                return;
            int trailing = 0;
            for (int index = value.length() - 1;
                 index >= 0 && Character.isWhitespace(value.charAt(index));
                 index--)
                trailing++;
            if (trailing == 0 || trailing == value.length())
                return;
            IntByReference moved = new IntByReference();
            _invokeNativeObject(14, new Object[]{getPointer(),
                    TEXT_PATTERN_RANGE_ENDPOINT_END, TEXT_UNIT_CHARACTER, -trailing, moved},
                    WinNT.HRESULT.class);
        }

        // IUIAutomationTextRange::Select — vtable index 16
        void select() {
            WinNT.HRESULT hr = (WinNT.HRESULT) _invokeNativeObject(16,
                    new Object[]{getPointer()},
                    WinNT.HRESULT.class);
            if (W32Errors.FAILED(hr))
                logger.debug("Text selection failed: 0x{}", Integer.toHexString(hr.intValue()));
        }
    }

}
