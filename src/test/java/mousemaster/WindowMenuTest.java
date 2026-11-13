package mousemaster;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowMenuTest {

    @Test
    void highlightStartsOnThePreviousWindow() {
        WindowMenu menu = WindowMenu.of(List.of(row("当前"), row("上一个"), row("更早")));
        assertEquals(1, menu.selected());
        assertEquals(0, WindowMenu.of(List.of(row("只有一个"))).selected());
        assertTrue(WindowMenu.of(List.of()).isEmpty());
    }

    @Test
    void upAndDownWrap() {
        WindowMenu menu = WindowMenu.of(List.of(row("当前"), row("上一个"), row("更早")));
        assertEquals(0, menu.move(-1).selected());
        assertEquals(2, menu.move(-1).move(-1).selected());
        assertEquals(2, menu.move(1).selected());
        assertEquals(0, menu.move(1).move(1).selected());
    }

    @Test
    void htmlShowsTheIconTitleAndProcessOnTwoLines() {
        WindowMenu menu = WindowMenu.of(List.of(
                new WindowEntry("当前", "current.exe", null),
                new WindowEntry("a<b>&c", "x<y>&z.exe", "window-icon-1-0")));
        String html = menu.html();
        assertTrue(html.contains("bgcolor=\"#7AA2F7\""));
        assertTrue(html.contains("a&lt;b&gt;&amp;c"));
        assertTrue(html.contains("x&lt;y&gt;&amp;z.exe"));
        assertFalse(html.contains("a<b>"));
        assertTrue(html.contains("class=name>"));
        assertTrue(html.contains("class=nameon "));
        assertTrue(html.contains("class=process>"));
        assertTrue(html.contains("class=processon "));
        assertTrue(html.contains("rowspan=2"));
        assertTrue(html.contains("valign=\"middle\""));
        assertTrue(html.contains("src=\"window-icon-1-0\""));
        assertTrue(html.contains("width=\"32\""));
        assertTrue(html.contains("e k 上"));
        assertTrue(html.contains("Esc 返回"));
        assertEquals(1, menu.withEntries(List.of(
                new WindowEntry("当前", "current.exe", "window-icon-2-0"),
                new WindowEntry("a<b>&c", "x<y>&z.exe", "window-icon-2-1"))).selected());
    }

    @Test
    void longListShowsAWindowAroundTheHighlight() {
        List<WindowEntry> titles = IntStream.range(0, 20)
                                             .mapToObj(index -> row("窗口" + index))
                                             .toList();
        WindowMenu menu = WindowMenu.of(titles);
        for (int step = 0; step < 15; step++)
            menu = menu.move(1);
        String html = menu.html();
        assertTrue(html.contains("上面还有"));
        assertTrue(html.contains("窗口" + menu.selected()));
        assertFalse(html.contains(">窗口0<"));
    }

    @Test
    void rowsAreTaggedFromTheTop() {
        WindowMenu menu = WindowMenu.of(
                List.of(row("前面"), row("上一个"), row("更早"), row("最早")), keys("f", "c", "a"));
        assertEquals(3, menu.tagCount());
        String html = menu.html();
        assertTrue(html.contains("字母 直接跳转"));
        int tagF = html.indexOf(">F</td>");
        int current = html.indexOf(">当前</td>");
        int tagC = html.indexOf(">C</td>");
        int tagA = html.indexOf(">A</td>");
        assertTrue(tagF > 0 && tagF < current && current < html.indexOf("前面"));
        assertTrue(tagC > html.indexOf("前面") && tagC < html.indexOf("上一个"));
        assertTrue(tagA > html.indexOf("上一个") && tagA < html.indexOf("更早"));
        // The highlighted row draws its tag dark, and a row past the keys keeps the column.
        // 当前 follows the first letter and is not itself a tag.
        assertTrue(html.contains("class=tag rowspan=2 valign=\"middle\" width=\"26\">F</td>"));
        assertTrue(html.contains("class=here rowspan=2 valign=\"middle\" width=\"32\" nowrap>当前</td>"));
        assertTrue(html.contains("class=tagon bgcolor=\"#7AA2F7\" rowspan=2 valign=\"middle\" width=\"26\">C</td>"));
        assertTrue(html.contains("class=tag rowspan=2 valign=\"middle\" width=\"26\">&nbsp;</td>"));
        assertEquals(1, html.split(">当前</td>", -1).length - 1);

        assertEquals(2, menu.select(2).selected());
        assertEquals(0, menu.select(0).selected());
        assertEquals(1, menu.select(-1).selected());
        assertEquals(1, menu.select(4).selected());
        assertTrue(menu.select(0).html().contains(
                "class=hereon bgcolor=\"#7AA2F7\" rowspan=2 valign=\"middle\" width=\"32\" nowrap>当前</td>"));
        // Icons arriving later keep the tags and the highlight.
        WindowMenu withIcons = menu.select(2).withEntries(
                List.of(row("前面"), row("上一个"), row("更早"), row("最早")));
        assertEquals(3, withIcons.tagCount());
        assertEquals(2, withIcons.selected());
        assertTrue(withIcons.html().contains(">当前</td>"));

        WindowMenu alone = WindowMenu.of(List.of(row("只有一个")), keys("f"));
        assertEquals(1, alone.tagCount());
        assertTrue(alone.html().contains(">F</td>"));
        assertTrue(alone.html().contains(">当前</td>"));
    }

    @Test
    void onlyTheFirstPageIsTagged() {
        List<WindowEntry> titles = IntStream.range(0, 20)
                                             .mapToObj(index -> row("窗口" + index))
                                             .toList();
        List<Key> manyKeys = keys("f", "c", "a", "w", "v", "r", "x", "q", "z", "t", "b",
                "y", "u", "i", "o");
        assertEquals(WindowMenu.VISIBLE_ROWS, WindowMenu.of(titles, manyKeys).tagCount());
        assertEquals(3, WindowMenu.of(titles, keys("f", "c", "a")).tagCount());
        String firstPageHtml = WindowMenu.of(titles, manyKeys).html();
        assertTrue(firstPageHtml.contains(">F</td>"));
        assertTrue(firstPageHtml.contains(">当前</td>"));
        assertTrue(firstPageHtml.contains(">Y</td>"));
        assertFalse(firstPageHtml.contains(">U</td>"));
        WindowMenu firstPage = WindowMenu.of(titles, keys("f", "c", "a"));
        assertTrue(firstPage.shows(0));
        assertTrue(firstPage.shows(WindowMenu.VISIBLE_ROWS - 1));
        assertFalse(firstPage.shows(WindowMenu.VISIBLE_ROWS));
        // Scrolled past the first page, the tagged rows are gone but the column stays.
        WindowMenu scrolled = firstPage.select(2);
        for (int step = 0; step < 15; step++)
            scrolled = scrolled.move(1);
        assertFalse(scrolled.html().contains(">F</td>"));
        assertFalse(scrolled.shows(0));
        assertTrue(scrolled.shows(scrolled.selected()));
        assertTrue(scrolled.shows(19));
        assertFalse(scrolled.shows(20));
    }

    @Test
    void withoutTagKeysTheRowsHaveNoTagColumn() {
        String html = WindowMenu.of(List.of(row("当前"), row("上一个"))).html();
        assertFalse(html.contains("class=tag"));
        assertFalse(html.contains("字母"));
    }

    @Test
    void emptyMenuSaysSo() {
        assertTrue(WindowMenu.empty().html().contains("没有可切换的窗口"));
    }

    private static WindowEntry row(String title) {
        return new WindowEntry(title, "app.exe", null);
    }

    private static List<Key> keys(String... characters) {
        return Stream.of(characters).map(Key::ofCharacter).toList();
    }

}
