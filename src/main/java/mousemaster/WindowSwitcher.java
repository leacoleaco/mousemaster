package mousemaster;

import mousemaster.platform.SwitchableWindows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Owns the window list for window-switch-mode. The list is taken once, on entry,
 * in Alt-Tab order, and stays put while e and d move the highlight.
 * Program icons are read after the keyboard hook returns, so opening the list does
 * not stall the hook.
 */
public final class WindowSwitcher implements ModeListener {

    public static final String MODE = "window-switch-mode";

    private static final Logger logger = LoggerFactory.getLogger(WindowSwitcher.class);

    private final Platform platform;
    private String modeName;
    private SwitchableWindows windows = SwitchableWindows.NONE;
    private WindowMenu menu = WindowMenu.empty();
    private boolean iconsLoaded;

    public WindowSwitcher(Platform platform) {
        this.platform = platform;
    }

    @Override
    public void modeChanged(Mode newMode) {
        if (MODE.equals(newMode.name()) && !MODE.equals(modeName))
            open();
        modeName = newMode.name();
    }

    @Override
    public void modeTimedOut() {
    }

    public void move(int delta) {
        menu = menu.move(delta);
    }

    public String html() {
        if (!iconsLoaded && !platform.inKeyboardHook()) {
            try {
                windows.loadIcons();
                int pixelSize = windows.iconPixelSize();
                for (Map.Entry<String, int[]> icon : windows.icons().entrySet())
                    WindowIconImages.add(icon.getKey(), icon.getValue(), pixelSize);
                menu = menu.withEntries(windows.entries());
            }
            catch (RuntimeException exception) {
                logger.warn("Could not load window icons", exception);
            }
            iconsLoaded = true;
        }
        return menu.html();
    }

    /** Focus the highlighted window. False when the list is empty or the focus was refused. */
    public boolean activateSelected() {
        if (menu.isEmpty())
            return false;
        return windows.activate(menu.selected());
    }

    private void open() {
        windows = platform.switchableWindows();
        menu = WindowMenu.of(windows.entries());
        iconsLoaded = false;
    }

}
