package mousemaster;

import java.util.Set;

/**
 * Which top-level windows appear in the Alt-Tab list.
 * The Windows enumerator decides {@code representative} (the visible last popup of the
 * root owner). Everything else is a property of that window.
 */
public final class AltTabWindowRules {

    /** Desktop and taskbar. They are top-level and visible, and Alt-Tab never lists them. */
    public static final Set<String> SKIPPED_CLASSES = Set.of(
            "Progman", "WorkerW", "Shell_TrayWnd", "Shell_SecondaryTrayWnd");

    private AltTabWindowRules() {
    }

    public static boolean included(boolean visible, boolean cloaked, boolean sameProcess,
                                   boolean toolWindow, boolean appWindow,
                                   boolean representative, String className, String title) {
        if (!visible || cloaked || sameProcess || !representative)
            return false;
        if (className != null && SKIPPED_CLASSES.contains(className))
            return false;
        // A tool window is hidden from Alt-Tab unless the app asked to be listed.
        if (toolWindow && !appWindow)
            return false;
        return title != null && !title.isBlank();
    }

    public static String displayTitle(String title, boolean iconic) {
        String cleaned = title.replace('\r', ' ').replace('\n', ' ').strip()
                              .replaceAll(" +", " ");
        if (iconic)
            cleaned = cleaned + " · 最小化";
        return cleaned;
    }

}
