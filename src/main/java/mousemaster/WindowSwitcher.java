package mousemaster;

import mousemaster.platform.SwitchableWindows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns the window list for window-switch-mode. The list is taken once, in Alt-Tab
 * order, and stays put while e and d move the highlight.
 * The key that opens the list arrives inside the keyboard hook, which Windows removes
 * when its callback takes too long. The hook therefore draws only a title line, the
 * windows are enumerated on the next frame, and program icons are filled in once a
 * background thread has read them.
 */
public final class WindowSwitcher implements ModeListener {

    public static final String MODE = "window-switch-mode";

    /** Drawn while the hook is still on the stack, so the callback stays cheap. */
    private static final String LOADING_HTML =
            "<table cellspacing=0 cellpadding=0><tr><td class=title>窗口</td></tr></table>";

    private static final Logger logger = LoggerFactory.getLogger(WindowSwitcher.class);

    private final Platform platform;
    private String modeName;
    private SwitchableWindows windows = SwitchableWindows.NONE;
    private WindowMenu menu = WindowMenu.empty();
    private boolean listReady;
    /** The keys that tag the rows of the first page, as the mode's tag command lists them. */
    private List<Key> tagKeys = List.of();

    public WindowSwitcher(Platform platform) {
        this.platform = platform;
    }

    @Override
    public void modeChanged(Mode newMode) {
        if (MODE.equals(newMode.name()) && !MODE.equals(modeName)) {
            listReady = false;
            windows = SwitchableWindows.NONE;
            menu = WindowMenu.empty();
            tagKeys = MenuTags.keys(newMode, Command.SelectWindowByTag.class,
                    Command.SelectWindowByTag::tagKeys);
        }
        modeName = newMode.name();
    }

    @Override
    public void modeTimedOut() {
    }

    public void move(int delta) {
        menu = menu.move(delta);
    }

    /** Moves the highlight to the row this key tags. A key that tags no row does nothing. */
    public void selectTag(Key key) {
        menu = menu.select(MenuTags.row(tagKeys, key, menu.tagCount()));
    }

    public String html() {
        if (!listReady) {
            if (platform.inKeyboardHook())
                return LOADING_HTML;
            ensureListed();
        }
        else if (!platform.inKeyboardHook())
            showIcons();
        return menu.html();
    }

    /** Focus the highlighted window. False when the list is empty or the focus was refused. */
    public boolean activateSelected() {
        if (menu.isEmpty())
            return false;
        return windows.activate(menu.selected());
    }

    private void ensureListed() {
        try {
            windows = platform.switchableWindows();
            menu = WindowMenu.of(windows.entries(), tagKeys);
        }
        catch (Throwable failure) {
            logger.error("Could not list windows", failure);
            windows = SwitchableWindows.NONE;
            menu = WindowMenu.empty();
        }
        listReady = true;
    }

    /** Puts icons on the rows once the background read has finished. */
    private void showIcons() {
        try {
            if (!windows.drainIcons())
                return;
            List<WindowEntry> entries = windows.entries();
            List<int[]> icons = windows.icons();
            int pixelSize = windows.iconPixelSize();
            List<WindowEntry> withIcons = new ArrayList<>(entries.size());
            int shown = 0;
            for (int index = 0; index < entries.size(); index++) {
                WindowEntry entry = entries.get(index);
                int[] argb = index < icons.size() ? icons.get(index) : null;
                String source = WindowIconImages.source(argb, pixelSize);
                if (source != null)
                    shown++;
                withIcons.add(new WindowEntry(entry.title(), entry.processName(), source));
            }
            menu = menu.withEntries(withIcons);
            logger.debug("Window icons: " + shown + "/" + entries.size());
        }
        catch (Throwable failure) {
            logger.error("Could not draw window icons", failure);
        }
    }

}
