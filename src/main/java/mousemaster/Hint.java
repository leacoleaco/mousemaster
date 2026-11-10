package mousemaster;

import mousemaster.platform.UiAutomation.UiElement;

import java.util.List;

public record Hint(double centerX, double centerY, double cellWidth, double cellHeight,
                   List<Key> keySequence, Rectangle elementRectangle, UiElement.Role role,
                   UiElement.Control control) {

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

    public boolean startsWith(List<Key> selectedHintKeySequence) {
        if (selectedHintKeySequence.isEmpty())
            return true;
        if (keySequence.size() < selectedHintKeySequence.size())
            return false;
        return keySequence.subList(0, selectedHintKeySequence.size())
                          .equals(selectedHintKeySequence);
    }

}
