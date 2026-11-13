package mousemaster;

import io.qt.gui.QImage;
import mousemaster.platform.MouseController;
import mousemaster.platform.Overlay;
import mousemaster.platform.UiAutomation;
import mousemaster.platform.UiAutomation.UiElement;
import mousemaster.renderer.HintMeshRenderer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The boxes of UI hints as the renderer lays them out, with the fonts Qt measures. */
class UiHintBoxLayoutTest {

    private static final String uiMode = """
            idle-mode.to.ui-hint-mode=+u
            ui-hint-mode.to.idle-mode=+esc
            ui-hint-mode.overlay.type=ui-accessibility
            ui-hint-mode.overlay.area=mouse-window
            ui-hint-mode.overlay.mouse-follows-target=false
            ui-hint-mode.overlay.selection-keys=a b c
            """;

    private static final Screen screen = new Screen(new Rectangle(0, 0, 1920, 1080), 96, 1);

    private static boolean qtAvailable;

    @BeforeAll
    static void initializeQt() {
        MousemasterApplication.tempDirectory =
                System.getProperty("java.io.tmpdir") + "/mousemaster-ui-hint-box-layout-test";
        try {
            QtManager.initialize();
        } catch (Throwable alreadyInitializedByAnotherTest) {
        }
        try {
            new QImage(1, 1, QImage.Format.Format_ARGB32_Premultiplied).dispose();
            qtAvailable = true;
        } catch (Throwable e) {
            qtAvailable = false;
        }
    }

    private static Mode mode() throws IOException {
        return ConfigurationParser.parse(PropertiesReader.readPropertiesFile(
                        new BufferedReader(new StringReader(uiMode))),
                KeyboardLayout.keyboardLayoutByShortName.get("uk-qwerty")).modeMap()
                                  .get("ui-hint-mode");
    }

    private static HintMeshRenderer renderer() {
        return new HintMeshRenderer(() -> null, () -> {
        });
    }

    private static Hint hint(double centerX, double centerY, String label) {
        return new Hint(centerX, centerY, -1, -1,
                label.chars().mapToObj(name -> Key.ofName(String.valueOf((char) name))).toList(),
                new Rectangle((int) centerX - 20, (int) centerY - 10, 40, 20),
                UiElement.Role.CLICK, UiElement.Control.BUTTON);
    }

    private static boolean overlap(Rectangle box, Rectangle otherBox) {
        return !box.intersection(otherBox).isEmpty();
    }

    @Test
    void theBoxOfAHintIsOnItsElementAndGrowsWithItsLabel() throws IOException {
        assumeTrue(qtAvailable, "Qt natives are unavailable here");
        Mode mode = mode();
        HintMeshStyle style =
                mode.hintMesh().styleByFilter().get(ScreenFilter.of(screen));
        Hint oneKey = hint(300, 200, "a");
        Hint twoKeys = hint(600, 200, "ba");
        Hint near = hint(615, 200, "bb");
        Hint offScreen = hint(5000, 200, "bc");
        HintMesh hintMesh = new HintMesh.HintMeshBuilder()
                .visible(true)
                .styleByFilter(mode.hintMesh().styleByFilter())
                .hints(List.of(oneKey, twoKeys, near, offScreen))
                .prefixLength(-1)
                .build();
        Map<Hint, Rectangle> boxes = renderer().hintBoxRectangles(hintMesh, Set.of(screen));
        assertEquals(Set.of(oneKey, twoKeys, near), boxes.keySet());
        for (Hint hint : List.of(oneKey, twoKeys, near)) {
            Rectangle box = boxes.get(hint);
            assertTrue(box.width() > 4 && box.height() > 4, hint + " in " + box);
            double centerX = hint.centerX() + style.hintCenterOffsetX();
            assertEquals(centerX, box.x() + box.width() / 2d, 1.5, hint + " in " + box);
            assertEquals(hint.centerY(), box.y() + box.height() / 2d, 1.5, hint + " in " + box);
        }
        assertTrue(boxes.get(twoKeys).width() > boxes.get(oneKey).width(),
                boxes.get(twoKeys) + " and " + boxes.get(oneKey));
        assertEquals(boxes.get(twoKeys).height(), boxes.get(oneKey).height());
        assertTrue(overlap(boxes.get(twoKeys), boxes.get(near)),
                boxes.get(twoKeys) + " and " + boxes.get(near));
        assertFalse(overlap(boxes.get(oneKey), boxes.get(twoKeys)));
    }

