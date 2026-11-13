package mousemaster;

import mousemaster.Command.CopyItem;
import mousemaster.platform.ScreenTools;
import mousemaster.platform.UiAutomation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The copy menu and its tools, with the screen, the clipboard and the text of the other
 * program stood in for. The modes are the ones of the shipped configuration.
 */
class CopyToolsTest {

    private static final Rectangle SCREEN = new Rectangle(100, 50, 1000, 700);

    private ModeMap modeMap;
    private FakeScreenTools screenTools;
    private FakePointer pointer;
    private FakeOutput output;
    private String textAtPointer;
    private CopyTools copyTools;

    @BeforeEach
    void load() throws Exception {
        Configuration configuration = ConfigurationParser.parse(
                PropertiesReader.readPropertiesFile(Files.newBufferedReader(
                        Path.of("configuration/mousemaster.properties"))),
                KeyboardLayout.keyboardLayout("00000409", null));
        modeMap = configuration.modeMap();
        screenTools = new FakeScreenTools();
        pointer = new FakePointer();
        output = new FakeOutput();
        UiAutomation uiAutomation = new UiAutomation() {
            @Override
            public Future<List<UiElement>> startFindActiveWindowUiElements() {
                return CompletableFuture.completedFuture(List.of());
            }

            @Override
            public Future<List<UiElement>> startFindUiElementsInArea(Rectangle area) {
                return CompletableFuture.completedFuture(List.of());
            }

            @Override
            public Future<String> startReadText() {
                return CompletableFuture.completedFuture(textAtPointer);
            }
        };
        copyTools = new CopyTools(pointer,
                new ScreenManager(() -> Set.of(new Screen(SCREEN, 96, 1))), screenTools,
                uiAutomation, output);
        enter("normal-mode");
    }

    @AfterEach
    void shutdown() {
        copyTools.shutdown();
    }

    private void enter(String modeName) {
        Mode mode = modeMap.get(modeName);
        assertNotNull(mode, modeName);
        copyTools.modeChanged(mode);
    }

