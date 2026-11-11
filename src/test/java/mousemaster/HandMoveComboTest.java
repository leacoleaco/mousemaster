package mousemaster;

import mousemaster.KeyboardManager.EatAndRegurgitates;
import mousemaster.platform.KeyboardController;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hold A and e/d/s/f move the pointer the way k/j/h/l do. Release A and e/d scroll
 * again, and f opens hints again.
 */
class HandMoveComboTest {

    private final List<Command> ranCommands = new ArrayList<>();
    private KeyboardManager keyboardManager;
    private ComboWatcher comboWatcher;
    private ModeMap modeMap;
    private Instant now = Instant.parse("2020-01-01T00:00:00Z");
    private boolean followModeSwitches;

    private void load() throws Exception {
        load(false);
    }

    private void load(boolean followModeSwitches) throws Exception {
        this.followModeSwitches = followModeSwitches;
        Configuration configuration = ConfigurationParser.parse(
                PropertiesReader.readPropertiesFile(Files.newBufferedReader(
                        Path.of("configuration/mousemaster.properties"))),
                KeyboardLayout.keyboardLayout("00000409", null));
        modeMap = configuration.modeMap();
        Set<Key> pressedPreconditionKeys = new HashSet<>();
        for (Mode mode : modeMap.modes())
            for (Combo combo : mode.comboMap().commandsByCombo().keySet())
                pressedPreconditionKeys.addAll(combo.precondition()
                                                    .keyPrecondition()
                                                    .pressedKeyPrecondition()
                                                    .allKeys());
        CommandRunner commandRunner = new CommandRunner(null, null, null) {
            @Override
            public boolean runningAtomicCommand() {
                return false;
            }

            @Override
            public void run(Command command, Key eventKey) {
                ranCommands.add(command);
                if (!followModeSwitches)
                    return;
                String modeName = switch (command) {
                    case Command.SwitchMode switchMode -> switchMode.modeName();
                    case Command.ActivateSelectedWindow activate -> activate.modeAfter();
                    default -> null;
                };
                if (modeName == null)
                    return;
                Mode mode = modeMap.get(modeName);
                if (mode != null)
                    comboWatcher.modeChanged(mode);
            }
        };
        comboWatcher = new ComboWatcher(commandRunner, null,
                () -> new App("test.exe"), null, () -> now, Set.of(), pressedPreconditionKeys,
                new KeyRedactor(KeyRedaction.NONE), modeMap,
                configuration.initiallySetVariables(), configuration.virtualKeys(),
                configuration.initiallyPressedVirtualKeys(),
                configuration.activeKeyboardLayout());
        comboWatcher.setModeListeners(List.of());
        KeyboardController keyboard = new KeyboardController() {
            @Override
            public void update(double delta) {
            }

            @Override
            public void reset() {
            }

            @Override
            public void sendInputMoves(List<ResolvedMacroMove> moves, boolean startRepeat) {
            }

            @Override
            public void keyPressedNotEaten(Key key) {
            }

            @Override
            public void keyReleasedNotEaten(Key key) {
            }

            @Override
            public void recordEarlyReleaseForQueuedPress(Key key) {
            }

            @Override
            public void clearEarlyReleaseForQueuedPress(Key key) {
            }
        };
        keyboardManager = new KeyboardManager(comboWatcher, null,
                new KeyRegurgitator(keyboard, new KeyRedactor(KeyRedaction.NONE)), () -> now);
        keyboardManager.setMacroPlayer(new MacroPlayer(() -> now, comboWatcher, keyboardManager,
                keyboard, new KeyRedactor(KeyRedaction.NONE),
                configuration.activeKeyboardLayout()));
        comboWatcher.modeChanged(modeMap.get("normal-mode"));
        ranCommands.clear();
    }

    private EatAndRegurgitates press(String keyName) {
        now = now.plusMillis(30);
        return keyboardManager.keyEvent(new KeyEvent.PressKeyEvent(now, Key.ofName(keyName)));
    }

