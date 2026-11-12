package mousemaster;

import java.util.List;

/**
 * The window list drawn while window-switch-mode is active.
 * Titles stay in Alt-Tab order: the active window first, then the one Alt-Tab would
 * land on, then older windows. The highlight starts on that second window when there
 * is one, so confirming jumps there the way a single Alt-Tab does.
 * e moves up the list and d moves down. Both wrap.
 * Each row is its tag, then the program icon, centered on two lines: the window title,
 * then the process file name. The rows of the first page are tagged, top to bottom, and
 * pressing a tag jumps to the window of its row.
 */
public final class WindowMenu {

    /** Left in the indicator text and replaced with {@link #html()} when the menu is drawn. */
    public static final String PLACEHOLDER = "[[window-menu]]";

    /** The icon's size in the menu. The bitmap itself may be larger, so hidpi stays sharp. */
    static final int ICON_PX = 32;
    static final int VISIBLE_ROWS = 12;
    static final int MAX_TITLE_CHARS = 48;

    private final List<WindowEntry> entries;
    /** The tag of each of the first rows. Shorter than the list when the keys run out. */
    private final List<String> tags;
    private final int selected;

    private WindowMenu(List<WindowEntry> entries, List<String> tags, int selected) {
        this.entries = entries;
        this.tags = tags;
        this.selected = selected;
    }

    public static WindowMenu empty() {
        return new WindowMenu(List.of(), List.of(), 0);
    }

    public static WindowMenu of(List<WindowEntry> entries) {
        return of(entries, List.of());
    }

    /** {@code tagKeys} tag the rows of the first page, in order. */
    public static WindowMenu of(List<WindowEntry> entries, List<Key> tagKeys) {
        List<WindowEntry> copy = List.copyOf(entries);
        int selected = copy.size() > 1 ? 1 : 0;
        return new WindowMenu(copy, MenuTags.labels(tagKeys, copy.size(), VISIBLE_ROWS),
                selected);
    }

    /** Same highlight and tags, after icons have been filled in on the entries. */
    public WindowMenu withEntries(List<WindowEntry> entries) {
        List<WindowEntry> copy = List.copyOf(entries);
        List<String> keptTags = tags.subList(0, Math.min(tags.size(), copy.size()));
        if (copy.isEmpty())
            return new WindowMenu(copy, keptTags, 0);
        int index = Math.min(Math.max(selected, 0), copy.size() - 1);
        return new WindowMenu(copy, keptTags, index);
    }

    public WindowMenu move(int delta) {
        if (entries.isEmpty() || delta == 0)
            return this;
        int next = Math.floorMod(selected + delta, entries.size());
        return new WindowMenu(entries, tags, next);
    }

    /** The highlight on the row at {@code index}. Unchanged when there is no such row. */
    public WindowMenu select(int index) {
        if (index < 0 || index >= entries.size() || index == selected)
            return this;
        return new WindowMenu(entries, tags, index);
    }

    public int selected() {
        return selected;
    }

    /** How many rows have a tag, counted from the top. */
    public int tagCount() {
        return tags.size();
    }

    /**
     * Whether the row at {@code index} is drawn. A list longer than a page is drawn
     * around the highlight, so the first rows scroll out of sight, and their tags with them.
     */
    public boolean shows(int index) {
        return index >= firstShown() && index < endShown();
    }

    private int firstShown() {
        if (entries.size() <= VISIBLE_ROWS)
            return 0;
        int end = Math.min(entries.size(),
                Math.max(0, selected - VISIBLE_ROWS / 2) + VISIBLE_ROWS);
        return end - VISIBLE_ROWS;
    }

    private int endShown() {
        return Math.min(entries.size(), firstShown() + VISIBLE_ROWS);
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
        html.append("<tr><td class=hint>");
        if (!tags.isEmpty())
            html.append("字母 直接跳转&nbsp;&nbsp;");
        html.append("e k 上&nbsp;&nbsp;d j 下&nbsp;&nbsp;g / 空格 / 回车 跳转&nbsp;&nbsp;Esc 返回</td></tr>");
        if (entries.isEmpty()) {
            html.append("<tr><td class=empty>没有可切换的窗口</td></tr></table>");
            return html.toString();
        }
        int start = firstShown();
        int end = endShown();
        if (start > 0)
            html.append("<tr><td class=more>上面还有 ").append(start).append(" 个</td></tr>");
        for (int index = start; index < end; index++)
            appendRow(html, entries.get(index), index < tags.size() ? tags.get(index) : null,
                    index == selected);
        if (end < entries.size())
            html.append("<tr><td class=more>下面还有 ")
                .append(entries.size() - end)
                .append(" 个</td></tr>");
        html.append("</table>");
        return html.toString();
    }

    private void appendRow(StringBuilder html, WindowEntry entry, String tag, boolean current) {
        String bg = current ? " bgcolor=\"#7AA2F7\"" : "";
        html.append("<tr><td class=row").append(bg)
            .append("><table cellspacing=0 cellpadding=0><tr>");
        // Every row keeps the tag column once any row has a tag, so the icons line up.
        if (!tags.isEmpty())
            html.append("<td class=").append(current ? "tagon" : "tag").append(bg)
                .append(" rowspan=2 valign=\"middle\" width=\"26\">")
                .append(tag == null ? "&nbsp;" : escape(tag))
                .append("</td>");
        html.append("<td class=icon rowspan=2 valign=\"middle\" width=\"40\">");
        if (entry.iconSource() != null)
            html.append("<img src=\"").append(entry.iconSource())
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

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

}
