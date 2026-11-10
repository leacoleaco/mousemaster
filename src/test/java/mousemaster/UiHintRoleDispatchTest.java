package mousemaster;

import mousemaster.platform.MouseController;
import mousemaster.platform.Overlay;
import mousemaster.platform.UiAutomation;
import mousemaster.platform.UiAutomation.UiElement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In the shipped configuration, what choosing a UI hint does depends on the role of its
 * element: a click, a click that leaves to idle, the key mode of the control, or nothing.
 */
class UiHintRoleDispatchTest {

    private static final UiElement BUTTON = new UiElement(new Rectangle(100, 100, 80, 30),
            UiElement.Role.CLICK, UiElement.Control.BUTTON);
    private static final UiElement CHECK_BOX = new UiElement(new Rectangle(100, 200, 20, 20),
            UiElement.Role.CLICK, UiElement.Control.CHECK_BOX);
    private static final UiElement EDIT = new UiElement(new Rectangle(300, 100, 200, 30),
            UiElement.Role.INPUT, UiElement.Control.EDIT);
    private static final UiElement VERTICAL_SCROLL_BAR =
            new UiElement(new Rectangle(1900, 100, 17, 800), UiElement.Role.WIDGET,
                    UiElement.Control.SCROLL_BAR);
    private static final UiElement HORIZONTAL_SCROLL_BAR =
            new UiElement(new Rectangle(100, 1000, 1000, 17), UiElement.Role.WIDGET,
                    UiElement.Control.SCROLL_BAR);
    private static final UiElement SLIDER = new UiElement(new Rectangle(700, 50, 200, 20),
            UiElement.Role.WIDGET, UiElement.Control.SLIDER);
    private static final UiElement PANE = new UiElement(new Rectangle(600, 400, 300, 300),
            UiElement.Role.OTHER, UiElement.Control.PANE);
    /** An image the application says reacts to a click. */
    private static final UiElement CLICKABLE_IMAGE =
            new UiElement(new Rectangle(1200, 400, 64, 64), UiElement.Role.CLICK,
                    UiElement.Control.IMAGE);

    private final List<Command> ranCommands = new ArrayList<>();
    private Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private ModeMap modeMap;
    private MouseManager mouseManager;
    private HintManager hintManager;
    private ComboWatcher comboWatcher;
    private String currentModeName;

    @BeforeEach
    void start() throws IOException {
        Configuration configuration = ConfigurationParser.parse(
                PropertiesReader.readPropertiesFile(Files.newBufferedReader(
                        Path.of("configuration", "mousemaster.properties"))),
                KeyboardLayout.keyboardLayoutByShortName.get("us-qwerty"));
        modeMap = configuration.modeMap();
        ScreenManager screenManager = new ScreenManager(
                () -> Set.of(new Screen(new Rectangle(0, 0, 1920, 1080), 96, 1)));
        mouseManager = new MouseManager(screenManager, proxy(MouseController.class));
        Future<List<UiElement>> found = CompletableFuture.completedFuture(
                List.of(BUTTON, CHECK_BOX, EDIT, VERTICAL_SCROLL_BAR, HORIZONTAL_SCROLL_BAR,
                        SLIDER, PANE, CLICKABLE_IMAGE));
        Overlay overlay = (Overlay) Proxy.newProxyInstance(Overlay.class.getClassLoader(),
                new Class<?>[] {Overlay.class}, (proxy, method, args) ->
                        method.getReturnType() == Rectangle.class ?
                                new Rectangle(0, 0, 1920, 1080) :
                                method.getReturnType() == boolean.class ? false : null);
        hintManager = new HintManager(configuration.positionHistoryConfigurationByName(),
                screenManager, mouseManager, overlay, new UiAutomation() {
                    @Override
                    public Future<List<UiElement>> startFindActiveWindowUiElements() {
                        return found;
                    }

                    @Override
                    public Future<List<UiElement>> startFindUiElementsInArea(Rectangle area) {
                        return found;
                    }
                }, () -> new App("test.exe"), new KeyRedactor(KeyRedaction.NONE), null);
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
        CommandRunner commandRunner = new CommandRunner(null, null, null) {
            @Override
            public boolean runningAtomicCommand() {
                return false;
            }

            @Override
            public void run(Command command, Key eventKey) {
                ranCommands.add(command);
                switch (command) {
                    case Command.SelectHintKey c -> hintManager.selectHintKey(eventKey);
                    case Command.FilterUiHints filter ->
                            hintManager.filterUiHints(filter.role());
                    case Command.SwitchMode switchMode -> switchMode(switchMode.modeName());
                    case Command.StartWheelDown c -> mouseManager.startWheelDown();
                    case Command.StopWheelDown c -> mouseManager.stopWheelDown();
                    default -> {}
                }
            }
        };
        comboWatcher = new ComboWatcher(commandRunner, hintManager,
                () -> new App("test.exe"), screenManager, () -> now,
                unpressedPreconditionKeys, pressedPreconditionKeys,
                new KeyRedactor(KeyRedaction.NONE), modeMap,
                configuration.initiallySetVariables(), configuration.virtualKeys(),
                configuration.initiallyPressedVirtualKeys(),
                configuration.activeKeyboardLayout());
        comboWatcher.setModeListeners(List.of(hintManager));
        switchMode(Mode.IDLE_MODE_NAME);
        tick();
        switchMode("normal-mode");
        tick();
        tap("f");
        assertEquals("ui-hint-mode", currentModeName);
        hintManager.completePendingUiHintQuery();
        tick();
        ranCommands.clear();
    }

