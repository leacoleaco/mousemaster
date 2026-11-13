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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * With ui-other-windows, the hints of the window under the pointer are shown first, and
 * those of the other windows are added to them once they are found.
 */
class UiHintOtherWindowsTest {

    private static final String uiMode = """
            idle-mode.to.ui-hint-mode=+u
            ui-hint-mode.to.idle-mode=+esc
            ui-hint-mode.overlay.selection-keys=a b c
            ui-hint-mode.overlay.type=ui-accessibility
            ui-hint-mode.overlay.area=mouse-window
            ui-hint-mode.overlay.mouse-follows-target=false
            """;
    private static final String otherWindowsUiMode =
            uiMode + "ui-hint-mode.overlay.ui-other-windows=active-screen\n";

    private static final Rectangle screen = new Rectangle(0, 0, 1920, 1080);
    private static final Rectangle mouseWindow = new Rectangle(40, 50, 600, 400);
    private static final List<UiElement> mouseWindowElements = List.of(
            button(100, 100), button(200, 100), button(300, 100), button(400, 100));
    private static final List<UiElement> otherWindowsElements = List.of(
            button(1000, 600), button(1100, 600), button(1200, 600), button(1300, 600),
            button(1400, 600));

    private final CompletableFuture<List<UiElement>> mouseWindowQuery = new CompletableFuture<>();
    private final List<CompletableFuture<List<UiElement>>> otherWindowsQueries =
            new ArrayList<>();
    private final List<Point> otherWindowsQueryPoints = new ArrayList<>();
    private final List<Rectangle> otherWindowsQueryAreas = new ArrayList<>();
    private final List<HintMesh> shownHintMeshes = new ArrayList<>();

    private static UiElement button(int x, int y) {
        return new UiElement(new Rectangle(x, y, 40, 20), UiElement.Role.CLICK,
                UiElement.Control.BUTTON);
    }

    private static ModeMap parse(String properties) throws IOException {
        return ConfigurationParser.parse(PropertiesReader.readPropertiesFile(
                        new BufferedReader(new StringReader(properties))),
                KeyboardLayout.keyboardLayoutByShortName.get("uk-qwerty")).modeMap();
    }

