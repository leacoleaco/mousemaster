package mousemaster;

import mousemaster.platform.MouseController;
import mousemaster.platform.Overlay;
import mousemaster.platform.UiAutomation;
import mousemaster.platform.UiAutomation.UiElement;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two UI hints whose boxes overlap begin with different keys, and show that first key only
 * until it is pressed.
 */
class UiHintOverlapTest {

    private static final String uiMode = """
            idle-mode.to.ui-hint-mode=+u
            ui-hint-mode.to.idle-mode=+esc
            ui-hint-mode.overlay.type=ui-accessibility
            ui-hint-mode.overlay.area=mouse-window
            ui-hint-mode.overlay.mouse-follows-target=false
            """;

    private static final Rectangle screen = new Rectangle(0, 0, 1920, 1080);
    private static final Rectangle mouseWindow = new Rectangle(40, 50, 600, 400);
    /** The box the overlay of these tests draws a key in. */
    private static final int keyWidth = 10;
    private static final int boxHeight = 16;

    private final CompletableFuture<List<UiElement>> mouseWindowQuery = new CompletableFuture<>();
    private final CompletableFuture<List<UiElement>> otherWindowsQuery =
            new CompletableFuture<>();
    /** Null is an overlay that does not lay hints out. */
    private Integer overlayKeyWidth = keyWidth;

    private static UiElement button(int centerX, int centerY) {
        return new UiElement(new Rectangle(centerX - 20, centerY - 10, 40, 20),
                UiElement.Role.CLICK, UiElement.Control.BUTTON);
    }

    private static Mode mode(String properties) throws IOException {
        return ConfigurationParser.parse(PropertiesReader.readPropertiesFile(
                        new BufferedReader(new StringReader(uiMode + properties))),
                KeyboardLayout.keyboardLayoutByShortName.get("uk-qwerty")).modeMap()
                                  .get("ui-hint-mode");
    }

    /** A box as wide as the label has keys, centered on the hint. */
    private Map<Hint, Rectangle> hintBoxRectangles(HintMesh hintMesh) {
        if (overlayKeyWidth == null)
            return null;
        Map<Hint, Rectangle> rectangles = new HashMap<>();
        for (Hint hint : hintMesh.hints()) {
            int width = overlayKeyWidth * hint.keySequence().size();
            rectangles.put(hint, new Rectangle((int) hint.centerX() - width / 2,
                    (int) hint.centerY() - boxHeight / 2, width, boxHeight));
        }
        return rectangles;
    }

    private HintManager hintManager() {
        Overlay overlay = (Overlay) Proxy.newProxyInstance(Overlay.class.getClassLoader(),
                new Class<?>[]{Overlay.class}, (proxy, method, args) -> {
                    if (method.getName().equals("hintBoxRectangles"))
                        return hintBoxRectangles((HintMesh) args[0]);
                    if (method.getName().equals("mouseWindowRectangle"))
                        return mouseWindow;
                    if (method.getReturnType() == boolean.class)
                        return false;
                    if (method.getReturnType() == int.class)
                        return 0;
                    return null;
                });
        ScreenManager screenManager =
                new ScreenManager(() -> Set.of(new Screen(screen, 96, 1)));
        return new HintManager(Map.of(), screenManager,
                new MouseManager(screenManager, proxy(MouseController.class)), overlay,
                new UiAutomation() {
                    @Override
                    public Future<List<UiElement>> startFindActiveWindowUiElements() {
                        fail("looked in the active window");
                        return null;
                    }

                    @Override
                    public Future<List<UiElement>> startFindUiElementsInArea(Rectangle area) {
                        fail("looked in an area");
                        return null;
                    }

                    @Override
                    public Future<List<UiElement>> startFindMouseWindowUiElements(int x, int y) {
                        return mouseWindowQuery;
                    }

                    @Override
                    public Future<List<UiElement>> startFindOtherWindowsUiElements(
                            int x, int y, Rectangle area) {
                        return otherWindowsQuery;
                    }
                }, () -> new App("test.exe"), new KeyRedactor(KeyRedaction.NONE), null);
    }

    /** In the mode, with the hints of the window under the pointer shown. */
    private HintManager showingMouseWindowHints(Mode mode, List<UiElement> elements) {
        HintManager hintManager = hintManager();
        hintManager.mouseMoved(120, 80);
        hintManager.modeChanged(mode);
        mouseWindowQuery.complete(elements);
        hintManager.completePendingUiHintQuery();
        return hintManager;
    }