    private void switchMode(String modeName) {
        currentModeName = modeName;
        mouseManager.modeChanged(modeMap.get(modeName));
        comboWatcher.modeChanged(modeMap.get(modeName));
    }

    /** One iteration of the main loop: the combos run before the built-in keys are refreshed. */
    private void tick() {
        now = now.plusMillis(10);
        comboWatcher.update(0.01);
        comboWatcher.updateBuiltInVirtualKeys(new MouseState(mouseManager),
                new KeyboardState(null) {
                    @Override
                    public boolean pressingUnhandledKeyInCurrentMode() {
                        return false;
                    }
                }, new AnimationPlayer(Map.of()));
    }

    private void ticks(int count) {
        for (int i = 0; i < count; i++)
            tick();
    }

    private void press(Key key) {
        now = now.plusMillis(30);
        comboWatcher.keyEvent(new KeyEvent.PressKeyEvent(now, key));
    }

    private void release(Key key) {
        now = now.plusMillis(30);
        comboWatcher.keyEvent(new KeyEvent.ReleaseKeyEvent(now, key));
    }

    private void press(String keyName) {
        press(Key.ofName(keyName));
    }

    private void release(String keyName) {
        release(Key.ofName(keyName));
    }

    private void tap(String keyName) {
        press(keyName);
        release(keyName);
    }

    /** Types the label of the hint on that element, and lets the modes it goes through run. */
    private void choose(UiElement element) {
        Hint hint = hintManager.hintMesh().hints().stream()
                               .filter(candidate -> element.rectangle()
                                                           .equals(candidate.elementRectangle()))
                               .findFirst().orElseThrow();
        for (Key key : hint.keySequence()) {
            press(key);
            release(key);
        }
        ticks(10);
    }

    private List<Command> ran(Class<?>... commandClasses) {
        return ranCommands.stream()
                          .filter(command -> List.of(commandClasses)
                                                 .contains(command.getClass()))
                          .toList();
    }

    @Test
    void aClickHintIsClickedAndNormalModeIsBack() {
        choose(CHECK_BOX);
        assertEquals(List.of(new Command.SwitchMode("ui-hint-selected-mode"),
                        new Command.SwitchMode("click-hint-mode"), new Command.PressLeft(),
                        new Command.ReleaseLeft(), new Command.SwitchMode("normal-mode")),
                ran(Command.SwitchMode.class, Command.PressLeft.class,
                        Command.ReleaseLeft.class));
        assertEquals("normal-mode", currentModeName);
    }

    @Test
    void aButtonIsPointedAtWithoutAClick() {
        choose(BUTTON);
        assertEquals(List.of(new Command.SwitchMode("ui-hint-selected-mode"),
                        new Command.SwitchMode("point-button-mode"),
                        new Command.SwitchMode("normal-mode")),
                ran(Command.SwitchMode.class, Command.PressLeft.class,
                        Command.ReleaseLeft.class));
        assertEquals(List.of(), ran(Command.PressLeft.class, Command.ReleaseLeft.class));
        assertEquals("normal-mode", currentModeName);
        assertEquals(140, mouseManager.jumpEndX());
        assertEquals(115, mouseManager.jumpEndY());
    }

    @Test
    void aHintOfAControlTheApplicationSaysReactsToAClickIsClicked() {
        choose(CLICKABLE_IMAGE);
        assertEquals(List.of(new Command.PressLeft(), new Command.ReleaseLeft()),
                ran(Command.PressLeft.class, Command.ReleaseLeft.class));
        assertEquals("normal-mode", currentModeName);
    }

    @Test
    void aClickHintIsClickedWhileItsLastKeyIsStillHeld() {
        Hint hint = hintManager.hintMesh().hints().stream()
                               .filter(candidate -> CHECK_BOX.rectangle().equals(
                                       candidate.elementRectangle()))
                               .findFirst().orElseThrow();
        List<Key> keys = hint.keySequence();
        for (Key key : keys.subList(0, keys.size() - 1)) {
            press(key);
            release(key);
        }
        press(keys.getLast());
        ticks(10);
        assertEquals(List.of(new Command.PressLeft(), new Command.ReleaseLeft()),
                ran(Command.PressLeft.class, Command.ReleaseLeft.class));
        assertEquals("normal-mode", currentModeName);
    }

    @Test
    void anInputHintIsClickedAndMousemasterLeavesToIdle() {
        choose(EDIT);
        assertEquals(List.of(new Command.SwitchMode("ui-hint-selected-mode"),
                        new Command.SwitchMode("click-input-mode"), new Command.PressLeft(),
                        new Command.ReleaseLeft(), new Command.SwitchMode("idle-mode")),
                ran(Command.SwitchMode.class, Command.PressLeft.class,
                        Command.ReleaseLeft.class));
        assertEquals("idle-mode", currentModeName);
    }

