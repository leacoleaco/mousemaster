package mousemaster;

import java.util.List;

/**
 * The window list drawn while window-switch-mode is active.
 * Titles stay in Alt-Tab order: the active window first, then the one Alt-Tab would
 * land on, then older windows. The highlight starts on that second window when there
 * is one, so confirming jumps there the way a single Alt-Tab does.
 * e moves up the list and d moves down. Both wrap.
 * Each row is the program icon, centered on two lines: the window title, then the
 * process file name.
 */
public final class WindowMenu {

    /** Left in the indicator text and replaced with {@link #html()} when the menu is drawn. */
    public static final String PLACEHOLDER = "[[window-menu]]";

    /** The icon's size in the menu. The bitmap itself may be larger, so hidpi stays sharp. */
    static final int ICON_PX = 32;
    static final int VISIBLE_ROWS = 12;
    static final int MAX_TITLE_CHARS = 48;

    private final List<WindowEntry> entries;
    private final int selected;

    private WindowMenu(List<WindowEntry> entries, int selected) {
        this.entries = entries;
        this.selected = selected;
    }

    public static WindowMenu empty() {
        return new WindowMenu(List.of(), 0);
    }

    public static WindowMenu of(List<WindowEntry> entries) {
        List<WindowEntry> copy = List.copyOf(entries);
        int selected = copy.size() > 1 ? 1 : 0;
        return new WindowMenu(copy, selected);
    }

    /** Same highlight, after icons have been filled in on the entries. */
    public WindowMenu withEntries(List<WindowEntry> entries) {
        List<WindowEntry> copy = List.copyOf(entries);
        if (copy.isEmpty())
            return new WindowMenu(copy, 0);
        int index = Math.min(Math.max(selected, 0), copy.size() - 1);
        return new WindowMenu(copy, index);
    }

    public WindowMenu move(int delta) {
        if (entries.isEmpty() || delta == 0)
            return this;
        int next = Math.floorMod(selected + delta, entries.size());
        return new WindowMenu(entries, next);
    }

    public int selected() {
        return selected;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public String html() {
        StringBuilder html = new StringBuilder();
        html.append("<table cellspacing=0 cellpadding=0>");
        html.append("<tr><td class=title>窗口</td></tr>");
        html.append("<tr><td class=hint>e k 上&nbsp;&nbsp;d j 下&nbsp;&nbsp;g / 空格 / 回车 跳转&nbsp;&nbsp;Esc 返回</td></tr>");
        if (entries.isEmpty()) {
            html.append("<tr><td class=empty>没有可切换的窗口</td></tr></table>");
            return html.toString();
        }
        int start = 0;
        int end = entries.size();
        if (entries.size() > VISIBLE_ROWS) {
            start = Math.max(0, selected - VISIBLE_ROWS / 2);
            end = Math.min(entries.size(), start + VISIBLE_ROWS);
            start = Math.max(0, end - VISIBLE_ROWS);
        }
        if (start > 0)
            html.append("<tr><td class=more>上面还有 ").append(start).append(" 个</td></tr>");
        for (int index = start; index < end; index++)
            appendRow(html, entries.get(index), index == selected);
        if (end < entries.size())
            html.append("<tr><td class=more>下面还有 ")
                .append(entries.size() - end)
                .append(" 个</td></tr>");
        html.append("</table>");
        return html.toString();
    }

    private static void appendRow(StringBuilder html, WindowEntry entry, boolean current) {
        String bg = current ? " bgcolor=\"#7AA2F7\"" : "";
        html.append("<tr><td class=row").append(bg)
            .append("><table cellspacing=0 cellpadding=0><tr>");
        html.append("<td class=icon rowspan=2 valign=\"middle\" width=\"40\">");
        if (entry.iconName() != null)
            html.append("<img src=\"").append(entry.iconName())
                .append("\" width=\"").append(ICON_PX).append("\" height=\"").append(ICON_PX)
                .append("\">");
        else
            html.append("&nbsp;");
        html.append("</td><td class=").append(current ? "nameon" : "name").append(bg).append(">");
        html.append(escape(shorten(entry.title())));
        html.append("</td></tr><tr><td class=")
            .append(current ? "processon" : "process").append(bg).append(">");
        if (entry.processName().isBlank())
            html.append("&nbsp;");
        else
            html.append(escape(shorten(entry.processName())));
        html.append("</td></tr></table></td></tr>");
    }

    private static String shorten(String title) {
        int count = title.codePointCount(0, title.length());
        if (count <= MAX_TITLE_CHARS)
            return title;
        return title.substring(0, title.offsetByCodePoints(0, MAX_TITLE_CHARS)) + "…";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

}