    private static List<Key> keys(String names) {
        return names.chars().mapToObj(name -> Key.ofName(String.valueOf((char) name))).toList();
    }

    private static List<List<Key>> labels(String... names) {
        return List.of(names).stream().map(UiHintOverlapTest::keys).toList();
    }

    private static List<List<Key>> labels(List<Hint> hints) {
        return hints.stream().map(Hint::keySequence).toList();
    }

    private static List<Boolean> overlapped(List<Hint> hints) {
        return hints.stream().map(Hint::overlapped).toList();
    }

    /** The pairs of hints that overlap and begin with the same key. */
    private static int alikeCount(List<List<Key>> labels, List<List<Integer>> overlaps) {
        int alikeCount = 0;
        for (int index = 0; index < labels.size(); index++)
            for (int otherIndex : overlaps.get(index))
                if (otherIndex > index && labels.get(index).getFirst()
                                                .equals(labels.get(otherIndex).getFirst()))
                    alikeCount++;
        return alikeCount;
    }

    private static List<List<Integer>> overlaps(int count, int... pairs) {
        List<List<Integer>> overlaps = new ArrayList<>();
        for (int index = 0; index < count; index++)
            overlaps.add(new ArrayList<>());
        for (int pairIndex = 0; pairIndex < pairs.length; pairIndex += 2) {
            overlaps.get(pairs[pairIndex]).add(pairs[pairIndex + 1]);
            overlaps.get(pairs[pairIndex + 1]).add(pairs[pairIndex]);
        }
        return overlaps;
    }

    @Test
    void boxesThatShareAreaOverlap() {
        List<Rectangle> boxes = new ArrayList<>(List.of(
                new Rectangle(100, 100, 20, 16),
                // On the first.
                new Rectangle(110, 108, 20, 16),
                // Touches the first on its right edge.
                new Rectangle(120, 100, 20, 16),
                // Touches the first on its bottom edge.
                new Rectangle(100, 116, 5, 16),
                new Rectangle(500, 100, 20, 16)));
        // On no screen.
        boxes.add(null);
        assertEquals(List.of(List.of(1), List.of(0, 2), List.of(1), List.of(), List.of(),
                List.of()), UiHintOverlap.overlaps(boxes));
    }

    @Test
    void twoOverlappingHintsAreGivenLabelsThatBeginWithDifferentKeys() {
        List<List<Key>> labels = labels("a", "ba", "bb", "bc", "ca", "cb", "cc");
        List<List<Integer>> overlaps = overlaps(7, 1, 2);
        List<List<Key>> apart = UiHintOverlap.labelsBeginningApart(labels, overlaps, 0);
        assertEquals(0, alikeCount(apart, overlaps));
        // The nearest hint that begins with another key gave its label.
        assertEquals(labels("a", "ca", "bb", "bc", "ba", "cb", "cc"), apart);
    }

    @Test
    void hintsThatDoNotOverlapKeepTheirLabels() {
        List<List<Key>> labels = labels("a", "ba", "bb", "bc", "ca", "cb", "cc");
        assertEquals(labels, UiHintOverlap.labelsBeginningApart(labels, overlaps(7), 0));
        // They overlap, and already begin with different keys.
        assertEquals(labels,
                UiHintOverlap.labelsBeginningApart(labels, overlaps(7, 0, 1, 3, 4), 0));
    }

    @Test
    void aLabelOfAsManyKeysIsTakenBeforeANearerOneOfAnotherLength() {
        List<List<Key>> labels = labels("a", "ba", "bb", "ca");
        assertEquals(labels("a", "ca", "bb", "ba"),
                UiHintOverlap.labelsBeginningApart(labels, overlaps(4, 1, 2), 0));
    }

    @Test
    void aLabelOfAnotherLengthIsTakenWhenTheLongestLabelsAllBeginAlike() {
        List<List<Key>> labels = labels("a", "b", "ca", "cb");
        assertEquals(labels("a", "ca", "b", "cb"),
                UiHintOverlap.labelsBeginningApart(labels, overlaps(4, 2, 3), 0));
        // One swap sets two pairs apart.
        assertEquals(labels("a", "caa", "bb", "ba", "cab"),
                UiHintOverlap.labelsBeginningApart(labels("a", "ba", "bb", "caa", "cab"),
                        overlaps(5, 1, 2, 3, 4), 0));
    }

