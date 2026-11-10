package mousemaster;

import mousemaster.platform.UiAutomation.UiElement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RoleBoxColorTest {

    private Configuration parse(String... lines) {
        return ConfigurationParser.parse(List.of(lines),
                KeyboardLayout.keyboardLayout("00000409", null));
    }

    private static HintMeshStyle style(Configuration configuration, String modeName) {
        return configuration.modeMap().get(modeName).hintMesh().styleByFilter().map()
                           .values().iterator().next();
    }

    @Test
    void eachRoleHasItsOwnTagColor() {
        HintMeshStyle style = style(parse(
                "idle-mode.overlay.box-color=#111111",
                "idle-mode.overlay.click-box-color=#FFEB3B",
                "idle-mode.overlay.input-box-color=#A5D6A7",
                "idle-mode.overlay.widget-box-color=#FFB74D",
                "idle-mode.overlay.other-box-color=#BDBDBD"), Mode.IDLE_MODE_NAME);
        assertEquals(GradientColor.parse("#FFEB3B"), style.boxColor(UiElement.Role.CLICK));
        assertEquals(GradientColor.parse("#A5D6A7"), style.boxColor(UiElement.Role.INPUT));
        assertEquals(GradientColor.parse("#FFB74D"), style.boxColor(UiElement.Role.WIDGET));
        assertEquals(GradientColor.parse("#BDBDBD"), style.boxColor(UiElement.Role.OTHER));
        assertEquals(GradientColor.parse("#111111"), style.boxColor(null));
    }

    @Test
    void aControlTypeHasTheRoleOfWhatSelectingItDoes() {
        assertEquals(UiElement.Role.CLICK, UiElement.Control.BUTTON.role());
        assertEquals(UiElement.Role.CLICK, UiElement.Control.HYPERLINK.role());
        assertEquals(UiElement.Role.CLICK, UiElement.Control.MENU_ITEM.role());
        assertEquals(UiElement.Role.CLICK, UiElement.Control.CHECK_BOX.role());
        assertEquals(UiElement.Role.INPUT, UiElement.Control.EDIT.role());
        assertEquals(UiElement.Role.INPUT, UiElement.Control.COMBO_BOX.role());
        assertEquals(UiElement.Role.WIDGET, UiElement.Control.SCROLL_BAR.role());
        assertEquals(UiElement.Role.OTHER, UiElement.Control.DOCUMENT.role());
        assertEquals(UiElement.Control.CHECK_BOX, UiElement.Control.fromWindowsControlType(50002));
        assertEquals(UiElement.Role.INPUT, UiElement.Role.fromWindowsControlType(50004));
        assertEquals(UiElement.Role.OTHER, UiElement.Role.fromWindowsControlType(49999));
    }

    /** An image is only pointed at, unless the application says it reacts to a click. */
    @Test
    void aClickableControlOfATypeThatIsOnlyPointedAtIsClicked() {
        assertEquals(UiElement.Role.OTHER, UiElement.Role.of(UiElement.Control.IMAGE, false));
        assertEquals(UiElement.Role.CLICK, UiElement.Role.of(UiElement.Control.IMAGE, true));
        assertEquals(UiElement.Role.CLICK, UiElement.Role.of(null, true));
        assertEquals(UiElement.Role.OTHER, UiElement.Role.of(null, false));
        // A click in the middle of a container lands on whichever item is there.
        assertEquals(UiElement.Role.OTHER, UiElement.Role.of(UiElement.Control.TREE, true));
        assertEquals(UiElement.Role.OTHER, UiElement.Role.of(UiElement.Control.PANE, true));
        assertEquals(UiElement.Role.INPUT, UiElement.Role.of(UiElement.Control.EDIT, true));
        assertEquals(UiElement.Role.WIDGET,
                UiElement.Role.of(UiElement.Control.SCROLL_BAR, true));
    }

    @Test
    void oneControlCanHaveItsOwnTagColor() {
        HintMeshStyle style = style(parse(
                "idle-mode.overlay.click-box-color=#FFEB3B",
                "idle-mode.overlay.checkbox-box-color=#CE93D8",
                "idle-mode.overlay.button-box-color=#FFCC80"), Mode.IDLE_MODE_NAME);
        assertEquals(GradientColor.parse("#CE93D8"),
                style.boxColor(UiElement.Role.CLICK, UiElement.Control.CHECK_BOX));
        assertEquals(GradientColor.parse("#FFCC80"),
                style.boxColor(UiElement.Role.CLICK, UiElement.Control.BUTTON));
        assertEquals(GradientColor.parse("#FFEB3B"),
                style.boxColor(UiElement.Role.CLICK, UiElement.Control.LIST_ITEM));
        assertEquals(GradientColor.parse("#FFEB3B"), style.boxColor(UiElement.Role.CLICK, null));
    }

    @Test
    void aChildModeInheritsOneControlColor() {
        HintMeshStyle child = style(parse(
                "idle-mode.to.child-mode=+f",
                "idle-mode.overlay.checkbox-box-color=#CE93D8",
                "idle-mode.overlay.menu-item-box-color=#FFCC80",
                "child-mode=idle-mode",
                "child-mode.overlay.checkbox-box-color=#0000FF"), "child-mode");
        assertEquals(GradientColor.parse("#0000FF"),
                child.boxColor(UiElement.Role.CLICK, UiElement.Control.CHECK_BOX));
        assertEquals(GradientColor.parse("#FFCC80"),
                child.boxColor(UiElement.Role.CLICK, UiElement.Control.MENU_ITEM));
    }

    @Test
    void anUnsetRoleUsesTheTagColor() {
        HintMeshStyle style = style(parse("idle-mode.overlay.box-color=#111111"),
                Mode.IDLE_MODE_NAME);
        assertEquals(style.boxColor(), style.boxColor(UiElement.Role.CLICK));
        assertEquals(style.boxColor(), style.boxColor(null));
    }

    @Test
    void aChildModeInheritsRoleColorsAndCanReplaceOne() {
        Configuration configuration = parse(
                "idle-mode.to.child-mode=+f",
                "idle-mode.overlay.box-color=#111111",
                "idle-mode.overlay.click-box-color=#FFEB3B",
                "idle-mode.overlay.input-box-color=#A5D6A7",
                "idle-mode.overlay.widget-box-color=#FFB74D",
                "idle-mode.overlay.other-box-color=#BDBDBD",
                "child-mode=idle-mode",
                "child-mode.overlay.box-color=#4DD0E1",
                "child-mode.overlay.input-box-color=#00FF00");
        HintMeshStyle child = style(configuration, "child-mode");
        assertEquals(GradientColor.parse("#FFEB3B"), child.boxColor(UiElement.Role.CLICK));
        assertEquals(GradientColor.parse("#00FF00"), child.boxColor(UiElement.Role.INPUT));
        assertEquals(GradientColor.parse("#FFB74D"), child.boxColor(UiElement.Role.WIDGET));
        assertEquals(GradientColor.parse("#BDBDBD"), child.boxColor(UiElement.Role.OTHER));
        assertEquals(GradientColor.parse("#4DD0E1"), child.boxColor(null));
    }

    @Test
    void aFilterNamesARole() {
        Configuration configuration = parse(
                "idle-mode.filter-ui-hints.click=+g",
                "idle-mode.filter-ui-hints.widget=+b");
        assertEquals(java.util.Set.of(
                        List.of(new Command.FilterUiHints(UiElement.Role.CLICK)),
                        List.of(new Command.FilterUiHints(UiElement.Role.WIDGET))),
                java.util.Set.copyOf(configuration.modeMap().get(Mode.IDLE_MODE_NAME)
                                                  .comboMap().commandsByCombo().values()));
        assertThrows(IllegalArgumentException.class,
                () -> parse("idle-mode.filter-ui-hints.button=+g"));
    }
}
