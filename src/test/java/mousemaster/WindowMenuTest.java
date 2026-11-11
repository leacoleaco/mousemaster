package mousemaster;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

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
    void emptyMenuSaysSo() {
        assertTrue(WindowMenu.empty().html().contains("没有可切换的窗口"));
    }

    private static WindowEntry row(String title) {
        return new WindowEntry(title, "app.exe", null);
    }

}
