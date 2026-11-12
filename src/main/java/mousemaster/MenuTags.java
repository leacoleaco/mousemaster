package mousemaster;

import java.util.List;
import java.util.function.Function;

/**
 * The letters beside the rows of the window list and of the program menu.
 * Pressing a row's letter moves the highlight to it. The letters are handed out
 * from the top row down, in the order the configuration lists the keys.
 */
final class MenuTags {

    private MenuTags() {
    }

    /**
     * The tag keys of the mode's tag command, or none when the mode has no such command.
     * The command carries the keys so that what is drawn is what the combos accept.
     */
    static <C extends Command> List<Key> keys(Mode mode, Class<C> commandType,
                                              Function<C, List<Key>> tagKeys) {
        for (List<Command> commands : mode.comboMap().commandsByCombo().values())
            for (Command command : commands)
                if (commandType.isInstance(command))
                    return tagKeys.apply(commandType.cast(command));
        return List.of();
    }

    /** One label per row, until the rows, the keys, or {@code maxRows} run out. */
    static List<String> labels(List<Key> tagKeys, int rowCount, int maxRows) {
        int count = Math.min(Math.min(tagKeys.size(), rowCount), maxRows);
        return tagKeys.subList(0, count).stream().map(Key::hintLabel).toList();
    }

    /** The row the key tags, or -1 when the key tags none of the {@code tagCount} rows. */
    static int row(List<Key> tagKeys, Key key, int tagCount) {
        if (key == null)
            return -1;
        int index = tagKeys.indexOf(key);
        return index < tagCount ? index : -1;
    }

}
