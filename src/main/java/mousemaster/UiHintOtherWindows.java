package mousemaster;

/**
 * Where the windows other than the one under the pointer are looked in, once the hints of
 * the window under the pointer are shown.
 */
public enum UiHintOtherWindows {

    /** Only the window under the pointer gets hints. */
    NONE,
    /** The other windows that show on the screen the pointer is on. */
    ACTIVE_SCREEN,
    /** The other windows that show on any screen. */
    ALL_SCREENS

}
