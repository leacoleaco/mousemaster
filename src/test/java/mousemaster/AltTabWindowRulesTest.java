package mousemaster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AltTabWindowRulesTest {

    @Test
    void anOrdinaryWindowIsListed() {
        assertTrue(included(true, false, false, false, false, true, "Chrome_WidgetWin_1", "文档"));
    }

    @Test
    void hiddenCloakedOwnedAndForeignWindowsAreSkipped() {
        assertFalse(included(false, false, false, false, false, true, "Notepad", "记事本"));
        assertFalse(included(true, true, false, false, false, true, "Notepad", "记事本"));
        assertFalse(included(true, false, true, false, false, true, "Notepad", "记事本"));
        assertFalse(included(true, false, false, false, false, false, "Notepad", "记事本"));
        assertFalse(included(true, false, false, true, false, true, "tool", "工具"));
        assertFalse(included(true, false, false, false, false, true, "Progman", "Program Manager"));
        assertFalse(included(true, false, false, false, false, true, "Shell_TrayWnd", "任务栏"));
        assertFalse(included(true, false, false, false, false, true, "Notepad", "  "));
    }

    @Test
    void anAppWindowToolWindowIsListed() {
        assertTrue(included(true, false, false, true, true, true, "ConsoleWindowClass", "终端"));
    }

    @Test
    void aMinimizedWindowKeepsItsNameAndIsMarked() {
        assertEquals("记事本 · 最小化", AltTabWindowRules.displayTitle(" 记事本\n", true));
        assertEquals("记事本", AltTabWindowRules.displayTitle("记事本", false));
    }

    private static boolean included(boolean visible, boolean cloaked, boolean sameProcess,
                                    boolean toolWindow, boolean appWindow,
                                    boolean representative, String className, String title) {
        return AltTabWindowRules.included(visible, cloaked, sameProcess, toolWindow, appWindow,
                representative, className, title);
    }

}