    private EatAndRegurgitates release(String keyName) {
        now = now.plusMillis(30);
        return keyboardManager.keyEvent(new KeyEvent.ReleaseKeyEvent(now, Key.ofName(keyName)));
    }

    private boolean ran(Command command) {
        return ranCommands.contains(command);
    }

    @Test
    void holdingATurnsEdsfIntoMovement() throws Exception {
        load();
        assertTrue(press("a").mustBeEaten());
        assertFalse(ran(new Command.StartMoveUp()));

        assertTrue(press("e").mustBeEaten());
        assertTrue(ran(new Command.StartMoveUp()));
        assertFalse(ran(new Command.StartWheelUp()));
        ranCommands.clear();
        EatAndRegurgitates releaseE = release("e");
        assertTrue(releaseE.mustBeEaten());
        assertTrue(releaseE.regurgitates().isEmpty());
        assertTrue(ran(new Command.StopMoveUp()));
        release("a");

        ranCommands.clear();
        assertTrue(press("d").mustBeEaten());
        assertFalse(ran(new Command.StartMoveDown()));
        release("d");
        press("a");
        ranCommands.clear();
        assertTrue(press("d").mustBeEaten());
        assertTrue(ran(new Command.StartMoveDown()));
        assertFalse(ran(new Command.StartWheelDown()));
        release("d");
        release("a");

        ranCommands.clear();
        assertTrue(press("s").mustBeEaten());
        assertTrue(ran(new Command.SwitchMode("window-switch-mode")));
        assertFalse(ran(new Command.StartMoveLeft()));
        release("s");
        load();
        press("a");
        ranCommands.clear();
        assertTrue(press("s").mustBeEaten());
        assertTrue(ran(new Command.StartMoveLeft()));
        assertFalse(ran(new Command.SwitchMode("window-switch-mode")));
        release("s");
        release("a");

        ranCommands.clear();
        assertTrue(press("f").mustBeEaten());
        assertTrue(ran(new Command.SwitchMode("ui-hint-mode")));
        assertFalse(ran(new Command.StartMoveRight()));
        release("f");

        load();
        press("a");
        ranCommands.clear();
        assertTrue(press("f").mustBeEaten());
        assertTrue(ran(new Command.StartMoveRight()));
        assertFalse(ran(new Command.SwitchMode("ui-hint-mode")));
        assertFalse(ran(new Command.SwitchMode("hint1-mode")));
    }

    @Test
    void releasingAStopsHandMovementAndRestoresTheWheel() throws Exception {
        load();
        press("e");
        assertTrue(ran(new Command.StartWheelUp()));
        ranCommands.clear();
        press("a");
        assertTrue(ran(new Command.StopWheelUp()));
        assertTrue(ran(new Command.StartMoveUp()));
        ranCommands.clear();
        EatAndRegurgitates releaseA = release("a");
        assertTrue(releaseA.mustBeEaten());
        assertTrue(releaseA.regurgitates().isEmpty());
        assertTrue(ran(new Command.StopMoveUp()));
        ranCommands.clear();
        EatAndRegurgitates releaseE = release("e");
        assertTrue(releaseE.mustBeEaten());
        assertTrue(releaseE.regurgitates().isEmpty());
        assertFalse(ran(new Command.StartWheelUp()));
        ranCommands.clear();
        press("e");
        assertTrue(ran(new Command.StartWheelUp()));
        assertFalse(ran(new Command.StartMoveUp()));
    }

    @Test
    void kjhlStillMovesAndEitherKeyKeepsTheDirection() throws Exception {
        load();
        press("k");
        assertTrue(ran(new Command.StartMoveUp()));
        press("a");
        press("e");
        ranCommands.clear();
        release("k");
        assertFalse(ran(new Command.StopMoveUp()));
        release("e");
        assertTrue(ran(new Command.StopMoveUp()));
    }

    @Test
    void clicksStillWorkWhileAIsHeld() throws Exception {
        load();
        press("a");
        press("e");
        ranCommands.clear();
        assertTrue(press("g").mustBeEaten());
        assertTrue(ran(new Command.PressLeft()));
    }