    /** Runs the tools the way the main loop does, until the condition holds. */
    private void pump(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "Timed out waiting for " + what);
            copyTools.update(0.02);
            Thread.sleep(2);
        }
    }

    private void pumpFor(long millis) throws InterruptedException {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            copyTools.update(0.02);
            Thread.sleep(2);
        }
    }

    private static Key key(String letter) {
        return Key.ofName(letter);
    }

    @Test
    void theMenuListsTheFiveRowsInOrderWithTheirLetters() {
        enter("copy-menu-mode");
        String html = copyTools.menuHtml();
        int text = html.indexOf("复制一段文字");
        int word = html.indexOf("复制光标下单词");
        int all = html.indexOf("复制全部");
        int screenshot = html.indexOf("截图");
        int longScreenshot = html.indexOf("截长图");
        assertTrue(0 < text && text < word && word < all && all < screenshot &&
                   screenshot < longScreenshot, html);
        for (String letter : List.of("y", "w", "a", "i", "l"))
            assertTrue(copyTools.hasMenuTag(key(letter)), letter);
        for (String letter : List.of("e", "d", "k", "j", "g"))
            assertFalse(copyTools.hasMenuTag(key(letter)), letter);

        // The highlight starts on the first row, and wraps.
        assertEquals(CopyItem.TEXT, copyTools.selectedMenuItem().item());
        assertEquals("text-pick-mode", copyTools.selectedMenuItem().modeAfter());
        copyTools.moveMenu(1);
        assertEquals(CopyItem.WORD, copyTools.selectedMenuItem().item());
        assertEquals("copy-menu-done-mode", copyTools.selectedMenuItem().modeAfter());
        copyTools.moveMenu(-2);
        assertEquals(CopyItem.LONG_SCREENSHOT, copyTools.selectedMenuItem().item());
        assertEquals("long-screenshot-area-mode", copyTools.selectedMenuItem().modeAfter());
        copyTools.moveMenu(-1);
        assertEquals(CopyItem.SCREENSHOT, copyTools.selectedMenuItem().item());
        assertEquals("screenshot-mode", copyTools.selectedMenuItem().modeAfter());

        // Opened again, the highlight is back on the first row.
        enter("normal-mode");
        enter("copy-menu-mode");
        assertEquals(CopyItem.TEXT, copyTools.selectedMenuItem().item());
    }

    @Test
    void theWordAndEverythingAreCopiedOnceTheMenuIsGone() throws Exception {
        enter("copy-menu-mode");
        copyTools.run(CopyItem.WORD);
        enter("copy-menu-done-mode");
        assertTrue(output.copies.isEmpty());
        assertFalse(copyTools.active());
        pump(() -> !output.copies.isEmpty(), "the word to be copied");
        assertEquals(List.of(Command.CopyText.Kind.WORD), output.copies);

        enter("normal-mode");
        enter("copy-menu-mode");
        copyTools.run(CopyItem.ALL);
        enter("copy-menu-done-mode");
        pump(() -> output.copies.size() == 2, "everything to be copied");
        assertEquals(Command.CopyText.Kind.ALL, output.copies.get(1));
    }

    @Test
    void thePickerCopiesTheWordsBetweenTwoTags() throws Exception {
        textAtPointer = "今天是11月3日，123";
        enter("copy-menu-mode");
        copyTools.run(CopyItem.TEXT);
        enter("text-pick-mode");
        assertTrue(copyTools.active());
        // Not ready: space does nothing yet, and the panel says it is reading.
        assertFalse(copyTools.confirm());
        assertTrue(copyTools.textPickerHtml().contains("正在读取"));
        pump(() -> copyTools.textPickerHtml().contains("今天"), "the picker");
        String html = copyTools.textPickerHtml();
        assertTrue(html.contains("11月3日"), html);
        assertFalse(html.contains("pieceon"), html);

        // The tags are a s d f g: d is 11月3日 and g is 123.
        copyTools.key(key("d"));
        assertEquals(1, count(copyTools.textPickerHtml(), "class=pieceon"));
        copyTools.key(key("g"));
        assertEquals(3, count(copyTools.textPickerHtml(), "class=pieceon"));
        copyTools.undo();
        assertEquals(1, count(copyTools.textPickerHtml(), "class=pieceon"));
        copyTools.key(key("g"));

        assertTrue(copyTools.confirm());
        assertFalse(copyTools.active());
        assertEquals("", copyTools.textPickerHtml());
        enter("copy-tool-done-mode");
        pump(() -> output.copied == 1, "the copy");
        assertEquals("11月3日，123", screenTools.clipboard);
        assertTrue(output.clicks == 0);
    }

    @Test
    void escLeavesThePickerAndCopiesNothing() throws Exception {
        textAtPointer = "one two three";
        screenTools.clipboard = "before";
        enter("copy-menu-mode");
        copyTools.run(CopyItem.TEXT);
        enter("text-pick-mode");
        pump(() -> copyTools.textPickerHtml().contains("two"), "the picker");
        copyTools.key(key("s"));
        // Esc switches to activation mode, which has no tool.
        enter("normal-mode");
        assertFalse(copyTools.active());
        pumpFor(100);
        assertEquals("before", screenTools.clipboard);
        assertEquals(0, output.copied);
    }

    @Test
    void textThatCannotBeReadIsSelectedWithClicksAndTakenFromTheClipboard() throws Exception {
        textAtPointer = null;
        screenTools.clipboard = "before";
        enter("copy-menu-mode");
        copyTools.run(CopyItem.TEXT);
        enter("text-pick-mode");
        pump(() -> output.clicks == 1, "the clicks");
        assertTrue(copyTools.textPickerHtml().contains("正在读取"));
        // The other program answers the copy chord.
        screenTools.setClipboardFromOutside("hello big world");
        pump(() -> copyTools.textPickerHtml().contains("hello"), "the picker");
        copyTools.key(key("s"));
        copyTools.key(key("d"));
        assertTrue(copyTools.confirm());
        enter("copy-tool-done-mode");
        pump(() -> output.copied == 1, "the copy");
        assertEquals("big world", screenTools.clipboard);
    }

    @Test
    void escAfterTheClicksGivesTheClipboardItsTextBack() throws Exception {
        textAtPointer = "  ";
        screenTools.clipboard = "before";
        enter("copy-menu-mode");
        copyTools.run(CopyItem.TEXT);
        enter("text-pick-mode");
        pump(() -> output.clicks == 1, "the clicks");
        screenTools.setClipboardFromOutside("what the program copied");
        pump(() -> copyTools.textPickerHtml().contains("program"), "the picker");
        enter("normal-mode");
        pump(() -> "before".equals(screenTools.clipboard), "the clipboard to be put back");
        assertEquals(0, output.copied);
    }

    @Test
    void whenNothingIsCopiedThePanelSaysSoAndSpaceLeaves() throws Exception {
        textAtPointer = null;
        enter("copy-menu-mode");
        copyTools.run(CopyItem.TEXT);
        enter("text-pick-mode");
        pump(() -> output.clicks == 1, "the clicks");
        // The clipboard never changes.
        pump(() -> copyTools.textPickerHtml().contains("没有读到文字"), "the empty panel");
        assertTrue(copyTools.confirm());
        assertFalse(copyTools.active());
        pumpFor(50);
        assertEquals(0, output.copied);
        assertNull(screenTools.clipboard);
    }

    @Test
    void theScreenshotCopiesTheRegionBetweenTheTwoCorners() throws Exception {
        pointer.x = 300;
        pointer.y = 200;
        enter("copy-menu-mode");
        copyTools.run(CopyItem.SCREENSHOT);
        enter("screenshot-mode");
        // Not ready before the screen was read.
        assertFalse(copyTools.confirm());
        pump(() -> screenTools.shown(false) != null, "the frozen screen");
        FakeSurface surface = screenTools.shown(false);
        assertEquals(SCREEN, surface.rectangle);
        assertEquals(List.of(SCREEN), screenTools.captures);
        // A move the key that ran the row started (l moves right) was stopped.
        assertEquals(1, pointer.halts);
        // No corner is chosen: moving the pointer moves nothing.
        pointer.x = 320;
        pointer.y = 220;
        pumpFor(60);
        int shows = surface.shows;

        // e is the corner where the region begins. The pointer goes there.
        copyTools.key(key("e"));
        assertEquals(SCREEN.x(), pointer.x);
        assertEquals(SCREEN.y(), pointer.y);
        // h j k l move the pointer, which is hidden, and the corner follows it.
        pointer.x = SCREEN.x() + 30;
        pointer.y = SCREEN.y() + 20;
        pump(() -> surface.shows > shows, "the corner to follow");
        // f is the corner where it ends: the pixel under the pointer is the last one.
        copyTools.key(key("f"));
        assertEquals(SCREEN.x() + SCREEN.width() - 1, pointer.x);
        assertEquals(SCREEN.y() + SCREEN.height() - 1, pointer.y);
        pointer.x = SCREEN.x() + 129;
        pointer.y = SCREEN.y() + 99;
        int showsBefore = surface.shows;
        pump(() -> surface.shows > showsBefore, "the corner to follow");
        // Only what changed is sent to the window.
        assertTrue(surface.lastDirty != null && !surface.lastDirty.equals(SCREEN));

        assertTrue(copyTools.confirm());
        assertFalse(copyTools.active());
        assertTrue(surface.closed);
        // The pointer is back where it was when the menu opened.
        assertEquals(300, pointer.x);
        assertEquals(200, pointer.y);
        enter("copy-tool-done-mode");
        pump(() -> output.copied == 1, "the copy");
        assertEquals(100, screenTools.imageWidth);
        assertEquals(80, screenTools.imageHeight);
        for (int y = 0; y < 80; y += 7)
            for (int x = 0; x < 100; x += 9)
                assertEquals(FakeScreenTools.pixel(SCREEN.x() + 30 + x, SCREEN.y() + 20 + y, 0),
                        screenTools.image[y * 100 + x]);
    }

    @Test
    void escLeavesTheScreenshotAndPutsThePointerBack() throws Exception {
        pointer.x = 300;
        pointer.y = 200;
        enter("copy-menu-mode");
        copyTools.run(CopyItem.SCREENSHOT);
        enter("screenshot-mode");
        pump(() -> screenTools.shown(false) != null, "the frozen screen");
        FakeSurface surface = screenTools.shown(false);
        copyTools.key(key("f"));
        enter("normal-mode");
        assertFalse(copyTools.active());
        assertTrue(surface.closed);
        assertEquals(300, pointer.x);
        assertEquals(200, pointer.y);
        pumpFor(60);
        assertEquals(0, output.copied);
        assertNull(screenTools.image);
    }

    @Test
    void aScreenThatCannotBeReadLeavesTheTool() throws Exception {
        screenTools.captureFails = true;
        enter("copy-menu-mode");
        copyTools.run(CopyItem.SCREENSHOT);
        enter("screenshot-mode");
        pump(() -> output.left == 1, "the tool to leave");
        assertFalse(copyTools.active());
        assertTrue(screenTools.surfaces.isEmpty());
    }

    @Test
    void theLongScreenshotJoinsWhatTheRegionShowsWhileItScrolls() throws Exception {
        pointer.x = 300;
        pointer.y = 200;
        enter("copy-menu-mode");
        copyTools.run(CopyItem.LONG_SCREENSHOT);
        enter("long-screenshot-area-mode");
        pump(() -> screenTools.shown(false) != null, "the frozen screen");
        FakeSurface selection = screenTools.shown(false);
        copyTools.key(key("e"));
        pointer.x = SCREEN.x() + 600;
        pointer.y = SCREEN.y() + 40;
        pumpFor(60);
        copyTools.key(key("f"));
        pointer.x = SCREEN.x() + 799;
        pointer.y = SCREEN.y() + 239;
        pumpFor(60);
        Rectangle region = new Rectangle(SCREEN.x() + 600, SCREEN.y() + 40, 200, 200);

        // Space fixes the region. Nothing is copied yet.
        screenTools.scrolling = true;
        assertTrue(copyTools.confirm());
        assertTrue(copyTools.active());
        enter("long-screenshot-mode");
        pump(() -> screenTools.shown(true) != null, "the outline");
        assertTrue(selection.closed);
        // The pointer is in the middle of the region, where the wheel scrolls it.
        assertEquals(region.x() + 100, pointer.x);
        assertEquals(region.y() + 100, pointer.y);
        // The corners no longer move.
        copyTools.key(key("e"));
        assertEquals(region.x() + 100, pointer.x);

        // The outline is around the region and the preview is away from it.
        pump(() -> screenTools.surfaces.stream()
                                       .filter(surface -> surface.hiddenFromCapture &&
                                                          surface.rectangle != null)
                                       .count() == 2, "the preview");
        List<FakeSurface> hidden = screenTools.surfaces.stream()
                                                       .filter(s -> s.hiddenFromCapture)
                                                       .toList();
        assertTrue(hidden.get(0).rectangle.contains(region));
        assertTrue(hidden.get(1).rectangle.intersection(region).isEmpty());
        assertTrue(SCREEN.contains(hidden.get(1).rectangle));
        pump(() -> screenTools.scrolled >= 160, "the region to scroll");
        assertEquals(0, output.copied);

        // Space copies the long picture.
        assertTrue(copyTools.confirm());
        assertFalse(copyTools.active());
        enter("copy-tool-done-mode");
        pump(() -> output.copied == 1, "the copy");
        for (FakeSurface surface : screenTools.surfaces)
            assertTrue(surface.closed);
        assertEquals(300, pointer.x);
        assertEquals(200, pointer.y);
        assertEquals(200, screenTools.imageWidth);
        assertTrue(screenTools.imageHeight >= 350, "height " + screenTools.imageHeight);
        assertTrue(screenTools.imageHeight <= 200 + screenTools.scrolled);
        for (int y = 0; y < screenTools.imageHeight; y += 11)
            for (int x = 0; x < 200; x += 13)
                assertEquals(FakeScreenTools.pixel(region.x() + x, region.y(), y),
                        screenTools.image[y * 200 + x], x + "," + y);
    }

    @Test
    void escLeavesTheLongScreenshotAndCopiesNothing() throws Exception {
        enter("copy-menu-mode");
        copyTools.run(CopyItem.LONG_SCREENSHOT);
        enter("long-screenshot-area-mode");
        pump(() -> screenTools.shown(false) != null, "the frozen screen");
        screenTools.scrolling = true;
        assertTrue(copyTools.confirm());
        enter("long-screenshot-mode");
        pump(() -> screenTools.scrolled >= 40, "the region to scroll");
        enter("normal-mode");
        assertFalse(copyTools.active());
        for (FakeSurface surface : screenTools.surfaces)
            assertTrue(surface.closed);
        pumpFor(150);
        assertEquals(0, output.copied);
        assertNull(screenTools.image);
    }

    @Test
    void openingTheMenuAgainDropsAToolLeftOpen() throws Exception {
        textAtPointer = "one two";
        enter("copy-menu-mode");
        copyTools.run(CopyItem.TEXT);
        enter("text-pick-mode");
        pump(() -> copyTools.textPickerHtml().contains("two"), "the picker");
        enter("copy-menu-mode");
        assertFalse(copyTools.active());
        assertEquals("", copyTools.textPickerHtml());
    }

    private static int count(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length()))
            count++;
        return count;
    }

    private static final class FakePointer implements CopyTools.Pointer {

        volatile int x;
        volatile int y;

        @Override
        public int x() {
            return x;
        }

        @Override
        public int y() {
            return y;
        }

        @Override
        public void moveTo(int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public boolean jumping() {
            return false;
        }

        @Override
        public void halt() {
            halts++;
        }

        volatile int halts;
    }

    private static final class FakeOutput implements CopyTools.Output {

        final List<Command.CopyText.Kind> copies = new ArrayList<>();
        int clicks;
        int copied;
        int left;

        @Override
        public void copyText(Command.CopyText.Kind kind) {
            copies.add(kind);
        }

        @Override
        public void selectLineAndCopy() {
            clicks++;
        }

        @Override
        public void showCopied() {
            copied++;
        }

        @Override
        public void leave() {
            left++;
        }
    }

    private static final class FakeSurface implements ScreenTools.Surface {

        final boolean hiddenFromCapture;
        volatile Rectangle rectangle;
        volatile Rectangle lastDirty;
        volatile int shows;
        volatile boolean closed;

        FakeSurface(boolean hiddenFromCapture) {
            this.hiddenFromCapture = hiddenFromCapture;
        }

        @Override
        public void show(Rectangle rectangle, int[] pixels, Rectangle dirty) {
            assertFalse(closed);
            assertTrue(pixels.length >= rectangle.width() * rectangle.height());
            if (dirty != null)
                assertTrue(rectangle.contains(dirty), dirty + " in " + rectangle);
            this.rectangle = rectangle;
            this.lastDirty = dirty;
            shows++;
        }

        @Override
        public void hide() {
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** A screen whose pixels say where they are, and scroll when told to. */
    private static final class FakeScreenTools implements ScreenTools {

        final List<FakeSurface> surfaces = new CopyOnWriteArrayList<>();
        final List<Rectangle> captures = new CopyOnWriteArrayList<>();
        volatile String clipboard;
        volatile int sequence;
        volatile int[] image;
        volatile int imageWidth;
        volatile int imageHeight;
        volatile boolean captureFails;
        /** Each picture of the region is this much further down the page than the last. */
        volatile boolean scrolling;
        volatile int scrolled;

        /** The pixel at that place of the screen, once the page scrolled that far. */
        static int pixel(int x, int y, int scrolled) {
            int row = y + scrolled;
            int value = (x * 0x9E3779B1 + row * 0x85EBCA6B) ^ (x * row);
            value ^= value >>> 15;
            return 0xFF000000 | (value & 0xFFFFFF);
        }

        FakeSurface shown(boolean hiddenFromCapture) {
            for (FakeSurface surface : surfaces)
                if (surface.hiddenFromCapture == hiddenFromCapture && surface.rectangle != null)
                    return surface;
            return null;
        }

        void setClipboardFromOutside(String text) {
            clipboard = text;
            sequence++;
        }

        @Override
        public boolean supported() {
            return true;
        }

        @Override
        public boolean capture(Rectangle rectangle, int[] pixels) {
            if (captureFails)
                return false;
            captures.add(rectangle);
            int offset = scrolled;
            for (int y = 0; y < rectangle.height(); y++)
                for (int x = 0; x < rectangle.width(); x++)
                    pixels[y * rectangle.width() + x] =
                            pixel(rectangle.x() + x, rectangle.y() + y, offset);
            if (scrolling && offset < 400)
                scrolled = offset + 9;
            return true;
        }

        @Override
        public boolean copyImage(int[] pixels, int width, int height) {
            image = pixels.clone();
            imageWidth = width;
            imageHeight = height;
            sequence++;
            return true;
        }

        @Override
        public boolean copyText(String text) {
            clipboard = text;
            sequence++;
            return true;
        }

        @Override
        public String clipboardText() {
            return clipboard;
        }

        @Override
        public int clipboardSequence() {
            return sequence;
        }

        @Override
        public Surface createSurface(boolean hiddenFromCapture) {
            FakeSurface surface = new FakeSurface(hiddenFromCapture);
            surfaces.add(surface);
            return surface;
        }
    }
}
