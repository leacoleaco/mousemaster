package mousemaster;

import mousemaster.platform.UiAutomation.UiElement;
import mousemaster.platform.UiAutomation.UiElement.Control;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a menu item puts the pointer, found by the control's accessibility name.
 * {@link Kind#CLICK} presses the named button. {@link Kind#POINT} moves into the
 * named panel: a tab or a toolbar of that name is the panel's edge, and the pointer
 * goes to the area just below it. {@link Kind#MENU} presses a menu-bar item, then
 * the command that appears under it. A command keeps its label when its shortcut changes.
 */
public record UiAim(Kind kind, String name, String item) {

    /** A named region large enough to land in, such as a file tree. */
    private static final int BODY_MIN_WIDTH = 160;
    private static final int BODY_MIN_HEIGHT = 120;
    /** The body of a panel under its tab, as opposed to the tab itself. */
    private static final int PANEL_MIN_WIDTH = 300;
    private static final int PANEL_MIN_HEIGHT = 150;
    /** Menu-bar items sit in this band under the top of the window. */
    private static final int MENU_BAR_BAND = 120;

    public enum Kind {
        /** Press the left button on the control. */
        CLICK,
        /** Move the pointer into the control and leave it there. */
        POINT,
        /** Open a menu, then press the command in it. */
        MENU
    }

    public UiAim(Kind kind, String name) {
        this(kind, name, null);
    }

    public UiAim {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("A control needs a name");
        name = name.strip();
        if (item != null)
            item = item.strip();
        if (kind == Kind.MENU) {
            if (item == null || item.isBlank())
                throw new IllegalArgumentException("A menu needs the menu and the item");
        }
        else if (item != null)
            throw new IllegalArgumentException("Only a menu names two controls");
    }

    /**
     * The control to act on, or null when the window has none.
     * {@code window} is the foreground window, in screen pixels. It decides which
     * tree is the file explorer when the explorer is not named. For a menu, this is
     * the menu-bar item; the command under it is {@link #locateItem}.
     */
    public UiElement locate(Rectangle window, List<UiElement> elements) {
        if (elements == null || elements.isEmpty())
            return null;
        return switch (kind) {
            case CLICK -> click(elements);
            case POINT -> point(window, elements);
            case MENU -> menuItem(window, elements, name, true);
        };
    }

    /** The command shown after the menu-bar item has been opened. */
    public UiElement locateItem(Rectangle window, List<UiElement> elements) {
        if (kind != Kind.MENU || elements == null || elements.isEmpty())
            return null;
        return menuItem(window, elements, item, false);
    }

    /**
     * A menu entry whose label is one of {@code alternatives}, separated by {@code |}.
     * The menu bar is the matching entry at the top of the window. The command is the
     * matching entry below that. {@code Find} matches {@code Find Ctrl+F} and not
     * {@code Find in Files}.
     */
    private static UiElement menuItem(Rectangle window, List<UiElement> elements,
                                     String alternatives, boolean menuBar) {
        int bandBottom = window == null || window.isEmpty()
                ? Integer.MIN_VALUE : window.y() + MENU_BAR_BAND;
        UiElement best = null;
        int bestTop = Integer.MAX_VALUE;
        for (UiElement element : elements) {
            if (element.control() != Control.MENU_ITEM)
                continue;
            if (!menuLabel(element.name(), alternatives))
                continue;
            Rectangle rectangle = element.rectangle();
            if (rectangle.width() < 8 || rectangle.height() < 8)
                continue;
            boolean inBar = bandBottom != Integer.MIN_VALUE && rectangle.y() <= bandBottom;
            if (menuBar != inBar && bandBottom != Integer.MIN_VALUE)
                continue;
            if (rectangle.y() < bestTop) {
                best = element;
                bestTop = rectangle.y();
            }
        }
        return best;
    }

    /**
     * The accessible name is the label, or the label followed by its shortcut,
     * as in {@code Find Ctrl+F}. {@code alternatives} lists labels separated by {@code |}.
     */
    static boolean menuLabel(String accessibleName, String alternatives) {
        if (accessibleName == null || alternatives == null)
            return false;
        String label = accessibleName.strip();
        int tab = label.indexOf('\t');
        if (tab >= 0)
            label = label.substring(0, tab).strip();
        label = label.replaceFirst("(?i)\\s*\\(&[a-z0-9]\\)$", "").strip();
        for (String alternative : alternatives.split("\\|")) {
            String query = alternative.strip();
            if (query.isEmpty())
                continue;
            if (label.equalsIgnoreCase(query))
                return true;
            if (label.length() <= query.length()
                    || !label.regionMatches(true, 0, query, 0, query.length())
                    || label.charAt(query.length()) != ' ')
                continue;
            if (isShortcut(label.substring(query.length() + 1).strip()))
                return true;
        }
        return false;
    }

    /** {@code Ctrl+F}, {@code Ctrl+Shift+F}, {@code Shift+Alt+A}. Not further words. */
    private static boolean isShortcut(String text) {
        String[] parts = text.split("\\+");
        if (parts.length < 2)
            return false;
        for (int index = 0; index < parts.length - 1; index++) {
            if (!isModifier(parts[index].strip()))
                return false;
        }
        String key = parts[parts.length - 1].strip();
        return !key.isEmpty() && !key.contains(" ");
    }

    private static boolean isModifier(String word) {
        return word.equalsIgnoreCase("ctrl")
                || word.equalsIgnoreCase("control")
                || word.equalsIgnoreCase("alt")
                || word.equalsIgnoreCase("shift")
                || word.equalsIgnoreCase("cmd")
                || word.equalsIgnoreCase("win")
                || word.equalsIgnoreCase("option");
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
