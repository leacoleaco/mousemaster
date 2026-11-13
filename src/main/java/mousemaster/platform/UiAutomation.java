package mousemaster.platform;

import mousemaster.Rectangle;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

public interface UiAutomation {

    /** Elements of the active window and of its popups. */
    Future<List<UiElement>> startFindActiveWindowUiElements();

    /**
     * Elements of the top-level window under the point, and of its popups.
     * The point is in screen pixels.
     */
    default Future<List<UiElement>> startFindMouseWindowUiElements(int x, int y) {
        return startFindActiveWindowUiElements();
    }

    /**
     * Elements of every visible window that intersects the area, cropped to the area.
     * Elements a window in front covers are left out.
     */
    Future<List<UiElement>> startFindUiElementsInArea(Rectangle area);

    /**
     * Elements of the windows {@link #startFindMouseWindowUiElements} leaves out: every
     * visible window that intersects the area, except the top-level window under the point
     * and its popups. Cropped to the area, and elements a window in front covers are left
     * out. The scan does not hold up a scan of the window under the pointer, and
     * cancelling the future stops it at the next window.
     */
    default Future<List<UiElement>> startFindOtherWindowsUiElements(int x, int y,
                                                                    Rectangle area) {
        return CompletableFuture.completedFuture(List.of());
    }

    /**
     * Controls of the active window, including panels that are only pointed at.
     * The future completes away from the thread that reads the keyboard.
     */
    default Future<WindowControls> startFindActiveWindowControls() {
        return CompletableFuture.completedFuture(
                new WindowControls(new Rectangle(0, 0, 0, 0), List.of()));
    }

    /**
     * The controls of one window, in screen pixels.
     * A control that has no name has a null name.
     */
    record WindowControls(Rectangle window, List<UiElement> elements) {}

    /** How much of the text at the pointer to copy. */
    enum TextExtent {
        /** The word around the pointer. */
        WORD,
        /** The line around the pointer. */
        LINE,
        /** The whole document that contains the pointer. */
        ALL
    }

    /**
     * Copy that much of the text at the pointer onto the clipboard.
     * The future completes away from the thread that reads the keyboard.
     * It is false when the pointer is not over text the platform can read,
     * so the caller selects it with clicks and copies with the keyboard.
     */
    default Future<Boolean> startCopyText(TextExtent extent) {
        return CompletableFuture.completedFuture(false);
    }

    record UiElement(Rectangle rectangle, Role role, Control control, String name) {

        public UiElement(Rectangle rectangle, Role role, Control control) {
            this(rectangle, role, control, null);
        }

        public UiElement(Rectangle rectangle, Role role) {
            this(rectangle, role, null);
        }

        /** A rectangle whose control type was not reported. Vision hints use this. */
        public UiElement(Rectangle rectangle) {
            this(rectangle, null);
        }

        public double centerX() {
            return rectangle.x() + rectangle.width() / 2d;
        }

        public double centerY() {
            return rectangle.y() + rectangle.height() / 2d;
        }

        /**
         * What selecting the hint of an element is for. {@link #CLICK} is only pointed at,
         * {@link #INPUT} is clicked and then typed into, {@link #WIDGET} is operated with
         * keys of its own (a scroll bar), {@link #OTHER} is only pointed at. A null role
         * was not classified at all.
         */
        public enum Role {
            CLICK("click"), INPUT("input"), WIDGET("widget"), OTHER("other");

            /** The name in the configuration, such as {@code click} for {@code click-box-color}. */
            private final String propertyName;

            Role(String propertyName) {
                this.propertyName = propertyName;
            }

            public String propertyName() {
                return propertyName;
            }

            /** Null when no role bears that name. */
            public static Role fromPropertyName(String propertyName) {
                for (Role role : values())
                    if (role.propertyName.equals(propertyName))
                        return role;
                return null;
            }

            /** The role of a Windows UI Automation control type id, going by the type alone. */
            public static Role fromWindowsControlType(int controlTypeId) {
                return of(Control.fromWindowsControlType(controlTypeId), false);
            }

            /**
             * The role of a control. A type that is only pointed at going by its type (an
             * image, a group, a text) is classified as click when the application says it
             * reacts to a click: {@code clickable} is whether it can be invoked, toggled, selected,
             * or expanded. A container stays pointed at: a click in the middle of a
             * tree lands on whichever item happens to be there.
             */
            public static Role of(Control control, boolean clickable) {
                Role typeRole = control == null ? OTHER : control.role();
                if (typeRole != OTHER || !clickable)
                    return typeRole;
                return control != null && control.container() ? OTHER : CLICK;
            }
        }