    private HintManager hintManager() {
        Overlay overlay = (Overlay) Proxy.newProxyInstance(Overlay.class.getClassLoader(),
                new Class<?>[]{Overlay.class}, (proxy, method, args) -> {
                    if (method.getName().equals("setHintMesh"))
                        shownHintMeshes.add((HintMesh) args[0]);
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
                        otherWindowsQueryPoints.add(new Point(x, y));
                        otherWindowsQueryAreas.add(area);
                        otherWindowsQueries.add(new CompletableFuture<>());
                        return otherWindowsQueries.getLast();
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

    private static List<List<Key>> keySequences(HintMesh hintMesh) {
        return hintMesh.hints().stream().map(Hint::keySequence).toList();
    }

    private static void assertNoLabelBeginsAnother(List<List<Key>> keySequences) {
        assertEquals(keySequences.size(), new HashSet<>(keySequences).size());
        for (List<Key> keySequence : keySequences)
            for (List<Key> other : keySequences)
                if (other.size() > keySequence.size())
                    assertNotEquals(keySequence, other.subList(0, keySequence.size()),
                            keySequence + " begins " + other);
    }

    @Test
    void onlyTheWindowUnderThePointerIsLookedInByDefault() throws IOException {
        Mode mode = parse(uiMode).get("ui-hint-mode");
        assertEquals(UiHintOtherWindows.NONE, mode.hintMesh().type().otherWindows());
        HintManager hintManager = showingMouseWindowHints(mode, mouseWindowElements);
        assertEquals(List.of(), otherWindowsQueries);
        // Four hints for three keys: two keep one key, the last key begins the two others.
        assertEquals(List.of(1, 1, 2, 2),
                keySequences(hintManager.hintMesh()).stream().map(List::size).toList());
    }

    @Test
    void theOtherWindowsAreThoseOfTheActiveScreenOrOfEveryScreen() throws IOException {
        assertEquals(UiHintOtherWindows.ACTIVE_SCREEN, parse(otherWindowsUiMode)
                .get("ui-hint-mode").hintMesh().type().otherWindows());
        assertEquals(UiHintOtherWindows.ALL_SCREENS,
                parse(uiMode + "ui-hint-mode.overlay.ui-other-windows=all-screens\n")
                        .get("ui-hint-mode").hintMesh().type().otherWindows());
        assertThrows(IllegalArgumentException.class,
                () -> parse(uiMode + "ui-hint-mode.overlay.ui-other-windows=mouse-window\n"));
    }

    @Test
    void theOtherWindowsAreLookedInOnceTheMouseWindowHintsAreShown() throws IOException {
        HintManager hintManager = hintManager();
        hintManager.mouseMoved(120, 80);
        hintManager.modeChanged(parse(otherWindowsUiMode).get("ui-hint-mode"));
        hintManager.completePendingUiHintQuery();
        assertEquals(List.of(), otherWindowsQueries,
                "the window under the pointer is still looked in");
        // The pointer moving on does not change which window was the one under it.
        hintManager.mouseMoved(900, 700);
        mouseWindowQuery.complete(mouseWindowElements);
        hintManager.completePendingUiHintQuery();
        assertEquals(4, hintManager.hintMesh().hints().size());
        assertFalse(hintManager.waitingForUiElements(), "a hint can be selected");
        assertEquals(List.of(new Point(120, 80)), otherWindowsQueryPoints);
        assertEquals(List.of(screen), otherWindowsQueryAreas);
    }

    @Test
    void theHintsOfTheOtherWindowsAreAddedToTheOnesShown() throws IOException {
        HintManager hintManager = showingMouseWindowHints(
                parse(otherWindowsUiMode).get("ui-hint-mode"), mouseWindowElements);
        List<Hint> mouseWindowHints = hintManager.hintMesh().hints();
        // The first key of a label that has two.
        Key firstKey = mouseWindowHints.getLast().keySequence().getFirst();
        assertEquals(2, mouseWindowHints.getLast().keySequence().size());
        hintManager.selectHintKey(firstKey);
        otherWindowsQueries.getFirst().complete(otherWindowsElements);
        hintManager.completePendingUiHintQuery();
        List<Hint> hints = hintManager.hintMesh().hints();
        assertEquals(9, hints.size());
        assertEquals(mouseWindowHints, hints.subList(0, 4),
                "the hints shown first keep their labels");
        assertEquals(List.of(1020d, 1120d, 1220d, 1320d, 1420d),
                hints.subList(4, 9).stream().map(Hint::centerX).toList());
        assertNoLabelBeginsAnother(keySequences(hintManager.hintMesh()));
        assertEquals(List.of(firstKey), hintManager.hintMesh().selectedKeySequence(),
                "the key pressed before they arrived is still pressed");
        assertEquals(hintManager.hintMesh(), shownHintMeshes.getLast());
        // The area and its background stay the window under the pointer.
        assertEquals(mouseWindow, hintManager.hintMesh().area());
    }

    @Test
    void aHintOfAnotherWindowIsSelectedLikeAnyOther() throws IOException {
        HintManager hintManager = showingMouseWindowHints(
                parse(otherWindowsUiMode).get("ui-hint-mode"), mouseWindowElements);
        otherWindowsQueries.getFirst().complete(otherWindowsElements);
        hintManager.completePendingUiHintQuery();
        Hint hint = hintManager.hintMesh().hints().getLast();
        for (Key key : hint.keySequence())
            hintManager.selectHintKey(key);
        assertEquals(hint, hintManager.hintMesh().hints().stream()
                .filter(candidate -> candidate.keySequence()
                        .equals(hintManager.hintMesh().selectedKeySequence()))
                .findFirst().orElseThrow());
        assertEquals(new Point(1420, 610), hintManager.targetPoint());
    }

    @Test
    void aWindowWithNoElementIsNotAnEmptyOverlayWhileTheOthersAreLookedIn()
            throws IOException {
        HintManager hintManager = showingMouseWindowHints(
                parse(otherWindowsUiMode).get("ui-hint-mode"), List.of());
        assertFalse(hintManager.hintMeshEmpty());
        otherWindowsQueries.getFirst().complete(otherWindowsElements);
        hintManager.completePendingUiHintQuery();
        assertFalse(hintManager.hintMeshEmpty());
        assertEquals(5, hintManager.hintMesh().hints().size());
    }

    @Test
    void theOverlayIsEmptyWhenNoWindowHasAnElement() throws IOException {
        HintManager hintManager = showingMouseWindowHints(
                parse(otherWindowsUiMode).get("ui-hint-mode"), List.of());
        otherWindowsQueries.getFirst().complete(List.of());
        hintManager.completePendingUiHintQuery();
        assertTrue(hintManager.hintMeshEmpty());
    }

    @Test
    void leavingTheModeStopsLookingInTheOtherWindows() throws IOException {
        ModeMap modeMap = parse(otherWindowsUiMode);
        HintManager hintManager = showingMouseWindowHints(modeMap.get("ui-hint-mode"),
                mouseWindowElements);
        hintManager.modeChanged(modeMap.get(Mode.IDLE_MODE_NAME));
        assertTrue(otherWindowsQueries.getFirst().isCancelled());
        hintManager.completePendingUiHintQuery();
        assertNull(hintManager.hintMesh());
    }

    @Test
    void selectingAHintStopsLookingInTheOtherWindows() throws IOException {
        HintManager hintManager = showingMouseWindowHints(
                parse(otherWindowsUiMode).get("ui-hint-mode"), mouseWindowElements);
        hintManager.selectHintKey(hintManager.hintMesh().hints().getFirst().keySequence()
                                             .getFirst());
        assertTrue(otherWindowsQueries.getFirst().isCancelled());
    }

    @Test
    void enteringTheModeAgainLooksInTheOtherWindowsAgain() throws IOException {
        ModeMap modeMap = parse(otherWindowsUiMode);
        HintManager hintManager = showingMouseWindowHints(modeMap.get("ui-hint-mode"),
                mouseWindowElements);
        otherWindowsQueries.getFirst().complete(otherWindowsElements);
        hintManager.completePendingUiHintQuery();
        hintManager.modeChanged(modeMap.get(Mode.IDLE_MODE_NAME));
        hintManager.modeChanged(modeMap.get("ui-hint-mode"));
        hintManager.completePendingUiHintQuery();
        assertEquals(4, hintManager.hintMesh().hints().size(),
                "the hints of the other windows are not shown before they are found again");
        assertEquals(2, otherWindowsQueries.size());
    }

    @Test
    void aMutationOfTheModeKeepsTheHintsOfTheOtherWindows() throws IOException {
        Mode mode = parse(otherWindowsUiMode).get("ui-hint-mode");
        HintManager hintManager = showingMouseWindowHints(mode, mouseWindowElements);
        // Before they are found: the scan goes on.
        hintManager.modeChanged(mode);
        assertFalse(otherWindowsQueries.getFirst().isCancelled());
        otherWindowsQueries.getFirst().complete(otherWindowsElements);
        hintManager.completePendingUiHintQuery();
        List<Hint> hints = hintManager.hintMesh().hints();
        // After they are found: they are neither dropped nor looked for again.
        hintManager.modeChanged(mode);
        assertEquals(hints, hintManager.hintMesh().hints());
        assertEquals(1, otherWindowsQueries.size());
    }

    @Test
    void anElementThatSitsOnOneOfTheWindowUnderThePointerGetsNoHint() throws IOException {
        HintManager hintManager = showingMouseWindowHints(
                parse(otherWindowsUiMode).get("ui-hint-mode"), mouseWindowElements);
        otherWindowsQueries.getFirst().complete(List.of(button(102, 101), button(1000, 600)));
        hintManager.completePendingUiHintQuery();
        assertEquals(List.of(120d, 220d, 320d, 420d, 1020d),
                hintManager.hintMesh().hints().stream().map(Hint::centerX).toList());
    }

    @Test
    void theRoleFilterAppliesToTheOtherWindows() throws IOException {
        HintManager hintManager = showingMouseWindowHints(
                parse(otherWindowsUiMode).get("ui-hint-mode"), mouseWindowElements);
        UiElement edit = new UiElement(new Rectangle(1500, 800, 200, 30),
                UiElement.Role.INPUT, UiElement.Control.EDIT);
        hintManager.filterUiHints(UiElement.Role.INPUT);
        assertEquals(0, hintManager.hintMesh().hints().size());
        otherWindowsQueries.getFirst().complete(List.of(button(1000, 600), edit));
        hintManager.completePendingUiHintQuery();
        assertEquals(List.of(1600d),
                hintManager.hintMesh().hints().stream().map(Hint::centerX).toList());
        hintManager.filterUiHints(null);
        assertEquals(6, hintManager.hintMesh().hints().size());
    }

    @Test
    void aFailedScanOfTheOtherWindowsLeavesTheHintsShown() throws IOException {
        HintManager hintManager = showingMouseWindowHints(
                parse(otherWindowsUiMode).get("ui-hint-mode"), mouseWindowElements);
        List<Hint> hints = hintManager.hintMesh().hints();
        otherWindowsQueries.getFirst().completeExceptionally(new IllegalStateException());
        hintManager.completePendingUiHintQuery();
        assertEquals(hints, hintManager.hintMesh().hints());
        assertFalse(hintManager.hintMeshEmpty());
    }

    private static List<Key> keys(String names) {
        return names.chars().mapToObj(name -> Key.ofName(String.valueOf((char) name))).toList();
    }

    @Test
    void theFirstLabelsDoNotDependOnHowManyHintsFollow() {
        List<Key> keys = keys("qwerasdfzxcv");
        for (int firstCount : List.of(0, 1, 11, 12, 13, 60, 143, 144, 400)) {
            List<List<Key>> alone =
                    HintManager.mouseWindowFirstKeySequences(keys, firstCount, 0);
            assertEquals(firstCount, alone.size());
            for (int otherCount : List.of(1, 11, 12, 200, 2000)) {
                List<List<Key>> keySequences = HintManager.mouseWindowFirstKeySequences(
                        keys, firstCount, otherCount);
                assertEquals(firstCount + otherCount, keySequences.size());
                assertEquals(alone, keySequences.subList(0, firstCount));
                assertNoLabelBeginsAnother(keySequences);
            }
        }
    }

    @Test
    void theLastKeyBeginsNoLabelOfTheFirstHints() {
        List<Key> keys = keys("qwerasdfzxcv");
        for (int firstCount : List.of(1, 11, 12, 60, 144, 400))
            assertTrue(HintManager.mouseWindowFirstKeySequences(keys, firstCount, 0).stream()
                                  .noneMatch(keySequence -> keySequence.getFirst()
                                                                       .equals(keys.getLast())),
                    firstCount + " hints");
        // Eleven hints have one key each, as they would alone.
        assertTrue(HintManager.mouseWindowFirstKeySequences(keys, 11, 0).stream()
                              .allMatch(keySequence -> keySequence.size() == 1));
        // Sixty hints alone would have seven labels of one key.
        List<List<Key>> sixty = HintManager.mouseWindowFirstKeySequences(keys, 60, 0);
        assertEquals(6, sixty.stream().filter(keySequence -> keySequence.size() == 1).count());
        assertEquals(54, sixty.stream().filter(keySequence -> keySequence.size() == 2).count());
    }

    @Test
    void theOtherHintsTakeTheShortestLabelsLeft() {
        List<Key> keys = keys("qwerasdfzxcv");
        // Eight hints use eight keys. Three are left, and the last key, which begins the
        // longer labels once there are more hints than labels.
        List<List<Key>> few = HintManager.mouseWindowFirstKeySequences(keys, 8, 5);
        assertEquals(List.of(1, 1, 1, 2, 2),
                few.subList(8, 13).stream().map(List::size).toList());
        assertEquals(List.of(keys.getLast(), keys.getFirst()), few.get(11));
        // A screen of windows: no label of the window under the pointer has three keys,
        // and no label of the other windows has four.
        List<List<Key>> many = HintManager.mouseWindowFirstKeySequences(keys, 100, 250);
        assertTrue(many.subList(0, 100).stream().allMatch(
                keySequence -> keySequence.size() <= 2));
        assertTrue(many.subList(100, 350).stream().allMatch(
                keySequence -> keySequence.size() <= 3));
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> null);
    }

}
