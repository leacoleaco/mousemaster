package mousemaster.platform;

import mousemaster.Rectangle;

import java.util.List;
import java.util.concurrent.Future;

public interface UiAutomation {

    /** Elements of the active window and of its popups. */
    Future<List<UiElement>> startFindActiveWindowUiElements();

    /**
     * Elements of every visible window that intersects the area, cropped to the area.
     * Elements a window in front covers are left out.
     */
    Future<List<UiElement>> startFindUiElementsInArea(Rectangle area);

    record UiElement(Rectangle rectangle, Role role) {

        public UiElement(Rectangle rectangle) {
            this(rectangle, Role.OTHER);
        }

        public double centerX() {
            return rectangle.x() + rectangle.width() / 2d;
        }

        public double centerY() {
            return rectangle.y() + rectangle.height() / 2d;
        }

        /**
         * The kinds a UI-hint filter can keep. {@link #OTHER} is everything else, including
         * elements found by vision, which have no control type.
         */
        public enum Role {
            TEXT, BUTTON, LINK, OTHER;

            /** Windows UI Automation control type ids. A document is the page, not a field. */
            public static Role fromWindowsControlType(int controlTypeId) {
                return switch (controlTypeId) {
                    case 50004, 50003, 50016 -> TEXT;   // Edit, ComboBox, Spinner
                    case 50000, 50031 -> BUTTON;        // Button, SplitButton
                    case 50005 -> LINK;                 // Hyperlink
                    default -> OTHER;
                };
            }
        }

    }

    // Bounding rectangles are in zoomed (physical) pixels, so the threshold is
    // multiplied by the screen's scale.
    // 13 unzoomed px = 13 physical px at 100% scale, 40 physical px at 300%.
    double MIN_DISTANCE_BETWEEN_HINTS_UNZOOMED = 13;

    static boolean isTooCloseToExistingUiElements(List<UiElement> elements, double x,
                                                  double y, double scale) {
        double threshold = MIN_DISTANCE_BETWEEN_HINTS_UNZOOMED * scale;
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
