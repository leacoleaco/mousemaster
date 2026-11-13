package mousemaster;

import mousemaster.KeyboardManager.EatAndRegurgitates;
import mousemaster.platform.KeyboardController;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Left Alt tapped twice opens accessibility hints from idle and from normal mode.
 * The taps stay out of Windows until it is clear the second tap is not coming.
 */
class IdleDoubleTapUiHintTest {

    private Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final List<Command> ranCommands = new ArrayList<>();
    private final List<String> sentToOs = new ArrayList<>();
    private KeyboardManager keyboardManager;

    @Test
    void shippedComboEatsBothLeftAltPresses() throws IOException {
        Configuration configuration = shippedConfiguration();
        assertEatenLeftAltDoubleTap(configuration.modeMap().get(Mode.IDLE_MODE_NAME));
        assertEatenLeftAltDoubleTap(configuration.modeMap().get("normal-mode"));
    }

    private static void assertEatenLeftAltDoubleTap(Mode mode) {
        boolean sawPress = false;
        for (var entry : mode.comboMap().commandsByCombo().entrySet()) {
            if (!entry.getValue().contains(new Command.SwitchMode("ui-hint-mode")))
                continue;
            for (MoveSet moveSet : entry.getKey().sequence().moveSets()) {
                if (!(moveSet instanceof MoveSet.KeyMoveSet keys))
                    continue;
                for (ComboMove.KeyComboMove move : keys.requiredMoves()) {
                    if (move instanceof ComboMove.PressComboMove press &&
                        Key.ofName("leftalt").equals(press.keyOrAlias().key())) {
                        sawPress = true;
                        assertTrue(press.eventMustBeEaten(), entry.getKey().toString());
                    }
                }
            }
        }
        assertTrue(sawPress, mode.name());
    }

    @Test
    void doubleTapLeftAltEntersUiHintWithoutSendingAlt() throws IOException {
        loadShipped();
        tapLeftAlt(40);
        advance(50);
        tapLeftAlt(40);
        assertTrue(ranCommands.contains(new Command.SwitchMode("ui-hint-mode")),
                ranCommands::toString);
        assertEquals(List.of(), sentToOs);
    }

    @Test
    void singleLeftAltTapIsReplayedAfterTheSecondTapWindow() throws IOException {
        loadShipped();
        tapLeftAlt(40);
        assertEquals(List.of(), sentToOs);
        assertFalse(ranCommands.contains(new Command.SwitchMode("ui-hint-mode")));
        advance(401);
        keyboardManager.update(0.05);
        assertEquals(List.of("+leftalt", "-leftalt"), sentToOs);
    }

    @Test
    void doubleTapLeftAltFromNormalModeEntersUiHint() throws IOException {
        loadShipped("normal-mode");
        tapLeftAlt(40);
        advance(50);
        tapLeftAlt(40);
        assertTrue(ranCommands.contains(new Command.SwitchMode("ui-hint-mode")),
                ranCommands::toString);
        assertEquals(List.of(), sentToOs);
    }

    @Test
    void altChordInNormalModeDoesNotEnterUiHint() throws IOException {
        loadShipped("normal-mode");
        assertTrue(press("leftalt").mustBeEaten());
        advance(30);
        assertFalse(press("tab").mustBeEaten());
        assertTrue(ranCommands.contains(new Command.SwitchMode("begin-alt-tab-mode")),
                ranCommands::toString);
        assertFalse(ranCommands.contains(new Command.SwitchMode("ui-hint-mode")));

        ranCommands.clear();
        release("tab");
        release("leftalt");
        advance(250);
        assertTrue(press("leftalt").mustBeEaten());
        advance(30);
        press("k");
        assertFalse(ranCommands.contains(new Command.SwitchMode("ui-hint-mode")),
                ranCommands::toString);
        assertTrue(ranCommands.stream().anyMatch(Command.MacroCommand.class::isInstance),
                ranCommands::toString);
    }

    @Test
    void fStillOpensUiHintInNormalMode() throws IOException {
        loadShipped("normal-mode");
        press("f");
        assertTrue(ranCommands.contains(new Command.SwitchMode("ui-hint-mode")),
                ranCommands::toString);
    }

    @Test
    void altWithAnotherKeyDoesNotEnterUiHint() throws IOException {
        loadShipped();
        assertTrue(press("leftalt").mustBeEaten());
        advance(30);
        assertFalse(press("tab").mustBeEaten());
        assertTrue(ranCommands.contains(new Command.SwitchMode("begin-alt-tab-mode")),
                ranCommands::toString);
        assertFalse(ranCommands.contains(new Command.SwitchMode("ui-hint-mode")));

        ranCommands.clear();
        sentToOs.clear();
        release("tab");
        release("leftalt");
        // A new gesture, after the chord has been released.
        advance(250);
        assertTrue(press("leftalt").mustBeEaten());
        advance(30);
        // k is the up alias. Alt+k sends Up and must not open hints.
        press("k");
        assertFalse(ranCommands.contains(new Command.SwitchMode("ui-hint-mode")),
                ranCommands::toString);
        assertTrue(ranCommands.stream().anyMatch(Command.MacroCommand.class::isInstance),
                ranCommands::toString);
    }

    private void tapLeftAlt(long heldMillis) {
        assertTrue(press("leftalt").mustBeEaten());
        advance(heldMillis);
        assertTrue(release("leftalt").mustBeEaten());
    }

    private void loadShipped() throws IOException {
        loadShipped(Mode.IDLE_MODE_NAME);
    }

    private void loadShipped(String modeName) throws IOException {
        Configuration configuration = shippedConfiguration();
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
        comboWatcher.modeChanged(modeMap.get(modeName));
        KeyboardController keyboard = new KeyboardController() {
            @Override
            public void update(double delta) {
            }

            @Override
            public void reset() {
            }

            @Override
            public void sendInputMoves(List<ResolvedMacroMove> moves, boolean startRepeat) {
                for (ResolvedMacroMove move : moves) {
                    if (move instanceof ResolvedKeyMacroMove keyMove)
                        sentToOs.add((keyMove.press() ? "+" : "-") + keyMove.key().staticName());
                }
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
        sentToOs.clear();
    }

    private static Configuration shippedConfiguration() throws IOException {
        return ConfigurationParser.parse(
                PropertiesReader.readPropertiesFile(Files.newBufferedReader(
                        Path.of("configuration", "mousemaster.properties"))),
                KeyboardLayout.keyboardLayoutByShortName.get("us-qwerty"));
    }

    private EatAndRegurgitates press(String keyName) {
        return keyboardManager.keyEvent(new KeyEvent.PressKeyEvent(now, Key.ofName(keyName)));
    }

    private EatAndRegurgitates release(String keyName) {
        return keyboardManager.keyEvent(new KeyEvent.ReleaseKeyEvent(now, Key.ofName(keyName)));
    }

    private void advance(long millis) {
        now = now.plusMillis(millis);
    }

}
