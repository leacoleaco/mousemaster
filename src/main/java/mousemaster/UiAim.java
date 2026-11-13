package mousemaster;

import mousemaster.platform.UiAutomation.UiElement;
import mousemaster.platform.UiAutomation.UiElement.Control;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a menu item puts the pointer, found by the control's accessibility name.
 * {@link Kind#CLICK} presses the named button. {@link Kind#POINT} moves into the
 * named panel: a tab or a toolbar of that name is the panel's edge, and the pointer
 * goes to the area just below it.
 */
public record UiAim(Kind kind, String name) {

    /** A named region large enough to land in, such as a file tree. */
    private static final int BODY_MIN_WIDTH = 160;
    private static final int BODY_MIN_HEIGHT = 120;
    /** The body of a panel under its tab, as opposed to the tab itself. */
    private static final int PANEL_MIN_WIDTH = 300;
    private static final int PANEL_MIN_HEIGHT = 150;

    public enum Kind {
        /** Press the left button on the control. */
        CLICK,
        /** Move the pointer into the control and leave it there. */
        POINT
    }

    public UiAim {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("A control needs a name");
        name = name.strip();
    }

    /**
     * The control to act on, or null when the window has none.
     * {@code window} is the foreground window, in screen pixels. It decides which
     * tree is the file explorer when the explorer is not named.
     */
    public UiElement locate(Rectangle window, List<UiElement> elements) {
        if (elements == null || elements.isEmpty())
            return null;
        return switch (kind) {
            case CLICK -> click(elements);
            case POINT -> point(window, elements);
        };
    }

    private UiElement click(List<UiElement> elements) {
        List<UiElement> buttons = new ArrayList<>();
        List<UiElement> others = new ArrayList<>();
        for (UiElement element : elements) {
            if (!nameStartsWith(element.name(), name))
                continue;
            Rectangle rectangle = element.rectangle();
            if (rectangle.width() < 8 || rectangle.height() < 8)
                continue;
            Control control = element.control();
            if (control == Control.TEXT || control == Control.DOCUMENT)
                continue;
            if (control == Control.BUTTON || control == Control.SPLIT_BUTTON)
                buttons.add(element);
            else if (area(rectangle) <= 80_000)
                others.add(element);
        }
        return smallest(buttons.isEmpty() ? others : buttons);
    }

    private UiElement point(Rectangle window, List<UiElement> elements) {
        List<UiElement> named = new ArrayList<>();
        for (UiElement element : elements) {
            Control control = element.control();
            // The menu bar and a sentence in a chat both say the name and are not the panel.
            if (control == Control.MENU_ITEM || control == Control.TEXT)
                continue;
            if (!nameStartsWith(element.name(), name))
                continue;
            Rectangle rectangle = element.rectangle();
            if (rectangle.width() < 8 || rectangle.height() < 8)
                continue;
            named.add(element);
        }
        UiElement body = largest(named, BODY_MIN_WIDTH, BODY_MIN_HEIGHT);
        if (body != null)
            return body;
        UiElement anchor = widest(named);
        if (anchor != null) {
            UiElement panel = panelBelow(anchor, elements);
            if (panel != null)
                return panel;
        }
        return explorerFallback(window, elements);
    }

    /**
     * The file tree is not always named Explorer. After the explorer is shown, it is
     * the large tree at the left of the window. The editor, and source control, are not.
     */
    private UiElement explorerFallback(Rectangle window, List<UiElement> elements) {
        if (window == null || window.isEmpty() || !name.toLowerCase().contains("explorer"))
            return null;
        int leftLimit = window.x() + Math.min(480, Math.max(window.width() / 8, 1));
        UiElement best = null;
        long bestArea = -1;
        for (UiElement element : elements) {
            if (element.control() != Control.TREE)
                continue;
            Rectangle rectangle = element.rectangle();
            if (rectangle.x() > leftLimit)
                continue;
            if (rectangle.width() < BODY_MIN_WIDTH || rectangle.height() < 200)
                continue;
            String elementName = element.name();
            if (elementName != null && elementName.toLowerCase().contains("source control"))
                continue;
            long elementArea = area(rectangle);
            if (elementArea > bestArea) {
                best = element;
                bestArea = elementArea;
            }
        }
        return best;
    }

    /** The large region whose top edge sits on the anchor's bottom edge. */
    private static UiElement panelBelow(UiElement anchor, List<UiElement> elements) {
        Rectangle anchorRectangle = anchor.rectangle();
        int anchorBottom = anchorRectangle.y() + anchorRectangle.height();
        UiElement best = null;
        long bestArea = -1;
        for (UiElement element : elements) {
            if (element == anchor)
                continue;
            Rectangle rectangle = element.rectangle();
            if (rectangle.width() < PANEL_MIN_WIDTH || rectangle.height() < PANEL_MIN_HEIGHT)
                continue;
            if (rectangle.y() < anchorBottom - 40 || rectangle.y() > anchorBottom + 140)
                continue;
            if (horizontalOverlap(anchorRectangle, rectangle) < 0.35)
                continue;
            long elementArea = area(rectangle);
            if (elementArea > bestArea) {
                best = element;
                bestArea = elementArea;
            }
        }
        return best;
    }

    /** How much of the narrower rectangle's width the two share. */
    private static double horizontalOverlap(Rectangle first, Rectangle second) {
        int left = Math.max(first.x(), second.x());
        int right = Math.min(first.x() + first.width(), second.x() + second.width());
        int overlap = right - left;
        if (overlap <= 0)
            return 0;
        return (double) overlap / Math.min(first.width(), second.width());
    }

    /**
     * The name is the query, or the query followed by a space or a parenthesis,
     * as in {@code Terminal (Ctrl+`)}. A sentence that merely mentions the query does not match.
     */
    static boolean nameStartsWith(String name, String query) {
        if (name == null || query == null || name.length() < query.length())
            return false;
        if (!name.regionMatches(true, 0, query, 0, query.length()))
            return false;
        if (name.length() == query.length())
            return true;
        char next = name.charAt(query.length());
        return next == ' ' || next == '(' || next == ',' || next == ':' || next == '\u00a0';
    }

    private static UiElement largest(List<UiElement> elements, int minWidth, int minHeight) {
        UiElement best = null;
        long bestArea = -1;
        for (UiElement element : elements) {
            Rectangle rectangle = element.rectangle();
            if (rectangle.width() < minWidth || rectangle.height() < minHeight)
                continue;
            long elementArea = area(rectangle);
            if (elementArea > bestArea) {
                best = element;
                bestArea = elementArea;
            }
        }
        return best;
    }

    private static UiElement widest(List<UiElement> elements) {
        UiElement best = null;
        int bestWidth = -1;
        for (UiElement element : elements) {
            int width = element.rectangle().width();
            if (width > bestWidth) {
                best = element;
                bestWidth = width;
            }
        }
        return best;
    }

    private static UiElement smallest(List<UiElement> elements) {
        UiElement best = null;
        long bestArea = Long.MAX_VALUE;
        for (UiElement element : elements) {
            long elementArea = area(element.rectangle());
            if (elementArea < bestArea) {
                best = element;
                bestArea = elementArea;
            }
        }
        return best;
    }

    private static long area(Rectangle rectangle) {
        return (long) rectangle.width() * rectangle.height();
    }

}
