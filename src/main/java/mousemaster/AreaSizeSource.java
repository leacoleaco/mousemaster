package mousemaster;

public enum AreaSizeSource {

    ACTIVE_SCREEN,
    ACTIVE_WINDOW,
    /** The top-level window under the pointer, whether or not it is focused. */
    MOUSE_WINDOW,
    LAST_SELECTED_HINT_CELL,
    LAST_OVERLAY_AREA,
    ALL_SCREENS

}
