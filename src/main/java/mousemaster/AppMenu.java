package mousemaster;

import java.util.List;

/**
 * The menu drawn while app-menu-mode is active: the items configured for the program
 * in front, one per row, each with its tag. The highlight starts on the first item.
 * e moves up and d moves down, and both wrap. Pressing a tag runs the item of its
 * row. A program that has no menu gets a note saying so.
 */
public final class AppMenu {

    /** Left in the indicator text and replaced with {@link #html()} when the menu is drawn. */
    public static final String PLACEHOLDER = "[[app-menu]]";

    private final String title;
    private final List<String> labels;
    /** The tag of each of the first rows. Shorter than the list when the keys run out. */
    private final List<String> tags;
    private final int selected;

    private AppMenu(String title, List<String> labels, List<String> tags, int selected) {
        this.title = title;
        this.labels = labels;
        this.tags = tags;
        this.selected = selected;
    }

    /** No program has a menu here: {@code title} names the program that was in front. */
    public static AppMenu empty(String title) {
        return new AppMenu(title == null ? "" : title, List.of(), List.of(), 0);
    }

    public static AppMenu of(String title, List<String> labels, List<Key> tagKeys) {
        List<String> copy = List.copyOf(labels);
        return new AppMenu(title == null ? "" : title, copy,
                MenuTags.labels(tagKeys, copy.size(), copy.size()), 0);
    }

    public AppMenu move(int delta) {
        if (labels.isEmpty() || delta == 0)
            return this;
        return new AppMenu(title, labels, tags, Math.floorMod(selected + delta, labels.size()));
    }

    /** The highlight on the row at {@code index}. Unchanged when there is no such row. */
    public AppMenu select(int index) {
        if (index < 0 || index >= labels.size() || index == selected)
            return this;
        return new AppMenu(title, labels, tags, index);
    }

    public int selected() {
        return selected;
    }

    /** How many rows have a tag, counted from the top. */
    public int tagCount() {
        return tags.size();
    }

    public boolean isEmpty() {
        return labels.isEmpty();
    }

    public String html() {
        StringBuilder html = new StringBuilder();
        html.append("<table cellspacing=0 cellpadding=0>");
        if (labels.isEmpty()) {
            html.append("<tr><td class=title>菜单</td></tr>");
            html.append("<tr><td class=hint>Esc 返回</td></tr>");
            html.append("<tr><td class=empty>");
            if (title.isBlank())
                html.append("当前程序还没有菜单");
            else
                html.append(WindowMenu.escape(title)).append(" 还没有菜单");
            html.append("</td></tr></table>");
            return html.toString();
        }
        html.append("<tr><td class=title>").append(WindowMenu.escape(title)).append("</td></tr>");
        html.append("<tr><td class=hint>");
        if (!tags.isEmpty())
            html.append("字母 直接执行&nbsp;&nbsp;");
        html.append("e k 上&nbsp;&nbsp;d j 下&nbsp;&nbsp;g / 空格 / 回车 执行&nbsp;&nbsp;Esc 返回</td></tr>");
        for (int index = 0; index < labels.size(); index++)
            appendRow(html, labels.get(index), index < tags.size() ? tags.get(index) : null,
                    index == selected);
        html.append("</table>");
        return html.toString();
    }

    private void appendRow(StringBuilder html, String label, String tag, boolean current) {
        String bg = current ? " bgcolor=\"#7AA2F7\"" : "";
        html.append("<tr><td class=row").append(bg)
            .append("><table cellspacing=0 cellpadding=0><tr>");
        // Every row keeps the tag column once any row has a tag, so the labels line up.
        if (!tags.isEmpty())
            html.append("<td class=").append(current ? "tagon" : "tag").append(bg)
                .append(" width=\"26\">")
                .append(tag == null ? "&nbsp;" : WindowMenu.escape(tag))
                .append("</td>");
        html.append("<td class=").append(current ? "itemon" : "item").append(bg).append(">")
            .append(WindowMenu.escape(label))
            .append("</td></tr></table></td></tr>");
    }

}
