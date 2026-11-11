package mousemaster.platform;

import mousemaster.WindowEntry;

import java.util.List;
import java.util.Map;

/**
 * A snapshot of the windows Alt-Tab would list, in that order.
 * The first entry is the window that was active when the snapshot was taken.
 */
public interface SwitchableWindows {

    SwitchableWindows NONE = new SwitchableWindows() {
        @Override
        public List<WindowEntry> entries() {
            return List.of();
        }

        @Override
        public boolean activate(int index) {
            return false;
        }
    };

    List<WindowEntry> entries();

    /** Reads program icons. Safe to call more than once. Does nothing when there are none. */
    default void loadIcons() {
    }

    /** Icon pixels filled in by {@link #loadIcons()}, keyed by {@link WindowEntry#iconName()}. */
    default Map<String, int[]> icons() {
        return Map.of();
    }

    /** Width and height of each array returned by {@link #icons()}. */
    default int iconPixelSize() {
        return 0;
    }

    /** Focus the window at {@code index}. False when the index is stale or focus was refused. */
    boolean activate(int index);

}
