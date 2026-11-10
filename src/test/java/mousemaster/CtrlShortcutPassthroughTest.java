package mousemaster;

import mousemaster.KeyboardManager.EatAndRegurgitates;
import mousemaster.platform.KeyboardController;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ctrl is not swallowed. Left Ctrl + e/d/s/f still moves; every other Ctrl chord
 * is passed through and does not run a mouse-mode command.
 */
class CtrlShortcutPassthroughTest {

    private final Instant now = Instant.parse("2020-01-01T00:00:00Z");
    private final List<Command> ranCommands = new ArrayList<>();
    private KeyboardManager keyboardManager;

    private void load(String... lines) {
        Configuration configuration = ConfigurationParser.parse(List.of(lines),
                KeyboardLayout.keyboardLayoutByShortName.get("us-qwerty"));
        ModeMap modeMap = configuration.modeMap();
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
            }
        };
        ComboWatcher comboWatcher = new ComboWatcher(commandRunner, null,
                () -> new App("test.exe"), null, () -> now, Set.of(), pressedPreconditionKeys,
                new KeyRedactor(KeyRedaction.NONE), modeMap,
                configuration.initiallySetVariables(), configuration.virtualKeys(),
                configuration.initiallyPressedVirtualKeys(),
                configuration.activeKeyboardLayout());
        comboWatcher.setModeListeners(List.of());
        comboWatcher.modeChanged(modeMap.get(Mode.IDLE_MODE_NAME));
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
        ranCommands.clear();
    }

    private void loadMouseMode() {
        load(
                "key-alias.enabledoubletap.us-qwerty=leftctrl",
                "idle-mode.double-tap-to-normal.key-alias=enabledoubletap",
                "idle-mode.double-tap-to-normal.leading-debounce-millis=0",
                "idle-mode.double-tap-to-normal.max-millis-between-taps=400",
                "idle-mode.double-tap-to-normal.target-mode=normal-mode",
                "idle-mode.press.left=+c",
                "idle-mode.press.right=+v",
                "idle-mode.press.middle=+a",
                "idle-mode.start-move.up=_{leftctrl} +e",
                "idle-mode.start-move.left=_{leftctrl} +s",
                "normal-mode.to.idle-mode=+q");
    }

    private EatAndRegurgitates press(String keyName) {
        return keyboardManager.keyEvent(new KeyEvent.PressKeyEvent(now, Key.ofName(keyName)));
    }

    private EatAndRegurgitates release(String keyName) {
        return keyboardManager.keyEvent(new KeyEvent.ReleaseKeyEvent(now, Key.ofName(keyName)));
    }

    @Test
    void doubleTapPressesAreNotEaten() {
        assertPressesNotEaten(false);
        assertPressesNotEaten(true);
    }

    private void assertPressesNotEaten(boolean limitTapDuration) {
        List<String> lines = new ArrayList<>(List.of(
                "key-alias.enabledoubletap.us-qwerty=leftctrl",
                "idle-mode.double-tap-to-normal.key-alias=enabledoubletap",
                "idle-mode.double-tap-to-normal.target-mode=normal-mode",
                "normal-mode.to.idle-mode=+q"));
        if (limitTapDuration)
            lines.add("idle-mode.double-tap-to-normal.max-millis-per-tap=150");
        Configuration configuration = ConfigurationParser.parse(lines,
                KeyboardLayout.keyboardLayoutByShortName.get("us-qwerty"));
        boolean sawPress = false;
        for (Mode mode : configuration.modeMap().modes()) {
            for (Combo combo : mode.comboMap().commandsByCombo().keySet()) {
                if (!combo.label().contains("double-tap"))
                    continue;
                for (MoveSet moveSet : combo.sequence().moveSets()) {
                    if (!(moveSet instanceof MoveSet.KeyMoveSet keys))
                        continue;
                    for (ComboMove.KeyComboMove move : keys.requiredMoves()) {
                        if (move instanceof ComboMove.PressComboMove press) {
                            sawPress = true;
                            assertFalse(press.eventMustBeEaten(), combo.label());
                        }
                    }
                }
            }
        }
        assertTrue(sawPress);
    }

    @Test
    void ctrlItselfIsNotEaten() {
        loadMouseMode();
        assertFalse(press("leftctrl").mustBeEaten());
        assertFalse(release("leftctrl").mustBeEaten());
    }

    @Test
    void ctrlPlusOtherKeysReachTheApp() {
        loadMouseMode();
        assertTrue(press("c").mustBeEaten());
        assertEquals(List.of(new Command.PressLeft()), ranCommands);
        release("c");
        ranCommands.clear();

        assertFalse(press("leftctrl").mustBeEaten());
        assertFalse(press("c").mustBeEaten());
        assertFalse(press("v").mustBeEaten());
        assertFalse(press("a").mustBeEaten());
        assertEquals(List.of(), ranCommands);
        assertFalse(keyboardManager.pressingUnhandledKeyInCurrentMode());
        assertTrue(keyboardManager.isPressedKeyNotEaten(Key.ofName("c")));
        assertFalse(release("c").mustBeEaten());
        assertFalse(release("v").mustBeEaten());
        assertFalse(release("a").mustBeEaten());
        assertFalse(release("leftctrl").mustBeEaten());
    }

    @Test
    void leftCtrlPlusMoveKeysStillMove() {
        loadMouseMode();
        press("e");
        assertFalse(ranCommands.contains(new Command.StartMoveUp()));
        release("e");

        press("leftctrl");
        assertTrue(press("e").mustBeEaten());
        assertTrue(press("s").mustBeEaten());
        assertTrue(ranCommands.contains(new Command.StartMoveUp()));
        assertTrue(ranCommands.contains(new Command.StartMoveLeft()));
    }

    @Test
    void moveKeyStillWorksWhileAnotherCtrlChordIsHeld() {
        loadMouseMode();
        press("leftctrl");
        assertFalse(press("c").mustBeEaten());
        assertTrue(press("e").mustBeEaten());
        assertEquals(List.of(new Command.StartMoveUp()), ranCommands);
    }

    @Test
    void rightCtrlDoesNotMoveAndDoesNotEatTheLetter() {
        loadMouseMode();
        press("rightctrl");
        assertFalse(press("e").mustBeEaten());
        assertFalse(press("c").mustBeEaten());
        assertEquals(List.of(), ranCommands);
    }

    @Test
    void otherCtrlChordCancelsDoubleTapButARealDoubleTapStillActivates() {
        loadMouseMode();
        press("leftctrl");
        press("c");
        release("c");
        release("leftctrl");
        press("leftctrl");
        assertFalse(ranCommands.stream().anyMatch(command -> command instanceof Command.SwitchMode));
        release("leftctrl");
        press("leftctrl");
        release("leftctrl");
        press("leftctrl");
        assertTrue(ranCommands.stream().anyMatch(command ->
                command.equals(new Command.SwitchMode("normal-mode"))));
    }

}
