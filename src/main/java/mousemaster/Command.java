package mousemaster;

import mousemaster.platform.UiAutomation.UiElement;

import java.util.List;

public sealed interface Command {

    record SwitchMode(String modeName) implements Command {}

    /** Dim the screen and ring the cursor, without changing mode. */
    record ShowCursorSpotlight() implements Command {}

    record StartMoveUp() implements Command {}
    record StartMoveDown() implements Command {}
    record StartMoveLeft() implements Command {}
    record StartMoveRight() implements Command {}

    record StopMoveUp() implements Command {}
    record StopMoveDown() implements Command {}
    record StopMoveLeft() implements Command {}
    record StopMoveRight() implements Command {}

    record StartMoveTargetUp() implements Command {}
    record StartMoveTargetDown() implements Command {}
    record StartMoveTargetLeft() implements Command {}
    record StartMoveTargetRight() implements Command {}

    record StopMoveTargetUp() implements Command {}
    record StopMoveTargetDown() implements Command {}
    record StopMoveTargetLeft() implements Command {}
    record StopMoveTargetRight() implements Command {}

    record PressLeft() implements Command {}
    record PressMiddle() implements Command {}
    record PressRight() implements Command {}

    record ReleaseLeft() implements Command {}
    record ReleaseMiddle() implements Command {}
    record ReleaseRight() implements Command {}

    record ToggleLeft() implements Command {}
    record ToggleMiddle() implements Command {}
    record ToggleRight() implements Command {}

    record StartWheelUp() implements Command {}
    record StartWheelDown() implements Command {}
    record StartWheelLeft() implements Command {}
    record StartWheelRight() implements Command {}

    record StopWheelUp() implements Command {}
    record StopWheelDown() implements Command {}
    record StopWheelLeft() implements Command {}
    record StopWheelRight() implements Command {}

    record ShrinkOverlayUp() implements Command {}
    record ShrinkOverlayDown() implements Command {}
    record ShrinkOverlayLeft() implements Command {}
    record ShrinkOverlayRight() implements Command {}

    record MoveOverlayToAreaAbove() implements Command {}
    record MoveOverlayToAreaBelow() implements Command {}
    record MoveOverlayToAreaLeft() implements Command {}
    record MoveOverlayToAreaRight() implements Command {}

    record MoveToTarget() implements Command {}
    record MoveToLastSelectedHint() implements Command {}
    record MoveToOverlayCenter() implements Command {}
    record MoveToCellCenter() implements Command {}
    record MoveToCellTop() implements Command {}
    record MoveToCellBottom() implements Command {}
    record MoveToCellLeft() implements Command {}
    record MoveToCellRight() implements Command {}

    record MoveTargetToMouse() implements Command {}
    record MoveTargetToLastSelectedHint() implements Command {}
    record MoveTargetToOverlayCenter() implements Command {}
    record MoveTargetToCellCenter() implements Command {}
    record MoveTargetToCellTop() implements Command {}
    record MoveTargetToCellBottom() implements Command {}
    record MoveTargetToCellLeft() implements Command {}
    record MoveTargetToCellRight() implements Command {}

    record SavePosition(String positionHistoryName) implements Command {}
    record UnsavePosition(String positionHistoryName) implements Command {}
    record ToggleSavePosition(String positionHistoryName) implements Command {}
    record ClearPositionHistory(String positionHistoryName) implements Command {}
    record CycleNextPosition(String positionHistoryName) implements Command {}
    record CyclePreviousPosition(String positionHistoryName) implements Command {}

    record StartAnimation(String animationName) implements Command {}
    record StopAnimation(String animationName) implements Command {}

    record MacroCommand(Macro macro, AliasResolution aliasResolution) implements Command {
        @Override
        public String toString() {
            return "MacroCommand[" + macro.name() + "]";
        }
    }

    record BreakComboPreparation() implements Command {}

    record BreakMacro() implements Command {}

    record Exit() implements Command {}

    record Restart() implements Command {}

    record Noop(String name) implements Command {}

    record SelectHintKey() implements Command {}
    record DeselectHintKey() implements Command {}
    record DeselectAllHintKeys() implements Command {}

    /** Keep only the UI hints of this role. The full scan stays, so another filter can replace it. */
    record FilterUiHints(UiElement.Role role) implements Command {}