    @Test
    void theFirstHintsKeepTheirLabels() {
        List<List<Key>> labels = labels("ba", "bb", "bc", "ca", "cb");
        List<List<Integer>> overlaps = overlaps(5, 0, 1);
        assertEquals(labels("ba", "ca", "bc", "bb", "cb"),
                UiHintOverlap.labelsBeginningApart(labels, overlaps, 1));
        // Nothing is left to swap with.
        assertEquals(labels, UiHintOverlap.labelsBeginningApart(labels, overlaps, 4));
    }

    @Test
    void aSwapDoesNotMakeTheOtherHintBeginLikeOneItOverlaps() {
        // The third hint is nearer than the fifth, but it is on a hint that begins with b.
        List<List<Key>> labels = labels("ba", "bb", "ca", "bc", "da");
        assertEquals(labels("da", "bb", "ca", "bc", "ba"),
                UiHintOverlap.labelsBeginningApart(labels, overlaps(5, 0, 1, 2, 3), 0));
    }

    @Test
    void hintsTooManyOnEachOtherKeepSomeThatBeginAlike() {
        List<List<Key>> labels = labels("aa", "ab", "ac", "ba", "bb", "bc");
        // The first four are all on each other, and labels begin with two keys.
        List<List<Integer>> overlaps = overlaps(6, 0, 1, 0, 2, 0, 3, 1, 2, 1, 3, 2, 3);
        assertEquals(3, alikeCount(labels, overlaps));
        List<List<Key>> apart = UiHintOverlap.labelsBeginningApart(labels, overlaps, 0);
        assertEquals(2, alikeCount(apart, overlaps));
        assertEquals(new HashSet<>(labels), new HashSet<>(apart));
    }

    @Test
    void manyOverlappingHintsAreRelabeledQuickly() {
        List<Key> keys = keys("qwerasdfzxcv");
        int count = 2000;
        List<List<Key>> labels = HintManager.mouseWindowFirstKeySequences(keys, count, 0);
        // A row of hints, each on the next two.
        int[] pairs = new int[4 * (count - 2)];
        for (int index = 0; index < count - 2; index++) {
            pairs[4 * index] = index;
            pairs[4 * index + 1] = index + 1;
            pairs[4 * index + 2] = index;
            pairs[4 * index + 3] = index + 2;
        }
        List<List<Integer>> overlaps = overlaps(count, pairs);
        long before = System.nanoTime();
        List<List<Key>> apart = UiHintOverlap.labelsBeginningApart(labels, overlaps, 0);
        long milliseconds = (System.nanoTime() - before) / 1_000_000;
        assertEquals(0, alikeCount(apart, overlaps));
        assertEquals(new HashSet<>(labels), new HashSet<>(apart));
        for (int index = 0; index < count; index++)
            assertEquals(labels.get(index).size(), apart.get(index).size());
        assertTrue(milliseconds < 2000, milliseconds + "ms");
    }

    @Test
    void theHintsOfAWindowThatOverlapBeginWithDifferentKeysAndAreMarked() throws IOException {
        // Seven hints for three keys: a, then ba bb bc ca cb cc. The second and the third
        // are 10 pixels apart, and a label of two keys is 20 pixels wide.
        HintManager hintManager = showingMouseWindowHints(
                mode("ui-hint-mode.overlay.selection-keys=a b c\n"),
                List.of(button(100, 100), button(300, 100), button(310, 100),
                        button(500, 100), button(700, 100), button(900, 100),
                        button(1100, 100)));
        List<Hint> hints = hintManager.hintMesh().hints();
        assertEquals(List.of(100d, 300d, 310d, 500d, 700d, 900d, 1100d),
                hints.stream().map(Hint::centerX).toList());
        assertEquals(labels("a", "ca", "bb", "bc", "ba", "cb", "cc"), labels(hints));
        assertEquals(List.of(false, true, true, false, false, false, false),
                overlapped(hints));
        // The first key of one hides the other.
        hintManager.selectHintKey(hints.get(1).keySequence().getFirst());
        assertFalse(hints.get(2).startsWith(hintManager.hintMesh().selectedKeySequence()));
    }

    @Test
    void theLongestLabelsThatAllBeginAlikeAreSwappedWithShorterOnes() throws IOException {
        // Four hints for three keys: a b, then ca cb, which are on each other.
        HintManager hintManager = showingMouseWindowHints(
                mode("ui-hint-mode.overlay.selection-keys=a b c\n"),
                List.of(button(100, 100), button(300, 100), button(500, 100),
                        button(510, 100)));
        List<Hint> hints = hintManager.hintMesh().hints();
        assertEquals(labels("a", "ca", "b", "cb"), labels(hints));
        // The box of b is narrower than the box of ca was, and is still on the box of cb.
        assertEquals(List.of(false, false, true, true), overlapped(hints));
    }

