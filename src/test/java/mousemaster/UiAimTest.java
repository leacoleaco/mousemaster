package mousemaster;

import mousemaster.platform.UiAutomation.UiElement;
import mousemaster.platform.UiAutomation.UiElement.Control;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The places a Cursor menu item points at, taken from one window's controls:
 * the New Agent button, the terminal panel under its tab, source control, and the file tree.
 */
class UiAimTest {

    /** The Cursor window the controls below were read from. */
    private static final Rectangle WINDOW = new Rectangle(3802, -2462, 4383, 2385);

    private static UiElement element(String name, Control control, int x, int y, int width,
                                    int height) {
        return new UiElement(new Rectangle(x, y, width, height),
                control == null ? null : control.role(), control, name);
    }

    private static List<UiElement> cursor() {
        return List.of(
                element("Terminal", Control.MENU_ITEM, 4487, -2430, 149, 77),
                element("Terminal (Ctrl+`)", Control.TAB_ITEM, 4975, -641, 142, 50),
                element("Terminal actions", Control.TOOL_BAR, 5130, -641, 1640, 52),
                element("Terminal (Ctrl+`)", Control.GROUP, 4988, -652, 115, 72),
                element("Kill Terminal", Control.BUTTON, 6653, -639, 45, 47),
                element("Terminal 1, java", Control.EDIT, 4417, -196, 16, 38),
                element(null, Control.GROUP, 4372, -578, 2516, 421),
                element(null, Control.IMAGE, 4417, -578, 2426, 421),
                element("Source Control Management", Control.TREE, 3834, -2223, 538, 1271),
                element("Source Control History", Control.TREE, 3834, -700, 538, 542),
                element("Source Control Input", Control.TREE_ITEM, 3834, -2223, 538, 77),
                element("New Agent", Control.BUTTON, 7538, -2340, 54, 56),
                element("New Agent", Control.TEXT, 7754, -2241, 135, 36),
                element("点击cursor 的 New Agent 按钮", Control.TEXT, 6950, -1903, 459, 43),
                element("Files Explorer", Control.TREE, 3834, -2200, 538, 1500));
    }

    @Test
    void clickPressesTheNewAgentButton() {
        UiElement button = new UiAim(UiAim.Kind.CLICK, "New Agent").locate(WINDOW, cursor());
        assertEquals(new Rectangle(7538, -2340, 54, 56), button.rectangle());
        assertEquals(Control.BUTTON, button.control());
    }

    @Test
    void terminalIsThePanelUnderItsTab() {
        UiElement panel = new UiAim(UiAim.Kind.POINT, "Terminal").locate(WINDOW, cursor());
        assertEquals(new Rectangle(4372, -578, 2516, 421), panel.rectangle());
    }

    @Test
    void gitIsTheSourceControlTree() {
        UiElement tree = new UiAim(UiAim.Kind.POINT, "Source Control").locate(WINDOW, cursor());
        assertEquals(new Rectangle(3834, -2223, 538, 1271), tree.rectangle());
    }

    @Test
    void projectIsTheFileTree() {
        UiElement tree = new UiAim(UiAim.Kind.POINT, "Files Explorer").locate(WINDOW, cursor());
        assertEquals(new Rectangle(3834, -2200, 538, 1500), tree.rectangle());
    }

    @Test
    void anUnnamedFileTreeAtTheLeftIsTheExplorer() {
        List<UiElement> elements = List.of(
                element("Source Control Management", Control.TREE, 3834, -2223, 538, 1271),
                element("mousemaster", Control.TREE, 3834, -2200, 538, 1600),
                // Larger than the file tree, and in the editor, so the left edge is what excludes it.
                element("outline", Control.TREE, 5000, -1800, 900, 1800));
        UiElement tree = new UiAim(UiAim.Kind.POINT, "Files Explorer").locate(WINDOW, elements);
        assertEquals("mousemaster", tree.name());
    }

    @Test
    void findClicksFindInTheEditMenu() {
        UiAim aim = new UiAim(UiAim.Kind.MENU, "Edit|编辑", "Find|查找");
        List<UiElement> bar = List.of(
                element("File", Control.MENU_ITEM, 3913, -2430, 81, 77),
                element("Edit", Control.MENU_ITEM, 3992, -2430, 88, 77),
                element("Selection", Control.MENU_ITEM, 4077, -2430, 155, 77));
        assertEquals(new Rectangle(3992, -2430, 88, 77), aim.locate(WINDOW, bar).rectangle());
        List<UiElement> open = List.of(
                element("Edit", Control.MENU_ITEM, 3992, -2430, 88, 77),
                element("Find Ctrl+F", Control.MENU_ITEM, 4001, -2016, 689, 59),
                element("Find", Control.GROUP, 4001, -2002, 279, 32),
                element("Find in Files Ctrl+Shift+F", Control.MENU_ITEM, 4001, -1874, 689, 59),
                element("查找下一个", Control.MENU_ITEM, 4001, -1600, 689, 59));
        UiElement find = aim.locateItem(WINDOW, open);
        assertEquals("Find Ctrl+F", find.name());
        assertEquals(Control.MENU_ITEM, find.control());
    }

    @Test
    void findUsesTheChineseMenuNames() {
        UiAim aim = new UiAim(UiAim.Kind.MENU, "Edit|编辑", "Find|查找");
        List<UiElement> bar = List.of(
                element("文件", Control.MENU_ITEM, 3913, -2430, 81, 77),
                element("编辑", Control.MENU_ITEM, 3992, -2430, 88, 77));
        assertEquals("编辑", aim.locate(WINDOW, bar).name());
        List<UiElement> open = List.of(
                element("编辑", Control.MENU_ITEM, 3992, -2430, 88, 77),
                element("查找 Ctrl+F", Control.MENU_ITEM, 4001, -2016, 689, 59),
                element("在文件中查找 Ctrl+Shift+F", Control.MENU_ITEM, 4001, -1874, 689, 59));
        assertEquals("查找 Ctrl+F", aim.locateItem(WINDOW, open).name());
        assertNull(aim.locateItem(WINDOW, bar));
    }

    @Test
    void aMissingControlIsNotInvented() {
        assertNull(new UiAim(UiAim.Kind.CLICK, "New Agent").locate(WINDOW, List.of()));
        assertNull(new UiAim(UiAim.Kind.POINT, "Terminal")
                .locate(WINDOW, List.of(element("Run", Control.MENU_ITEM, 4401, -2430, 88, 77))));
    }

}