    record MutateMode(String modeName, ModePropertyPath propertyPath, Object newPropertyValue, Combo combo) implements Command {
        @Override
        public boolean equals(Object o) {
            // Ignore combo.
            if (this == o) return true;
            if (!(o instanceof MutateMode m)) return false;
            return modeName.equals(m.modeName) &&
                   propertyPath.equals(m.propertyPath) &&
                   newPropertyValue.equals(m.newPropertyValue);
        }

        @Override
        public int hashCode() {
            int h = modeName.hashCode();
            h = 31 * h + propertyPath.hashCode();
            h = 31 * h + newPropertyValue.hashCode();
            return h;
        }

        @Override
        public String toString() {
            return "MutateMode[" + propertyPath + "=" + newPropertyValue + "]";
        }
    }

    record SetVariable(String variableName) implements Command {}
    record UnsetVariable(String variableName) implements Command {}
    record ResetVariables() implements Command {}

    /**
     * Select text at the pointer and copy it.
     * A text pattern reads the word, the line, or the whole document when the control
     * has one. Otherwise WORD double-clicks, LINE triple-clicks, and ALL focuses the
     * target then selects everything.
     */
    record CopyText(Kind kind) implements Command {
        public enum Kind { WORD, LINE, ALL }
    }

    /** Move the highlight in the Alt-Tab-ordered window menu. Negative is up. */
    record SelectWindow(int delta) implements Command {}

    /**
     * Focus the highlighted window. On success, switch to {@code modeAfter}
     * (the shipped configuration centers the pointer on that window, then returns).
     */
    record ActivateSelectedWindow(String modeAfter) implements Command {}

    /**
     * Focus the window whose tag is the key just pressed, then switch to {@code modeAfter}.
     * The tags go to the rows of the first page, top to bottom, in this order.
     * A key that tags no row does nothing.
     */
    record ActivateWindowByTag(List<Key> tagKeys, String modeAfter) implements Command {}

    /** Move the highlight in the menu of the program in front. Negative is up. */
    record SelectMenuItem(int delta) implements Command {}

    /**
     * Send the keys of the menu item whose tag is the key just pressed, then switch to
     * {@code modeAfter}. A key that tags no item does nothing.
     */
    record RunMenuItemByTag(List<Key> tagKeys, String modeAfter) implements Command {}

    /**
     * Send the keys of the highlighted menu item to the program, then switch to
     * {@code modeAfter}. A program with no menu only switches.
     */
    record RunSelectedMenuItem(String modeAfter) implements Command {}

    /** What a row of the copy menu does. The rows are drawn in this order. */
    enum CopyItem {
        /** Read the text at the pointer and open the word picker on it. */
        TEXT("text", "复制一段文字"),
        WORD("word", "复制光标下单词"),
        ALL("all", "复制全部"),
        SCREENSHOT("screenshot", "截图"),
        LONG_SCREENSHOT("long-screenshot", "截长图");

        private final String propertyName;
        private final String label;

        CopyItem(String propertyName, String label) {
            this.propertyName = propertyName;
            this.label = label;
        }

        /** The name in the configuration, such as {@code long-screenshot}. */
        public String propertyName() {
            return propertyName;
        }

        public String label() {
            return label;
        }

        /** Null when no item bears that name. */
        public static CopyItem fromPropertyName(String propertyName) {
            for (CopyItem item : values())
                if (item.propertyName.equals(propertyName))
                    return item;
            return null;
        }
    }

    /** Move the highlight in the copy menu. Negative is up. */
    record SelectCopyItem(int delta) implements Command {}

    /**
     * Run that row of the copy menu, then switch to {@code modeAfter}. {@code tagKey}
     * is the key that runs the row, and the letter drawn in front of it.
     */
    record RunCopyItem(CopyItem item, Key tagKey, String modeAfter) implements Command {}

    /** Run the highlighted row of the copy menu, then switch to that row's mode. */
    record RunSelectedCopyItem() implements Command {}

    /**
     * The key just pressed goes to the copy tool that is open: a letter of a tag in the
     * word picker, the tag of a corner in a screenshot. In the picker the tags are made
     * of these keys, and in a screenshot the first two are the corners where the region
     * begins and ends.
     */
    record CopyToolKey(List<Key> keys) implements Command {}

    /** Take back the last thing typed into the copy tool that is open. */
    record CopyToolUndo() implements Command {}

    /**
     * Finish the step the open copy tool is at, then switch to {@code modeAfter}: copy
     * what the word picker marks, copy the region of a screenshot, fix the region of a
     * long screenshot, or copy the long screenshot. Nothing happens, and the mode stays,
     * while the tool is not ready for it.
     */
    record CopyToolConfirm(String modeAfter) implements Command {}

}
