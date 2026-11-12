package mousemaster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The menu drawn while app-menu-mode is active: the items configured for the program
 * in front, one per row, each with its tag. The highlight starts on the first item.
 * e moves up and j moves down, and both wrap. Pressing a row's own key runs that
 * item. A program that has no menu gets a note saying so.
 */
public final class AppMenu {

    /** Left in the indicator text and replaced with {@link #html()} when the menu is drawn. */
    public static final String PLACEHOLDER = "[[app-menu]]";

    private final String title;
    private final List<String> labels;
    /**
     * The key of each row, or null when that row has none. Same length as {@link #labels}
     * once the menu has rows.
     */
    private final List<Key> tags;
    private final int selected;

    private AppMenu(String title, List<String> labels, List<Key> tags, int selected) {
        this.title = title;
        this.labels = labels;
        this.tags = tags;
        this.selected = selected;
    }

    /** No program has a menu here: {@code title} names the program that was in front. */
    public static AppMenu empty(String title) {
        return new AppMenu(title == null ? "" : title, List.of(), List.of(), 0);
    }

    /** The rows take keys from {@code tagKeys}, top to bottom, until either runs out. */
    public static AppMenu of(String title, List<String> labels, List<Key> tagKeys) {
        List<String> copy = List.copyOf(labels);
        List<String> assigned = MenuTags.labels(tagKeys, copy.size(), copy.size());
        List<Key> tags = new ArrayList<>();
        for (int index = 0; index < copy.size(); index++)
            tags.add(index < assigned.size() ? tagKeys.get(index) : null);
        return new AppMenu(title == null ? "" : title, copy, copyTags(tags), 0);
    }

    /** Each row keeps the key it was given. A null key is a row with no letter. */
    public static AppMenu withTags(String title, List<String> labels, List<Key> tags) {
        if (labels.size() != tags.size())
            throw new IllegalArgumentException(
                    "A menu row needs its own tag entry, even when the row has no key");
        return new AppMenu(title == null ? "" : title, List.copyOf(labels), copyTags(tags), 0);
    }

    /** {@link List#copyOf} rejects the null that stands for a row with no key. */
    private static List<Key> copyTags(List<Key> tags) {
        return Collections.unmodifiableList(new ArrayList<>(tags));
    }

    public AppMenu move(int delta) {
        if (labels.isEmpty() || delta == 0)
            return this;
        return new AppMenu(title, labels, tags,
                Math.floorMod(selected + delta, labels.size()));
    }

    /** The row this key runs, or -1 when no row has it. */
    public int rowOf(Key key) {
        if (key == null)
            return -1;
        return tags.indexOf(key);
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

    /** How many rows have a key. */
    public int tagCount() {
        int count = 0;
        for (Key tag : tags)
            if (tag != null)
                count++;
        return count;
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
        html.append("<tr><td class=hint>").append(hint()).append("</td></tr>");
        for (int index = 0; index < labels.size(); index++)
            appendRow(html, labels.get(index),
                    tags.get(index) == null ? null : tags.get(index).hintLabel(),
                    index == selected);
        html.append("</table>");
        return html.toString();
    }

    /**
     * d moves down and g confirms, unless the open menu uses that key for a row.
     * A row's key is left out of the hint, because pressing it runs the row.
     */
    private String hint() {
        StringBuilder hint = new StringBuilder();
        if (tagCount() > 0)
            hint.append("字母 直接执行&nbsp;&nbsp;");
        if (!tags.contains(Key.ofCharacter("e")))
            hint.append("e ");
        hint.append("k 上&nbsp;&nbsp;");
        if (!tags.contains(Key.ofCharacter("d")))
            hint.append("d ");
        hint.append("j 下&nbsp;&nbsp;");
        if (!tags.contains(Key.ofCharacter("g")))
            hint.append("g / ");
        hint.append("空格 / 回车 执行&nbsp;&nbsp;Esc 返回");
        return hint.toString();
    }

    private void appendRow(StringBuilder html, String label, String tag, boolean current) {
        String bg = current ? " bgcolor=\"#7AA2F7\"" : "";
        html.append("<tr><td class=row").append(bg)
            .append("><table cellspacing=0 cellpadding=0><tr>");
        // Every row keeps the tag column once any row has a tag, so the labels line up.
        if (tagCount() > 0)
            html.append("<td class=").append(current ? "tagon" : "tag").append(bg)
                .append(" width=\"26\">")
                .append(tag == null ? "&nbsp;" : WindowMenu.escape(tag))
                .append("</td>");
        html.append("<td class=").append(current ? "itemon" : "item").append(bg).append(">")
            .append(WindowMenu.escape(label))
            .append("</td></tr></table></td></tr>");
    }

}