    @Test
    void anOtherHintIsOnlyPointedAt() {
        choose(PANE);
        assertEquals(List.of(), ran(Command.PressLeft.class, Command.ReleaseLeft.class));
        assertEquals("normal-mode", currentModeName);
    }

    @Test
    void aWidgetWithNoKeyModeOfItsOwnIsOnlyPointedAt() {
        choose(SLIDER);
        assertEquals(List.of(), ran(Command.PressLeft.class, Command.ReleaseLeft.class));
        assertEquals("normal-mode", currentModeName);
    }

    @Test
    void aVerticalScrollBarIsScrolledWithTheUpAndDownKeys() {
        choose(VERTICAL_SCROLL_BAR);
        assertEquals("scroll-bar-mode", currentModeName);
        assertEquals(List.of(), ran(Command.PressLeft.class));
        press("j");
        tick();
        release("j");
        press("k");
        tick();
        release("k");
        tap("h");
        tap("l");
        assertEquals(List.of(new Command.StartWheelDown(), new Command.StopWheelDown(),
                        new Command.StartWheelUp(), new Command.StopWheelUp()),
                ran(Command.StartWheelDown.class, Command.StopWheelDown.class,
                        Command.StartWheelUp.class, Command.StopWheelUp.class,
                        Command.StartWheelLeft.class, Command.StartWheelRight.class));
        assertEquals("scroll-bar-mode", currentModeName);
    }

    @Test
    void aHorizontalScrollBarIsScrolledWithTheLeftAndRightKeys() {
        choose(HORIZONTAL_SCROLL_BAR);
        assertEquals("scroll-bar-mode", currentModeName);
        tap("j");
        tap("k");
        press("l");
        tick();
        release("l");
        press("h");
        tick();
        release("h");
        assertEquals(List.of(new Command.StartWheelRight(), new Command.StopWheelRight(),
                        new Command.StartWheelLeft(), new Command.StopWheelLeft()),
                ran(Command.StartWheelRight.class, Command.StopWheelRight.class,
                        Command.StartWheelLeft.class, Command.StopWheelLeft.class,
                        Command.StartWheelUp.class, Command.StartWheelDown.class));
    }

    @Test
    void thePanelAtThePointerListsTheKeysOfTheChosenScrollBar() {
        choose(VERTICAL_SCROLL_BAR);
        assertTrue(keysPanel().enabled());
        assertTrue(keysPanel().text().contains("k 上"), keysPanel().text());
        assertFalse(keysPanel().text().contains("左"), keysPanel().text());
        press("j");
        ticks(2);
        assertTrue(new MouseState(mouseManager).wheeling());
        assertFalse(comboWatcher.getMutatedMode().indicator().layerByName()
                                .get("scroll-indicator").enabled(),
                "the arrow of normal mode is not drawn over the panel");
        release("j");
        tap("esc");
        tap("f");
        hintManager.completePendingUiHintQuery();
        tick();
        choose(HORIZONTAL_SCROLL_BAR);
        assertTrue(keysPanel().text().contains("h 左"), keysPanel().text());
        assertFalse(keysPanel().text().contains("上"), keysPanel().text());
    }

    private IndicatorLayerConfiguration keysPanel() {
        return comboWatcher.getMutatedMode().indicator().layerByName().get("keys-indicator");
    }

    @Test
    void aScrollBarIsScrolledWhileTheFastKeyIsHeld() {
        choose(VERTICAL_SCROLL_BAR);
        press("b");
        tick();
        press("j");
        tick();
        assertEquals(List.of(new Command.StartWheelDown()), ran(Command.StartWheelDown.class));
    }

    @Test
    void escLeavesTheScrollBarForNormalModeAndTheNextHintIsDispatchedAgain() {
        choose(VERTICAL_SCROLL_BAR);
        tap("esc");
        assertEquals("normal-mode", currentModeName);
        tap("f");
        hintManager.completePendingUiHintQuery();
        tick();
        ranCommands.clear();
        choose(CHECK_BOX);
        assertEquals(List.of(new Command.PressLeft(), new Command.ReleaseLeft()),
                ran(Command.PressLeft.class, Command.ReleaseLeft.class));
        assertEquals("normal-mode", currentModeName);
    }

    @Test
    void theFilterKeysKeepOneRole() {
        tap("b");
        assertEquals(Set.of(UiElement.Role.WIDGET), roles());
        tap("g");
        assertEquals(Set.of(UiElement.Role.CLICK), roles());
        tap("t");
        assertEquals(Set.of(UiElement.Role.INPUT), roles());
        assertEquals("ui-hint-mode", currentModeName);
        assertFalse(hintManager.hintMesh().hints().isEmpty());
        assertTrue(ran(Command.PressLeft.class).isEmpty());
    }

    private Set<UiElement.Role> roles() {
        Set<UiElement.Role> roles = new HashSet<>();
        for (Hint hint : hintManager.hintMesh().hints())
            roles.add(hint.role());
        return roles;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? false : null);
    }

}
