package mousemaster;

import mousemaster.KeyboardManager.EatAndRegurgitates;
import mousemaster.platform.KeyboardController;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In activation mode an unbound English letter or symbol is eaten, and a note is shown
 * until a command key is pressed or the note's animation ends. Digits, Enter, and arrows
 * still reach the focused program.
 */
class UnhandledKeySwallowTest {

    private static final String TIP = "activemodetipanimation";

    private final List<Command> ranCommands = new ArrayList<>();
    private KeyboardManager keyboardManager;
    private ComboWatcher comboWatcher;
    private ModeMap modeMap;
    private AnimationPlayer animations;
    private Instant now = Instant.parse("2020-01-01T00:00:00Z");
    private boolean applyModeSwitches;

    @Test
    void shippedModesSwallowNonFunctionKeysOnlyWhileActivated() throws Exception {
        loadShipped();
        assertTrue(modeMap.get("normal-mode").eatUnhandledKeyPress());
        assertEquals(TIP, modeMap.get("normal-mode").unhandledKeyPressAnimation());
        assertTrue(modeMap.get("zoom-mode").eatUnhandledKeyPress());
        assertTrue(modeMap.get("ui-hint-mode").eatUnhandledKeyPress());
        assertTrue(modeMap.get("vision-hint-mode").eatUnhandledKeyPress());
        assertTrue(modeMap.get("scroll-bar-mode").eatUnhandledKeyPress());
        assertFalse(modeMap.get("idle-mode").eatUnhandledKeyPress());
        assertNull(modeMap.get("idle-mode").unhandledKeyPressAnimation());
        assertFalse(modeMap.get("hint1-mode").eatUnhandledKeyPress());

        EatAndRegurgitates w = press("w");
        assertTrue(w.mustBeEaten());
        assertTrue(w.regurgitates().isEmpty());
        drain();
        assertTrue(animations.running(TIP));
        assertTrue(release("w").mustBeEaten());

        press("w");
        EatAndRegurgitates move = press("k");
        assertTrue(move.mustBeEaten());
        assertTrue(ran(new Command.StartMoveUp()));
        assertTrue(move.regurgitates().stream()
                          .noneMatch(regurgitate -> regurgitate.key().equals(Key.ofName("w"))));
        drain();
        assertFalse(animations.running(TIP));
        release("w");
        release("k");
        ranCommands.clear();

        press("w");
        drain();
        animations.update(0.9);
        assertTrue(animations.running(TIP));
        release("w");
        press("w");
        drain();
        animations.update(0.2);
        assertTrue(animations.running(TIP));
        release("w");

        press("w");
        release("w");
        press("space");
        drain();
        assertFalse(animations.running(TIP));
        assertTrue(ran(new Command.PressLeft()));
        release("space");
        ranCommands.clear();

        press("leftctrl");
        assertFalse(press("w").mustBeEaten());
        drain();
        assertFalse(animations.running(TIP));
        release("w");
        release("leftctrl");

        assertFalse(press("enter").mustBeEaten());
        drain();
        assertFalse(animations.running(TIP));
        release("enter");
        assertFalse(press("leftarrow").mustBeEaten());
        assertFalse(press("1").mustBeEaten());
        assertFalse(press("backspace").mustBeEaten());
        release("leftarrow");
        release("1");
        release("backspace");

        EatAndRegurgitates comma = press(",");
        assertTrue(comma.mustBeEaten());
        drain();
        assertTrue(animations.running(TIP));
        release(",");

        ranCommands.clear();
        EatAndRegurgitates zero = press("0");
        assertTrue(zero.mustBeEaten());
        assertTrue(ran(new Command.PressMiddle()));
        drain();
        assertFalse(animations.running(TIP));
        release("0");

        comboWatcher.modeChanged(modeMap.get("idle-mode"));
        ranCommands.clear();
        assertFalse(press("w").mustBeEaten());
        drain();
        assertFalse(animations.running(TIP));
    }

    @Test
    void functionLettersDoNotShowTheActivationTip() throws Exception {
        applyModeSwitches = true;
        loadShipped();

        press("w");
        drain();
        assertTrue(animations.running(TIP));
        release("w");

        animations.stop(TIP);
        ranCommands.clear();
        EatAndRegurgitates q = press("q");
        drain();
        assertFalse(animations.running(TIP));
        assertTrue(ran(new Command.SwitchMode("idle-mode")));
        release("q");

        comboWatcher.modeChanged(modeMap.get("normal-mode"));
        animations.stop(TIP);
        ranCommands.clear();
        press("w");
        drain();
        assertTrue(animations.running(TIP));
        release("w");
        EatAndRegurgitates f = press("f");
        drain();
        assertFalse(animations.running(TIP));
        assertTrue(ran(new Command.SwitchMode("ui-hint-mode")));
        release("f");

        comboWatcher.modeChanged(modeMap.get("normal-mode"));
        animations.stop(TIP);
        ranCommands.clear();
        assertFalse(press("c").mustBeEaten());
        drain();
        assertFalse(animations.running(TIP));
        release("c");

        animations.stop(TIP);
        press("leftarrow");
        ranCommands.clear();
        press("q");
        drain();
        assertFalse(animations.running(TIP));
        assertTrue(ran(new Command.SwitchMode("idle-mode")));
        release("q");
        release("leftarrow");
    }

    @Test
    void withoutTheFlagANonFunctionKeyReachesTheApp() {
        load(List.of(
                "idle-mode.to.normal-mode=+q",
                "normal-mode.to.idle-mode=+q",
                "normal-mode.start-move.up=+k"));
        assertFalse(press("w").mustBeEaten());
        release("w");
        assertTrue(press("k").mustBeEaten());
        assertTrue(ran(new Command.StartMoveUp()));
    }

    private void loadShipped() throws Exception {
        load(PropertiesReader.readPropertiesFile(Files.newBufferedReader(
                Path.of("configuration/mousemaster.properties"))));
    }

    private void load(List<String> lines) {
        Configuration configuration = ConfigurationParser.parse(lines,
                KeyboardLayout.keyboardLayoutByShortName.get("us-qwerty"));
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
                if (applyModeSwitches && command instanceof Command.SwitchMode switchMode)
                    comboWatcher.modeChanged(modeMap.get(switchMode.modeName()));
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
        animations = new AnimationPlayer(Map.of(TIP,
                new AnimationConfiguration(Duration.ofSeconds(1), 1, AnimationDirection.FORWARD,
                        false)));
        comboWatcher.modeChanged(modeMap.get("normal-mode"));
        ranCommands.clear();
    }

    private void drain() {
        keyboardManager.drainUnhandledKeyPressTips(animations);
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

}
