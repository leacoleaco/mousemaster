package mousemaster;

import mousemaster.platform.UiAutomation.UiElement;

import java.util.List;

/**
 * overlapped: the box of its whole label would sit on the box of another hint. Until a key
 * is selected, such a hint shows the first key of its label only.
 */
public record Hint(double centerX, double centerY, double cellWidth, double cellHeight,
                   List<Key> keySequence, Rectangle elementRectangle, UiElement.Role role,
                   UiElement.Control control, boolean overlapped) {

    public Hint(double centerX, double centerY, double cellWidth, double cellHeight,
                List<Key> keySequence, Rectangle elementRectangle, UiElement.Role role,
                UiElement.Control control) {
        this(centerX, centerY, cellWidth, cellHeight, keySequence, elementRectangle, role,
                control, false);
    }

    public Hint(double centerX, double centerY, double cellWidth, double cellHeight,
                List<Key> keySequence, Rectangle elementRectangle, UiElement.Role role) {
        this(centerX, centerY, cellWidth, cellHeight, keySequence, elementRectangle, role,
                null);
    }

    public Hint(double centerX, double centerY, double cellWidth, double cellHeight,
                List<Key> keySequence, Rectangle elementRectangle) {
        this(centerX, centerY, cellWidth, cellHeight, keySequence, elementRectangle, null,
                null);
    }

    /** The same hint under another label. */
    public Hint labeled(List<Key> keySequence, boolean overlapped) {
        return new Hint(centerX, centerY, cellWidth, cellHeight, keySequence,
                elementRectangle, role, control, overlapped);
    }

    public boolean startsWith(List<Key> selectedHintKeySequence) {
        if (selectedHintKeySequence.isEmpty())
            return true;
        if (keySequence.size() < selectedHintKeySequence.size())
            return false;
        return keySequence.subList(0, selectedHintKeySequence.size())
                          .equals(selectedHintKeySequence);
    }

}
