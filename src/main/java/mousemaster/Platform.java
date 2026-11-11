package mousemaster;

import mousemaster.platform.ActiveAppFinder;
import mousemaster.platform.Console;
import mousemaster.platform.KeyboardController;
import mousemaster.platform.Overlay;
import mousemaster.platform.SwitchableWindows;
import mousemaster.platform.UiAutomation;

import java.util.List;
import java.util.Set;

public interface Platform extends ModeListener {

    void update(double delta);

    void pumpEvents();

    void sleep() throws InterruptedException;

    void reset(MouseManager mouseManager, KeyboardManager keyboardManager,
               KeyRegurgitator keyRegurgitator, MacroPlayer macroPlayer,
               KeyRedactor keyRedactor,
               boolean logLastKeyEventsOnExit,
               ModeMap modeMap,
               ZoomManager zoomManager,
               IndicatorManager indicatorManager,
               HintManager hintManager,
               List<MousePositionListener> mousePositionListeners,
               KeyboardLayout activeKeyboardLayout,
               boolean suppressCapsLockOsPassthrough);

    void shutdown();

    /**
     * Shuts down, then kills the process instead of exiting it: Qt's and the CRT's teardown
     * warns and can hang for seconds when it runs off the main thread.
     */
    void killProcess(int exitCode);

    void requestKillProcess();

    void requestRestartProcess();

    /** Dim the screen and ring the cursor. Platforms without that overlay do nothing. */
    default void showCursorSpotlight() {
    }

    /**
     * When suspended, keyboard and mouse hooks pass events to the operating system
     * and mousemaster does not eat them.
     */
    void setHooksSuspended(boolean suspended);

    Clock clock();

    KeyboardLayout activeKeyboardLayout();

    KeyboardController keyboard();

    mousemaster.platform.MouseController mouse();

    Set<Screen> screens();

    Overlay overlay();

    /** Windows in Alt-Tab order. Platforms without that list return an empty catalog. */
    default SwitchableWindows switchableWindows() {
        return SwitchableWindows.NONE;
    }

    /** True while a low-level keyboard hook is on the stack. Icon work waits until it returns. */
    default boolean inKeyboardHook() {
        return false;
    }

    UiAutomation uiAutomation();

    Vision vision();

    ActiveAppFinder activeAppFinder();

    Console console();

}