    @Test
    void hintsWhoseBoxesOverlapOnScreenBeginWithDifferentKeysAndAreMarked() throws IOException {
        assumeTrue(qtAvailable, "Qt natives are unavailable here");
        HintMeshRenderer renderer = renderer();
        Overlay overlay = (Overlay) Proxy.newProxyInstance(Overlay.class.getClassLoader(),
                new Class<?>[]{Overlay.class}, (proxy, method, args) -> {
                    if (method.getName().equals("hintBoxRectangles"))
                        return renderer.hintBoxRectangles((HintMesh) args[0], Set.of(screen));
                    if (method.getName().equals("mouseWindowRectangle"))
                        return new Rectangle(40, 50, 1600, 400);
                    if (method.getReturnType() == boolean.class)
                        return false;
                    if (method.getReturnType() == int.class)
                        return 0;
                    return null;
                });
        MouseController mouseController = (MouseController) Proxy.newProxyInstance(
                MouseController.class.getClassLoader(), new Class<?>[]{MouseController.class},
                (proxy, method, args) -> null);
        CompletableFuture<List<UiElement>> mouseWindowQuery = new CompletableFuture<>();
        ScreenManager screenManager = new ScreenManager(() -> Set.of(screen));
        HintManager hintManager = new HintManager(Map.of(), screenManager,
                new MouseManager(screenManager, mouseController), overlay,
                new UiAutomation() {
                    @Override
                    public Future<List<UiElement>> startFindActiveWindowUiElements() {
                        return fail("looked in the active window");
                    }

                    @Override
                    public Future<List<UiElement>> startFindUiElementsInArea(Rectangle area) {
                        return fail("looked in an area");
                    }

                    @Override
                    public Future<List<UiElement>> startFindMouseWindowUiElements(int x, int y) {
                        return mouseWindowQuery;
                    }

                    @Override
                    public Future<List<UiElement>> startFindOtherWindowsUiElements(
                            int x, int y, Rectangle area) {
                        return fail("looked in the other windows");
                    }
                }, () -> new App("test.exe"), new KeyRedactor(KeyRedaction.NONE), null);
        hintManager.mouseMoved(120, 80);
        hintManager.modeChanged(mode());
        // Seven hints for three keys: a, then ba bb bc ca cb cc. The second and the third
        // are 15 pixels apart, closer than a label of two keys is wide.
        List<UiElement> elements = new java.util.ArrayList<>();
        for (int centerX : new int[] {100, 300, 315, 500, 700, 900, 1100})
            elements.add(new UiElement(new Rectangle(centerX - 20, 90, 40, 20),
                    UiElement.Role.CLICK, UiElement.Control.BUTTON));
        mouseWindowQuery.complete(elements);
        hintManager.completePendingUiHintQuery();
        List<Hint> hints = hintManager.hintMesh().hints();
        assertEquals(List.of(100d, 300d, 315d, 500d, 700d, 900d, 1100d),
                hints.stream().map(Hint::centerX).toList());
        assertEquals(List.of(false, true, true, false, false, false, false),
                hints.stream().map(Hint::overlapped).toList());
        assertNotEquals(hints.get(1).keySequence().getFirst(),
                hints.get(2).keySequence().getFirst());
        assertEquals(2, hints.get(1).keySequence().size());
        assertEquals(2, hints.get(2).keySequence().size());
    }

}
