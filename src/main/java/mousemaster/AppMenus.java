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
        List<AppMenuConfiguration.Item> items = configuration.items();
        List<String> labels = items.stream().map(AppMenuConfiguration.Item::label).toList();
        // A menu that gives rows their own keys uses those. One that doesn't takes the
        // mode's keys from the top down.
        boolean ownKeys = items.stream().anyMatch(item -> item.tag() != null);
        if (ownKeys)
            menu = AppMenu.withTags(configuration.title(), labels,
                    items.stream().map(AppMenuConfiguration.Item::tag).toList());
        else
            menu = AppMenu.of(configuration.title(), labels, tagKeys);
    }

    /** Whether the open menu runs this key itself. */
    public boolean hasTag(Key key) {
        return menu.rowOf(key) >= 0;
    }

    public void move(int delta) {
        menu = menu.move(delta);
    }

    /** Moves the highlight to the item this key runs. False when the key runs no item. */
    public boolean selectTag(Key key) {
        int row = menu.rowOf(key);
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

    /** The keys of the highlighted item, ready to be played. Null when the item sends none. */
    public ResolvedMacro selectedMacro() {
        if (configuration == null || menu.isEmpty())
            return null;
        Macro macro = configuration.items().get(menu.selected()).macro();
        if (macro == null || macro.output().parallels().isEmpty())
            return null;
        // No combo matched here, so an alias in the output stands for all of its keys.
        return macro.resolve(new AliasResolution(Map.of(), macro.outputAliasAllKeys()));
    }

    /** Where the highlighted item puts the pointer. Null when it only sends keys. */
    public UiAim selectedAim() {
        if (configuration == null || menu.isEmpty())
            return null;
        return configuration.items().get(menu.selected()).aim();
    }

    /** Whether the highlighted item leaves activation mode once it has finished. */
    public boolean selectedThenIdle() {
        if (configuration == null || menu.isEmpty())
            return false;
        return configuration.items().get(menu.selected()).thenIdle();
    }

}