        /**
         * One Windows UI Automation control type, with the role its type gives it. A
         * document is the page, not a field. A menu, a list, a tree and a tab are the
         * containers of the items that are clicked.
         */
        public enum Control {
            BUTTON(50000, Role.CLICK, "button"),
            CALENDAR(50001, Role.OTHER, "calendar"),
            CHECK_BOX(50002, Role.CLICK, "checkbox"),
            COMBO_BOX(50003, Role.INPUT, "combo-box"),
            EDIT(50004, Role.INPUT, "edit"),
            HYPERLINK(50005, Role.CLICK, "hyperlink"),
            IMAGE(50006, Role.OTHER, "image"),
            LIST_ITEM(50007, Role.CLICK, "list-item"),
            LIST(50008, Role.OTHER, "list"),
            MENU(50009, Role.OTHER, "menu"),
            MENU_BAR(50010, Role.OTHER, "menu-bar"),
            MENU_ITEM(50011, Role.CLICK, "menu-item"),
            PROGRESS_BAR(50012, Role.OTHER, "progress-bar"),
            RADIO_BUTTON(50013, Role.CLICK, "radio-button"),
            SCROLL_BAR(50014, Role.WIDGET, "scroll-bar"),
            SLIDER(50015, Role.WIDGET, "slider"),
            SPINNER(50016, Role.INPUT, "spinner"),
            STATUS_BAR(50017, Role.OTHER, "status-bar"),
            TAB(50018, Role.OTHER, "tab"),
            TAB_ITEM(50019, Role.CLICK, "tab-item"),
            TEXT(50020, Role.OTHER, "static-text"),
            TOOL_BAR(50021, Role.OTHER, "tool-bar"),
            TOOL_TIP(50022, Role.OTHER, "tool-tip"),
            TREE(50023, Role.OTHER, "tree"),
            TREE_ITEM(50024, Role.CLICK, "tree-item"),
            CUSTOM(50025, Role.OTHER, "custom"),
            GROUP(50026, Role.OTHER, "group"),
            THUMB(50027, Role.OTHER, "thumb"),
            DATA_GRID(50028, Role.OTHER, "data-grid"),
            DATA_ITEM(50029, Role.CLICK, "data-item"),
            DOCUMENT(50030, Role.OTHER, "document"),
            SPLIT_BUTTON(50031, Role.CLICK, "split-button"),
            WINDOW(50032, Role.OTHER, "window"),
            PANE(50033, Role.OTHER, "pane"),
            HEADER(50034, Role.OTHER, "header"),
            HEADER_ITEM(50035, Role.CLICK, "header-item"),
            TABLE(50036, Role.OTHER, "table"),
            TITLE_BAR(50037, Role.OTHER, "title-bar"),
            SEPARATOR(50038, Role.OTHER, "separator"),
            SEMANTIC_ZOOM(50039, Role.OTHER, "semantic-zoom"),
            APP_BAR(50040, Role.OTHER, "app-bar");

            private static final java.util.Map<Integer, Control> BY_ID;
            private static final java.util.Map<String, Control> BY_BOX_COLOR_PROPERTY;

            static {
                java.util.Map<Integer, Control> byId = new java.util.HashMap<>();
                java.util.Map<String, Control> byProperty = new java.util.HashMap<>();
                for (Control control : values()) {
                    byId.put(control.windowsControlTypeId, control);
                    byProperty.put(control.propertyName + "-box-color", control);
                }
                BY_ID = java.util.Map.copyOf(byId);
                BY_BOX_COLOR_PROPERTY = java.util.Map.copyOf(byProperty);
            }

            private final int windowsControlTypeId;
            private final Role role;
            /** The name in the configuration, such as {@code checkbox} for {@code checkbox-box-color}. */
            private final String propertyName;

            Control(int windowsControlTypeId, Role role, String propertyName) {
                this.windowsControlTypeId = windowsControlTypeId;
                this.role = role;
                this.propertyName = propertyName;
            }

            /** Whether the control holds the controls that are acted on, rather than being one. */
            public boolean container() {
                return switch (this) {
                    case CALENDAR, LIST, MENU, MENU_BAR, STATUS_BAR, TAB, TOOL_BAR, TREE,
                         DATA_GRID, DOCUMENT, WINDOW, PANE, HEADER, TABLE, TITLE_BAR,
                         SEMANTIC_ZOOM, APP_BAR -> true;
                    default -> false;
                };
            }

            /** The role the type gives. See {@link Role#of} for the role of one element. */
            public Role role() {
                return role;
            }

            public String propertyName() {
                return propertyName;
            }

            public int windowsControlTypeId() {
                return windowsControlTypeId;
            }

            /** Null when Windows reports a control type this build does not know. */
            public static Control fromWindowsControlType(int controlTypeId) {
                return BY_ID.get(controlTypeId);
            }

            /** Null when {@code key} is not one control's box color, such as {@code checkbox-box-color}. */
            public static Control fromBoxColorProperty(String key) {
                return BY_BOX_COLOR_PROPERTY.get(key);
            }
        }

    }

    // Bounding rectangles are in zoomed (physical) pixels, so the threshold is
    // multiplied by the screen's scale.
    // 13 unzoomed px = 13 physical px at 100% scale, 40 physical px at 300%.
    double MIN_DISTANCE_BETWEEN_HINTS_UNZOOMED = 13;

    static boolean isTooCloseToExistingUiElements(List<UiElement> elements, double x,
                                                  double y, double scale) {
        return isTooCloseToExistingUiElements(elements, x, y, scale,
                MIN_DISTANCE_BETWEEN_HINTS_UNZOOMED);
    }

    static boolean isTooCloseToExistingUiElements(List<UiElement> elements, double x,
                                                  double y, double scale,
                                                  double distanceUnzoomed) {
        double threshold = distanceUnzoomed * scale;
        double thresholdSquared = threshold * threshold;
        for (UiElement e : elements) {
            double dx = e.centerX() - x;
            double dy = e.centerY() - y;
            if (dx * dx + dy * dy < thresholdSquared)
                return true;
        }
        return false;
    }
}
