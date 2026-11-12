package mousemaster.platform;

import mousemaster.WindowEntry;

import java.util.List;

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

    /**
     * Takes in program icons that have finished loading. Does not block: the first
     * call starts the read. True when {@link #icons()} changed.
     */
    default boolean drainIcons() {
        return false;
    }

    /**
     * ARGB pixels of each entry's program icon, in the order of {@link #entries()}.
     * Null where no icon has been read. Windows of one program share an array.
     */
    default List<int[]> icons() {
        return List.of();
    }

    /** Width and height of each array returned by {@link #icons()}. */
    default int iconPixelSize() {
        return 0;
    }

    /** Focus the window at {@code index}. False when the index is stale or focus was refused. */
    boolean activate(int index);

}
