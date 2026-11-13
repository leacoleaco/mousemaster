package mousemaster;

import java.util.List;
import java.util.Set;

/**
 * The menu of one program, from the app-menu.* properties. {@code name} is the app
 * alias the menu was declared for, and {@code apps} are the executables of that alias.
 * Choosing an item plays its macro, which sends the program its own shortcut,
 * and may also move the pointer onto a control.
 */
public record AppMenuConfiguration(String name, Set<App> apps, String title,
                                   List<Item> items) {

    /**
     * {@code tag} is the key that runs this item at once. Null when the row has no key.
     * {@code macro} is null when the item only moves the pointer.
     * {@code aim} is null when the item only sends keys.
     */
    public record Item(String name, String label, Key tag, Macro macro, UiAim aim) {
    }

    public boolean matches(App app) {
        return app != null && apps.contains(app);
    }

}
