package mousemaster;

import mousemaster.platform.ActiveAppFinder;
import mousemaster.platform.MouseController;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The label above the cursor names a click. Move and wheel leave it hidden. */
class ActionIndicatorTest {

    @Test
    void theLabelNamesTheActionAndAButtonWins() throws Exception {
        Configuration configuration = ConfigurationParser.parse(
                PropertiesReader.readPropertiesFile(Files.newBufferedReader(
                        Path.of("configuration/mousemaster.properties"))),
                KeyboardLayout.keyboardLayout("00000409", null));
        ModeMap modeMap = configuration.modeMap();
        Set<Key> unpressedPreconditionKeys = new HashSet<>();
        Set<Key> pressedPreconditionKeys = new HashSet<>();
        for (Mode mode : modeMap.modes()) {
            for (Combo combo : mode.comboMap().commandsByCombo().keySet()) {
                unpressedPreconditionKeys.addAll(
                        combo.precondition().keyPrecondition().unpressedKeySet());
                pressedPreconditionKeys.addAll(combo.precondition()
                                                    .keyPrecondition()
                                                    .pressedKeyPrecondition()
                                                    .allKeys());
            }
        }
        ActiveAppFinder noApp = () -> new App("test.exe");
        HintManager hintManager =
                new HintManager(Map.of(), null, null, null, null, noApp,
                        new KeyRedactor(KeyRedaction.NONE), null);
        ComboWatcher comboWatcher = new ComboWatcher(null, hintManager, noApp, null,
                Instant::now, unpressedPreconditionKeys, pressedPreconditionKeys,
                new KeyRedactor(KeyRedaction.NONE), modeMap,
                configuration.initiallySetVariables(), configuration.virtualKeys(),
                configuration.initiallyPressedVirtualKeys(),
                configuration.activeKeyboardLayout());
        comboWatcher.setModeListeners(List.of(new ModeListener() {
            @Override
            public void modeChanged(Mode newMode) {
            }

            @Override
            public void modeTimedOut() {
            }
        }));
        comboWatcher.modeChanged(modeMap.get("normal-mode"));
        assertFalse(layer(comboWatcher).enabled());
        assertEquals("", layer(comboWatcher).text());

        Screen screen = new Screen(new Rectangle(0, 0, 1920, 1080), 96, 1);
        MouseManager mouse = new MouseManager(new ScreenManager(() -> Set.of(screen)),
                (MouseController) Proxy.newProxyInstance(MouseController.class.getClassLoader(),
                        new Class<?>[]{MouseController.class}, (proxy, method, args) -> null));
        Mode normal = modeMap.get("normal-mode");
        mouse.modeChanged(normal);
        mouse.setWheel(normal.wheel());
        mouse.startMoveUp();
        mouse.startMoveLeft();
        apply(comboWatcher, mouse);
        assertFalse(layer(comboWatcher).enabled());

        mouse.stopMoveUp();
        mouse.stopMoveLeft();
        coast(mouse);
        mouse.startWheelDown();
        apply(comboWatcher, mouse);
        assertFalse(layer(comboWatcher).enabled());
        assertTrue(scroll(comboWatcher).enabled());
        assertEquals(IndicatorShape.PATH, scroll(comboWatcher).shape());
        assertEquals("", scroll(comboWatcher).text());

        mouse.pressRight();
        apply(comboWatcher, mouse);
        assertEquals(IndicatorShape.RECTANGLE, layer(comboWatcher).shape());
        assertEquals(Color.parse("#FFFFFF"), layer(comboWatcher).fillColor());
        assertEquals("<table cellspacing=0 cellpadding=0><tr><td>right click</table>",
                layer(comboWatcher).text());

        mouse.releaseRight();
        mouse.stopWheelDown();
        mouse.pressLeft();
        apply(comboWatcher, mouse);
        assertEquals("<table cellspacing=0 cellpadding=0><tr><td>left click</table>",
                layer(comboWatcher).text());

        mouse.releaseLeft();
        mouse.pressMiddle();
        apply(comboWatcher, mouse);
        assertEquals("<table cellspacing=0 cellpadding=0><tr><td>middle click</table>",
                layer(comboWatcher).text());

        mouse.releaseMiddle();
        apply(comboWatcher, mouse);
        assertFalse(layer(comboWatcher).enabled());
        assertEquals("", layer(comboWatcher).text());
    }

    /** Runs the coast out, so a later sample is not still the move that was just released. */
    private static void coast(MouseManager mouse) {
        for (int i = 0; i < 50 && (mouse.moving() || mouse.wheeling()); i++)
            mouse.update(0.05);
    }

    private static void apply(ComboWatcher comboWatcher, MouseManager mouse) {
        // The app drops a button's one-frame press flag before the indicator is drawn.
        mouse.update(0.016);
        comboWatcher.updateBuiltInVirtualKeys(new MouseState(mouse), new KeyboardState(null) {
            @Override
            public boolean pressingUnhandledKeyInCurrentMode() {
                return false;
            }
        }, new AnimationPlayer(Map.of()));
    }

    private static IndicatorLayerConfiguration layer(ComboWatcher comboWatcher) {
        return comboWatcher.getMutatedMode().indicator().layerByName().get("action-indicator");
    }

    private static IndicatorLayerConfiguration scroll(ComboWatcher comboWatcher) {
        return comboWatcher.getMutatedMode().indicator().layerByName().get("scroll-indicator");
    }
}