    @Test
    void ctrlStillPassesEdsfThrough() throws Exception {
        load();
        press("a");
        press("leftctrl");
        ranCommands.clear();
        assertFalse(press("e").mustBeEaten());
        assertFalse(ran(new Command.StartMoveUp()));
        assertFalse(ran(new Command.StartWheelUp()));
    }

    @Test
    void sAloneListsWindowsAndBAloneOpensTheSystemMenu() throws Exception {
        load();
        assertTrue(press("s").mustBeEaten());
        assertTrue(ran(new Command.SwitchMode("window-switch-mode")));
        assertFalse(ran(new Command.StartMoveLeft()));

        load();
        assertTrue(press("b").mustBeEaten());
        int systemMenu = -1;
        int leaveMouseMode = -1;
        for (int i = 0; i < ranCommands.size(); i++) {
            Command command = ranCommands.get(i);
            if (command instanceof Command.MacroCommand macro &&
                macro.macro().name().equals("systemmenu"))
                systemMenu = i;
            if (command.equals(new Command.SwitchMode("idle-mode")))
                leaveMouseMode = i;
        }
        assertTrue(systemMenu >= 0);
        assertTrue(leaveMouseMode > systemMenu);
        comboWatcher.modeChanged(modeMap.get("idle-mode"));
        EatAndRegurgitates releaseB = release("b");
        assertTrue(releaseB.mustBeEaten());
        assertTrue(releaseB.regurgitates().isEmpty());

        load();
        press("k");
        ranCommands.clear();
        press("b");
        assertFalse(ranCommands.stream().anyMatch(command ->
                command instanceof Command.MacroCommand macro &&
                macro.macro().name().equals("systemmenu")));
    }

    @Test
    void windowMenuMovesWithEAndDAndJumpsWithG() throws Exception {
        load();
        comboWatcher.modeChanged(modeMap.get("window-switch-mode"));
        ranCommands.clear();
        assertTrue(press("e").mustBeEaten());
        assertTrue(ran(new Command.SelectWindow(-1)));
        assertTrue(press("d").mustBeEaten());
        assertTrue(ran(new Command.SelectWindow(1)));
        ranCommands.clear();
        assertTrue(press("k").mustBeEaten());
        assertTrue(ran(new Command.SelectWindow(-1)));
        assertTrue(press("j").mustBeEaten());
        assertTrue(ran(new Command.SelectWindow(1)));
        release("k");
        release("j");
        release("e");
        release("d");
        ranCommands.clear();
        assertTrue(press("g").mustBeEaten());
        assertTrue(ran(new Command.ActivateSelectedWindow("center-on-active-window-mode")));
        release("g");
        assertTrue(press("esc").mustBeEaten());
        assertTrue(ran(new Command.SwitchMode("normal-mode")));
    }

    @Test
    void spaceInTheWindowListStaysInNormalMode() throws Exception {
        load(true);
        comboWatcher.modeChanged(modeMap.get("window-switch-mode"));
        ranCommands.clear();
        assertTrue(press("space").mustBeEaten());
        assertTrue(ran(new Command.ActivateSelectedWindow("window-switch-space-mode")));
        ranCommands.clear();
        EatAndRegurgitates releaseSpace = release("space");
        assertTrue(releaseSpace.mustBeEaten());
        assertTrue(releaseSpace.regurgitates().isEmpty());
        assertTrue(ran(new Command.SwitchMode("normal-mode")));
        assertFalse(ran(new Command.SwitchMode("idle-mode")));
    }

    @Test
    void zoomModeUsesTheSameHandMovement() throws Exception {
        load();
        comboWatcher.modeChanged(modeMap.get("zoom-mode"));
        ranCommands.clear();
        assertTrue(press("a").mustBeEaten());
        assertTrue(press("e").mustBeEaten());
        assertTrue(ran(new Command.StartMoveUp()));
        assertFalse(ran(new Command.StartWheelUp()));
    }
}