    @Test
    void aLabelThatWidensABoxOntoAnotherIsSwappedAgain() throws IOException {
        // Seven hints for four keys: a b c, then da db dc dd. db and dc are on each other,
        // and the nearest hint that begins with another key is c. With a label of two keys
        // its box reaches the box of da, in the row below.
        HintManager hintManager = showingMouseWindowHints(
                mode("ui-hint-mode.overlay.selection-keys=a b c d\n"),
                List.of(button(100, 100), button(200, 100), button(300, 100),
                        button(317, 110), button(600, 110), button(610, 110),
                        button(800, 110)));
        List<Hint> hints = hintManager.hintMesh().hints();
        assertEquals(List.of(100d, 200d, 300d, 317d, 600d, 610d, 800d),
                hints.stream().map(Hint::centerX).toList());
        assertEquals(labels("a", "db", "b", "da", "c", "dc", "dd"), labels(hints));
        assertEquals(List.of(false, false, false, false, true, true, false),
                overlapped(hints));
    }

    @Test
    void anOverlayThatDoesNotLayHintsOutLeavesThemAsTheyAre() throws IOException {
        overlayKeyWidth = null;
        HintManager hintManager = showingMouseWindowHints(
                mode("ui-hint-mode.overlay.selection-keys=a b c\n"),
                List.of(button(100, 100), button(300, 100), button(310, 100),
                        button(500, 100), button(700, 100), button(900, 100),
                        button(1100, 100)));
        List<Hint> hints = hintManager.hintMesh().hints();
        assertEquals(labels("a", "ba", "bb", "bc", "ca", "cb", "cc"), labels(hints));
        assertTrue(overlapped(hints).stream().noneMatch(overlapped -> overlapped));
    }

    @Test
    void theHintsOfTheWindowUnderThePointerKeepTheirLabelsWhenTheOthersArrive()
            throws IOException {
        // Six hints of the window under the pointer for five keys, the last of which is
        // kept for the other windows: a b c, then da db dc. da and db are on each other.
        HintManager hintManager = showingMouseWindowHints(
                mode("""
                        ui-hint-mode.overlay.selection-keys=a b c d e
                        ui-hint-mode.overlay.ui-other-windows=active-screen
                        """),
                List.of(button(100, 100), button(200, 100), button(300, 100),
                        button(400, 100), button(410, 100), button(500, 100)));
        List<Hint> mouseWindowHints = hintManager.hintMesh().hints();
        assertEquals(labels("a", "b", "da", "c", "db", "dc"), labels(mouseWindowHints));
        assertEquals(List.of(false, false, false, true, true, false),
                overlapped(mouseWindowHints));
        // The other windows: dd de ea eb ec. dd and de are on each other.
        otherWindowsQuery.complete(List.of(button(510, 600), button(520, 600),
                button(1000, 600), button(1200, 600), button(1400, 600)));
        hintManager.completePendingUiHintQuery();
        List<Hint> hints = hintManager.hintMesh().hints();
        assertEquals(11, hints.size());
        assertEquals(labels(mouseWindowHints), labels(hints.subList(0, 6)));
        assertEquals(labels("ea", "de", "dd", "eb", "ec"), labels(hints.subList(6, 11)));
        assertEquals(List.of(false, false, false, true, true, false,
                true, true, false, false, false), overlapped(hints));
    }

    @Test
    void aHintOfAnotherWindowDoesNotBeginLikeTheHintItIsOn() throws IOException {
        HintManager hintManager = showingMouseWindowHints(
                mode("""
                        ui-hint-mode.overlay.selection-keys=a b c d e
                        ui-hint-mode.overlay.ui-other-windows=active-screen
                        """),
                List.of(button(100, 100), button(200, 100), button(300, 100),
                        button(400, 100), button(500, 100), button(600, 100)));
        // dd is on dc, the last hint of the window under the pointer.
        otherWindowsQuery.complete(List.of(button(615, 100), button(1000, 600),
                button(1200, 600), button(1400, 600), button(1600, 600)));
        hintManager.completePendingUiHintQuery();
        List<Hint> hints = hintManager.hintMesh().hints();
        assertEquals(labels("a", "b", "c", "da", "db", "dc"), labels(hints.subList(0, 6)));
        assertEquals(labels("ea", "de", "dd", "eb", "ec"), labels(hints.subList(6, 11)));
        assertEquals(List.of(false, false, false, false, false, true,
                true, false, false, false, false), overlapped(hints));
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> null);
    }

}
