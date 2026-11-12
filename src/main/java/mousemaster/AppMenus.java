package mousemaster;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Owns the menu for app-menu-mode. The menu is the one configured for the program in
 * front when the mode is entered, and stays put until the mode is left: the items do
 * not change if another window takes the foreground meanwhile.
 */
public final class AppMenus implements ModeListener {

    public static final String MODE = "app-menu-mode";

    private final List<AppMenuConfiguration> configurations;
    private final Supplier<App> activeApp;
    private String modeName;
    /** Null when the program in front has no menu. */
    private AppMenuConfiguration configuration;
    private AppMenu menu = AppMenu.empty(null);
    /** The keys that tag the items, as the mode's tag command lists them. */
    private List<Key> tagKeys = List.of();

    public AppMenus(List<AppMenuConfiguration> configurations, Supplier<App> activeApp) {
        this.configurations = List.copyOf(configurations);
        this.activeApp = activeApp;
    }

    @Override
    public void modeChanged(Mode newMode) {
        if (MODE.equals(newMode.name()) && !MODE.equals(modeName)) {
            tagKeys = MenuTags.keys(newMode, Command.RunMenuItemByTag.class,
                    Command.RunMenuItemByTag::tagKeys);
            open(activeApp.get());
        }
        modeName = newMode.name();
    }

    @Override
    public void modeTimedOut() {
    }

    private void open(App app) {
        configuration = null;
        for (AppMenuConfiguration candidate : configurations)
            if (candidate.matches(app)) {
                configuration = candidate;
                break;
            }
        if (configuration == null) {
            menu = AppMenu.empty(app == null ? null : app.executableName());
            return;
        }
        menu = AppMenu.of(configuration.title(),
                configuration.items().stream().map(AppMenuConfiguration.Item::label).toList(),
                tagKeys);
    }

    public void move(int delta) {
        menu = menu.move(delta);
    }

    /** Moves the highlight to the item this key tags. False when the key tags no item. */
    public boolean selectTag(Key key) {
        int row = MenuTags.row(tagKeys, key, menu.tagCount());
        if (row < 0)
            return false;
        menu = menu.select(row);
        return true;
    }

    public boolean isEmpty() {
        return menu.isEmpty();
    }

    public String html() {
        return menu.html();
    }

    /** The keys of the highlighted item, ready to be played. Null when there is no menu. */
    public ResolvedMacro selectedMacro() {
        if (configuration == null || menu.isEmpty())
            return null;
        Macro macro = configuration.items().get(menu.selected()).macro();
        // No combo matched here, so an alias in the output stands for all of its keys.
        return macro.resolve(new AliasResolution(Map.of(), macro.outputAliasAllKeys()));
    }

}
