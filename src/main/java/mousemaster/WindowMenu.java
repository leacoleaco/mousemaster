package mousemaster;

import java.util.List;

/**
 * The window list drawn while window-switch-mode is active.
 * Titles stay in Alt-Tab order: the active window first, then the one Alt-Tab would
 * land on, then older windows. The highlight starts on that second window when there
 * is one, so confirming jumps there the way a single Alt-Tab does.
 * e moves up the list and d moves down. Both wrap.
 * Each row is its tag, then the program icon, centered on two lines: the window title,
 * then the process file name. The first row is the window already in front: it shows
 * 当前 and takes no tag. The other rows of the first page are tagged, top to bottom,
 * and pressing a tag jumps to the window of its row.
 */
public final class WindowMenu {

    /** Left in the indicator text and replaced with {@link #html()} when the menu is drawn. */
    public static final String PLACEHOLDER = "[[window-menu]]";

    /** Shown in the tag column of the active window. It is not a key. */
    static final String ACTIVE_LABEL = "当前";

    /** The icon's size in the menu. The bitmap itself may be larger, so hidpi stays sharp. */
    static final int ICON_PX = 32;
    static final int VISIBLE_ROWS = 12;
    static final int MAX_TITLE_CHARS = 48;
    /** Wide enough for {@link #ACTIVE_LABEL}. Every tagged row uses it so the icons line up. */
    static final int TAG_COLUMN_PX = 40;

    private final List<WindowEntry> entries;
    /**
     * The tag of each row after the active window, which is always the first entry.
     * Shorter than that tail when the keys run out, or empty when the menu has no tags.
     */
    private final List<String> tags;
    /** True when the mode tags rows, so the active window can show {@link #ACTIVE_LABEL}. */
    private final boolean tagged;
    private final int selected;

    private WindowMenu(List<WindowEntry> entries, List<String> tags, boolean tagged, int selected) {
        this.entries = entries;
        this.tags = tags;
        this.tagged = tagged;
        this.selected = selected;
    }

    public static WindowMenu empty() {
        return new WindowMenu(List.of(), List.of(), false, 0);
    }

    public static WindowMenu of(List<WindowEntry> entries) {
        return of(entries, List.of());
    }

    /**
     * {@code tagKeys} tag the rows of the first page after the active window, in order.
     * The active window is the first entry and is labeled {@link #ACTIVE_LABEL} instead.
     */
    public static WindowMenu of(List<WindowEntry> entries, List<Key> tagKeys) {
        List<WindowEntry> copy = List.copyOf(entries);
        int selected = copy.size() > 1 ? 1 : 0;
        boolean tagged = !tagKeys.isEmpty() && !copy.isEmpty();
        return new WindowMenu(copy, jumpTags(copy.size(), tagKeys), tagged, selected);
    }

    /**
     * Tags for the rows after the active window. Only rows that fit on the first page
     * are tagged: the page also shows the active window, which takes no key.
     */
    private static List<String> jumpTags(int rowCount, List<Key> tagKeys) {
        if (tagKeys.isEmpty() || rowCount <= 1)
            return List.of();
        int onFirstPage = Math.min(rowCount - 1, VISIBLE_ROWS - 1);
        return MenuTags.labels(tagKeys, onFirstPage, onFirstPage);
    }

    /** Same highlight and tags, after icons have been filled in on the entries. */
    public WindowMenu withEntries(List<WindowEntry> entries) {
        List<WindowEntry> copy = List.copyOf(entries);
        if (copy.isEmpty())
            return new WindowMenu(copy, List.of(), false, 0);
        int slots = Math.max(0, copy.size() - 1);
        List<String> keptTags = tags.subList(0, Math.min(tags.size(), slots));
        int index = Math.min(Math.max(selected, 0), copy.size() - 1);
        return new WindowMenu(copy, keptTags, tagged, index);
    }

    public WindowMenu move(int delta) {
        if (entries.isEmpty() || delta == 0)
            return this;
        int next = Math.floorMod(selected + delta, entries.size());
        return new WindowMenu(entries, tags, tagged, next);
    }

    /** The highlight on the row at {@code index}. Unchanged when there is no such row. */
    public WindowMenu select(int index) {
        if (index < 0 || index >= entries.size() || index == selected)
            return this;
        return new WindowMenu(entries, tags, tagged, index);
    }

    public int selected() {
        return selected;
    }

    /** How many rows after the active window have a tag. */
    public int tagCount() {
        return tags.size();
    }

    /**
     * The list row of the tag at {@code tagIndex}. Tags start on the row after the
     * active window, so the first tag is row 1. -1 when {@code tagIndex} is not a tag.
     */
    public int rowOfTag(int tagIndex) {
        if (tagIndex < 0 || tagIndex >= tags.size())
            return -1;
        return tagIndex + 1;
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
            appendRow(html, entries.get(index), index, index == selected);
        if (end < entries.size())
            html.append("<tr><td class=more>下面还有 ")
                .append(entries.size() - end)
                .append(" 个</td></tr>");
        html.append("</table>");
        return html.toString();
    }

    private void appendRow(StringBuilder html, WindowEntry entry, int index, boolean current) {
        String bg = current ? " bgcolor=\"#7AA2F7\"" : "";
        html.append("<tr><td class=row").append(bg)
            .append("><table cellspacing=0 cellpadding=0><tr>");
        // Every row keeps the tag column once the menu tags rows, so the icons line up.
        // The first row is the active window: 当前 is only a label, and it takes no key.
        if (tagged) {
            String tag = index == 0 ? ACTIVE_LABEL
                    : (index - 1 < tags.size() ? tags.get(index - 1) : null);
            html.append("<td class=").append(current ? "tagon" : "tag").append(bg)
                .append(" rowspan=2 valign=\"middle\" width=\"").append(TAG_COLUMN_PX)
                .append("\" nowrap>")
                .append(tag == null ? "&nbsp;" : escape(tag))
                .append("</td>");
        }
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
