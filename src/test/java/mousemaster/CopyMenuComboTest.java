package mousemaster;

import mousemaster.Command.CopyItem;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The keys of the copy menu and of the tools it opens, in the shipped configuration:
 * what each key runs, that no key reaches the program in front, and that activation
 * mode is back only once the key that finished a tool is up.
 */
class CopyMenuComboTest {

    private final List<Command> ranCommands = new ArrayList<>();
    private KeyboardManager keyboardManager;
    private ComboWatcher comboWatcher;
    private ModeMap modeMap;
    private Instant now = Instant.parse("2020-01-01T00:00:00Z");
    /** The mode of the row space runs, as the menu would say. Null leaves the menu open. */
    private String highlightedRowMode;
    private String currentMode;

    private void load() throws Exception {
        ranCommands.clear();
        highlightedRowMode = null;
        Configuration configuration = ConfigurationParser.parse(
                PropertiesReader.readPropertiesFile(Files.newBufferedReader(
                        Path.of("configuration/mousemaster.properties"))),
                KeyboardLayout.keyboardLayout("00000409", null));
        modeMap = configuration.modeMap();
        Set<Key> unpressedPreconditionKeys = new HashSet<>();
        Set<Key> pressedPreconditionKeys = new HashSet<>();
        for (Mode mode : modeMap.modes())
            for (Combo combo : mode.comboMap().commandsByCombo().keySet()) {
                unpressedPreconditionKeys.addAll(
                        combo.precondition().keyPrecondition().unpressedKeySet());
                pressedPreconditionKeys.addAll(combo.precondition()
                                                    .keyPrecondition()
                                                    .pressedKeyPrecondition()
                                                    .allKeys());
            }
        CommandRunner commandRunner = new CommandRunner(null, null, null) {
            @Override
            public boolean runningAtomicCommand() {
                return false;
            }

            @Override
            public void run(Command command, Key eventKey) {
                ranCommands.add(command);
                // The modes are followed the way the program follows them.
                String modeName = switch (command) {
                    case Command.SwitchMode switchMode -> switchMode.modeName();
                    case Command.RunCopyItem row -> row.modeAfter();
                    case Command.RunSelectedCopyItem selected -> highlightedRowMode;
                    case Command.CopyToolConfirm confirm -> confirm.modeAfter();
                    default -> null;
                };
                if (modeName != null)
                    enter(modeName);
            }
        };
        comboWatcher = new ComboWatcher(commandRunner, null,
                () -> new App("test.exe"), null, () -> now, unpressedPreconditionKeys,
                pressedPreconditionKeys,
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
        enter("normal-mode");
        ranCommands.clear();
    }

    private void enter(String modeName) {
        Mode mode = modeMap.get(modeName);
        assertTrue(mode != null, "No mode " + modeName);
        currentMode = modeName;
        comboWatcher.modeChanged(mode);
    }

    private EatAndRegurgitates press(String keyName) {
        now = now.plusMillis(30);
        return keyboardManager.keyEvent(new KeyEvent.PressKeyEvent(now, Key.ofName(keyName)));
    }

    private EatAndRegurgitates release(String keyName) {
        now = now.plusMillis(30);
        return keyboardManager.keyEvent(new KeyEvent.ReleaseKeyEvent(now, Key.ofName(keyName)));
    }

    /** The key goes down: it does not reach the program in front. */
    private void pressEaten(String keyName) {
        EatAndRegurgitates pressed = press(keyName);
        assertTrue(pressed.mustBeEaten(), keyName + " in " + currentMode);
        assertTrue(pressed.regurgitates().isEmpty(), keyName + " in " + currentMode);
    }

    /** The key comes up: neither that nor a key held back earlier reaches the program. */
    private void releaseEaten(String keyName) {
        String mode = currentMode;
        EatAndRegurgitates released = release(keyName);
        assertTrue(released.mustBeEaten(), keyName + " in " + mode);
        assertTrue(released.regurgitates().isEmpty(), keyName + " in " + mode);
    }

    private void tap(String keyName) {
        pressEaten(keyName);
        releaseEaten(keyName);
    }

    private boolean ran(Command command) {
        return ranCommands.contains(command);
    }

    private boolean ranAny(Class<? extends Command> type) {
        return ranCommands.stream().anyMatch(type::isInstance);
    }

    /** What ran, apart from the commands that only eat a key. */
    private List<Command> ranBesidesNoops() {
        return ranCommands.stream().filter(command -> !(command instanceof Command.Noop)).toList();
    }

    @Test
    void yAloneOpensTheMenuAndIsNotARowUntilPressedAgain() throws Exception {
        load();
        pressEaten("y");
        assertEquals("copy-menu-mode", currentMode);
        // The y that opened the menu is not the first row's y, held or coming up.
        assertFalse(ranAny(Command.RunCopyItem.class));
        pressEaten("y");
        assertFalse(ranAny(Command.RunCopyItem.class));
        releaseEaten("y");
        assertFalse(ranAny(Command.RunCopyItem.class));
        assertEquals("copy-menu-mode", currentMode);
        // Pressed again, it is.
        ranCommands.clear();
        pressEaten("y");
        assertEquals(List.of(new Command.RunCopyItem(CopyItem.TEXT, Key.ofName("y"),
                "text-pick-mode")), ranCommands);
        assertEquals("text-pick-mode", currentMode);
        // Its release is not a letter of the picker.
        ranCommands.clear();
        releaseEaten("y");
        assertEquals(List.of(), ranBesidesNoops());
    }

    @Test
    void yWithAModifierIsNotTheMenu() throws Exception {
        // Left Shift+y is the grid of the active window.
        for (String shift : List.of("leftshift", "rightshift")) {
            load();
            press(shift);
            ranCommands.clear();
            press("y");
            assertFalse(ran(new Command.SwitchMode("copy-menu-mode")), shift);
            assertEquals(shift.equals("leftshift"), ran(new Command.SwitchMode("window-mode")),
                    shift);
        }
        // Ctrl+Y, Alt+Y and Win+Y are the program's and the system's.
        for (String modifier : List.of("leftctrl", "rightctrl", "leftalt", "rightalt",
                "leftwin", "rightwin")) {
            load();
            press(modifier);
            ranCommands.clear();
            press("y");
            assertFalse(ran(new Command.SwitchMode("copy-menu-mode")), modifier);
            assertFalse(ranAny(Command.RunCopyItem.class), modifier);
        }
    }

    @Test
    void yOpensTheMenuWhileMagnifying() throws Exception {
        load();
        enter("zoom-mode");
        ranCommands.clear();
        pressEaten("y");
        assertEquals("copy-menu-mode", currentMode);
        assertFalse(ranAny(Command.RunCopyItem.class));
        releaseEaten("y");
        // m and z magnify while they are held, and are still down when the menu opens.
        for (String zoomKey : List.of("m", "z")) {
            load();
            pressEaten(zoomKey);
            assertEquals("zoom-mode", currentMode, zoomKey);
            pressEaten("y");
            assertEquals("copy-menu-mode", currentMode, zoomKey);
            ranCommands.clear();
            releaseEaten(zoomKey);
            releaseEaten("y");
            assertEquals(List.of(), ranBesidesNoops(), zoomKey);
            assertEquals("copy-menu-mode", currentMode, zoomKey);
        }
    }

    @Test
    void theMenuMovesWithEDKJAndRunsWithGSpaceAndEnter() throws Exception {
        load();
        tap("y");
        for (Map.Entry<String, Integer> move : Map.of("e", -1, "k", -1, "d", 1, "j", 1)
                                                  .entrySet()) {
            ranCommands.clear();
            pressEaten(move.getKey());
            assertEquals(List.of(new Command.SelectCopyItem(move.getValue())), ranCommands,
                    move.getKey());
            releaseEaten(move.getKey());
            assertEquals("copy-menu-mode", currentMode);
        }
        // The harness leaves the menu open, as when it has no rows.
        for (String runKey : List.of("g", "space", "enter", "numpadenter")) {
            ranCommands.clear();
            pressEaten(runKey);
            assertEquals(List.of(new Command.RunSelectedCopyItem()), ranCommands, runKey);
            // Held down, the key does not run the row again.
            pressEaten(runKey);
            assertEquals(1, ranCommands.size(), runKey);
            ranCommands.clear();
            releaseEaten(runKey);
            assertEquals(List.of(), ranBesidesNoops(), runKey);
            assertEquals("copy-menu-mode", currentMode);
        }
        ranCommands.clear();
        pressEaten("esc");
        assertEquals("normal-mode", currentMode);
        releaseEaten("esc");
        assertFalse(ran(new Command.SwitchMode("idle-mode")));
    }

    @Test
    void eachRowHasItsLetterAndTheModeItsToolRunsIn() throws Exception {
        Map<String, Command.RunCopyItem> rows = Map.of(
                "y", new Command.RunCopyItem(CopyItem.TEXT, Key.ofName("y"), "text-pick-mode"),
                "w", new Command.RunCopyItem(CopyItem.WORD, Key.ofName("w"), "copy-menu-done-mode"),
                "a", new Command.RunCopyItem(CopyItem.ALL, Key.ofName("a"), "copy-menu-done-mode"),
                "i", new Command.RunCopyItem(CopyItem.SCREENSHOT, Key.ofName("i"), "screenshot-mode"),
                "l", new Command.RunCopyItem(CopyItem.LONG_SCREENSHOT, Key.ofName("l"),
                        "long-screenshot-area-mode"));
        for (Map.Entry<String, Command.RunCopyItem> row : rows.entrySet()) {
            String letter = row.getKey();
            load();
            tap("y");
            ranCommands.clear();
            pressEaten(letter);
            // l is also the key that moves right where the region is chosen. No corner
            // is chosen yet, so the tool stops that move before it shows anything.
            List<Command> expected = letter.equals("l") ?
                    List.of(row.getValue(), new Command.StartMoveRight()) :
                    List.of(row.getValue());
            assertEquals(expected, ranCommands, letter);
            assertEquals(row.getValue().modeAfter(), currentMode, letter);
            // Held down, the letter does nothing more in the mode it opened.
            pressEaten(letter);
            assertEquals(expected, ranCommands, letter);
            ranCommands.clear();
            releaseEaten(letter);
            assertEquals(letter.equals("l") ? List.of(new Command.StopMoveRight()) : List.of(),
                    ranBesidesNoops().stream()
                                     .filter(command -> !(command instanceof Command.SwitchMode))
                                     .toList(), letter);
            assertFalse(ran(new Command.SwitchMode("idle-mode")), letter);
        }
    }

    /**
     * The word and everything are copied in activation mode, which is back only when the
     * key that ran the row is up: there w, a, g and space are commands of their own, and
     * space coming up leaves it.
     */
    @Test
    void afterTheWordIsCopiedActivationModeWaitsForTheKeyToComeUp() throws Exception {
        for (String letter : List.of("w", "a")) {
            load();
            tap("y");
            pressEaten(letter);
            assertEquals("copy-menu-done-mode", currentMode);
            ranCommands.clear();
            releaseEaten(letter);
            assertEquals(List.of(new Command.SwitchMode("normal-mode")), ranBesidesNoops(), letter);
        }
        for (String runKey : List.of("g", "space", "enter", "numpadenter")) {
            load();
            tap("y");
            tap("j");
            highlightedRowMode = "copy-menu-done-mode";
            ranCommands.clear();
            pressEaten(runKey);
            assertEquals("copy-menu-done-mode", currentMode, runKey);
            assertFalse(ran(new Command.PressLeft()), runKey);
            ranCommands.clear();
            releaseEaten(runKey);
            // g coming up in activation mode releases the left button, which is not down.
            assertEquals(List.of(new Command.SwitchMode("normal-mode")),
                    ranBesidesNoops().stream()
                                     .filter(command -> !(command instanceof Command.ReleaseLeft))
                                     .toList(), runKey);
            assertEquals("normal-mode", currentMode);
        }
    }

    @Test
    void thePickerTakesEveryLetterAndBackspaceAndCopiesWithSpaceOrEnter() throws Exception {
        load();
        tap("y");
        tap("y");
        assertEquals("text-pick-mode", currentMode);
        Mode mode = modeMap.get("text-pick-mode");
        List<Key> letters = mode.comboMap().commandsByCombo().values().stream()
                                .flatMap(List::stream)
                                .filter(Command.CopyToolKey.class::isInstance)
                                .map(command -> ((Command.CopyToolKey) command).keys())
                                .findFirst().orElseThrow();
        assertEquals(26, letters.size());
        assertEquals(26, new HashSet<>(letters).size());
        // The home row comes first: a paragraph of a few words is tagged with it.
        assertEquals(List.of("a", "s", "d", "f", "g", "h", "j", "k", "l"),
                letters.subList(0, 9).stream().map(Key::name).toList());
        for (Key letter : letters) {
            ranCommands.clear();
            pressEaten(letter.name());
            assertEquals(List.of(new Command.CopyToolKey(letters)), ranCommands, letter.name());
            ranCommands.clear();
            releaseEaten(letter.name());
            assertEquals(List.of(), ranBesidesNoops(), letter.name());
            assertEquals("text-pick-mode", currentMode, letter.name());
        }
        ranCommands.clear();
        pressEaten("backspace");
        assertEquals(List.of(new Command.CopyToolUndo()), ranCommands);
        releaseEaten("backspace");

        for (String confirmKey : List.of("space", "enter", "numpadenter")) {
            enter("text-pick-mode");
            ranCommands.clear();
            pressEaten(confirmKey);
            assertEquals(List.of(new Command.CopyToolConfirm("copy-tool-done-mode")),
                    ranCommands, confirmKey);
            assertEquals("copy-tool-done-mode", currentMode, confirmKey);
            ranCommands.clear();
            releaseEaten(confirmKey);
            assertEquals(List.of(new Command.SwitchMode("normal-mode")), ranBesidesNoops(),
                    confirmKey);
        }

        enter("text-pick-mode");
        ranCommands.clear();
        pressEaten("esc");
        assertEquals(List.of(new Command.SwitchMode("normal-mode")), ranCommands);
        releaseEaten("esc");
        assertFalse(ran(new Command.SwitchMode("idle-mode")));
    }

    @Test
    void theScreenshotChoosesACornerWithEOrFAndMovesItWithHJKL() throws Exception {
        for (String areaMode : List.of("screenshot-mode", "long-screenshot-area-mode")) {
            load();
            tap("y");
            tap(areaMode.equals("screenshot-mode") ? "i" : "l");
            assertEquals(areaMode, currentMode);
            List<Key> corners = List.of(Key.ofName("e"), Key.ofName("f"));
            for (String corner : List.of("e", "f")) {
                ranCommands.clear();
                pressEaten(corner);
                assertEquals(List.of(new Command.CopyToolKey(corners)), ranCommands, corner);
                ranCommands.clear();
                releaseEaten(corner);
                assertEquals(List.of(), ranBesidesNoops(), corner);
            }
            Map<String, List<Command>> moves = Map.of(
                    "k", List.of(new Command.StartMoveUp(), new Command.StopMoveUp()),
                    "j", List.of(new Command.StartMoveDown(), new Command.StopMoveDown()),
                    "h", List.of(new Command.StartMoveLeft(), new Command.StopMoveLeft()),
                    "l", List.of(new Command.StartMoveRight(), new Command.StopMoveRight()));
            for (String modifier : List.of("", "leftshift", "rightshift", "c", "b")) {
                if (!modifier.isEmpty())
                    pressEaten(modifier);
                for (Map.Entry<String, List<Command>> move : moves.entrySet()) {
                    String what = modifier + " " + move.getKey() + " in " + areaMode;
                    ranCommands.clear();
                    pressEaten(move.getKey());
                    assertEquals(List.of(move.getValue().get(0)), ranBesidesNoops(), what);
                    ranCommands.clear();
                    releaseEaten(move.getKey());
                    assertEquals(List.of(move.getValue().get(1)), ranBesidesNoops(), what);
                }
                // Two directions at once move the corner diagonally.
                ranCommands.clear();
                pressEaten("k");
                pressEaten("l");
                assertEquals(List.of(new Command.StartMoveUp(), new Command.StartMoveRight()),
                        ranBesidesNoops(), modifier);
                releaseEaten("k");
                releaseEaten("l");
                if (!modifier.isEmpty()) {
                    ranCommands.clear();
                    releaseEaten(modifier);
                    assertEquals(List.of(), ranBesidesNoops(), modifier);
                }
                assertEquals(areaMode, currentMode, modifier);
            }
            ranCommands.clear();
            pressEaten("esc");
            assertEquals(List.of(new Command.SwitchMode("normal-mode")), ranCommands);
            releaseEaten("esc");
        }
    }

    @Test
    void spaceOrEnterCopiesTheScreenshotAndActivationModeWaitsForItToComeUp() throws Exception {
        for (String confirmKey : List.of("space", "enter", "numpadenter")) {
            load();
            tap("y");
            tap("i");
            ranCommands.clear();
            pressEaten(confirmKey);
            assertEquals(List.of(new Command.CopyToolConfirm("copy-tool-done-mode")),
                    ranCommands, confirmKey);
            ranCommands.clear();
            releaseEaten(confirmKey);
            assertEquals(List.of(new Command.SwitchMode("normal-mode")), ranBesidesNoops(),
                    confirmKey);
        }
        // The row is run with space: space coming up is not the copy.
        load();
        tap("y");
        highlightedRowMode = "screenshot-mode";
        pressEaten("space");
        assertEquals("screenshot-mode", currentMode);
        ranCommands.clear();
        releaseEaten("space");
        assertEquals(List.of(), ranBesidesNoops());
        assertEquals("screenshot-mode", currentMode);
        // g does not copy.
        ranCommands.clear();
        press("g");
        release("g");
        assertFalse(ranAny(Command.CopyToolConfirm.class));
    }

    @Test
    void theLongScreenshotFixesItsRegionThenScrollsWithEAndDAndCopies() throws Exception {
        for (String confirmKey : List.of("space", "enter", "numpadenter")) {
            load();
            tap("y");
            tap("l");
            assertEquals("long-screenshot-area-mode", currentMode);
            ranCommands.clear();
            pressEaten(confirmKey);
            assertEquals(List.of(new Command.CopyToolConfirm("long-screenshot-mode")),
                    ranCommands, confirmKey);
            assertEquals("long-screenshot-mode", currentMode);
            // Held down, the key does not finish the long picture too.
            pressEaten(confirmKey);
            assertEquals(1, ranCommands.size(), confirmKey);
            ranCommands.clear();
            releaseEaten(confirmKey);
            assertEquals(List.of(), ranBesidesNoops(), confirmKey);
            assertEquals("long-screenshot-mode", currentMode);

            for (String modifier : List.of("", "leftshift", "b")) {
                if (!modifier.isEmpty())
                    pressEaten(modifier);
                ranCommands.clear();
                pressEaten("d");
                assertEquals(List.of(new Command.StartWheelDown()), ranBesidesNoops(), modifier);
                ranCommands.clear();
                releaseEaten("d");
                assertEquals(List.of(new Command.StopWheelDown()), ranBesidesNoops(), modifier);
                ranCommands.clear();
                pressEaten("e");
                assertEquals(List.of(new Command.StartWheelUp()), ranBesidesNoops(), modifier);
                ranCommands.clear();
                releaseEaten("e");
                assertEquals(List.of(new Command.StopWheelUp()), ranBesidesNoops(), modifier);
                if (!modifier.isEmpty())
                    releaseEaten(modifier);
            }
            // The corners no longer move.
            ranCommands.clear();
            press("k");
            release("k");
            press("f");
            release("f");
            assertEquals(List.of(), ranBesidesNoops());

            ranCommands.clear();
            pressEaten(confirmKey);
            assertEquals(List.of(new Command.CopyToolConfirm("copy-tool-done-mode")),
                    ranCommands, confirmKey);
            ranCommands.clear();
            releaseEaten(confirmKey);
            assertEquals(List.of(new Command.SwitchMode("normal-mode")), ranBesidesNoops(),
                    confirmKey);
        }
        load();
        enter("long-screenshot-mode");
        pressEaten("esc");
        assertEquals("normal-mode", currentMode);
        releaseEaten("esc");
        assertFalse(ran(new Command.SwitchMode("idle-mode")));
    }

    @Test
    void thePointerIsHiddenAndSlowUnderShiftWhileARegionIsChosen() throws Exception {
        load();
        for (String modeName : List.of("screenshot-mode", "long-screenshot-area-mode",
                "long-screenshot-mode")) {
            Mode mode = modeMap.get(modeName);
            assertTrue(mode.hideCursor().enabled(), modeName);
            assertEquals(Duration.ZERO, mode.hideCursor().idleDuration(), modeName);
            assertFalse(mode.pushModeToHistoryStack(), modeName);
            assertTrue(mode.stopCommandsFromPreviousMode(), modeName);
        }
        for (String modeName : List.of("copy-menu-mode", "copy-menu-done-mode", "text-pick-mode",
                "copy-tool-done-mode")) {
            Mode mode = modeMap.get(modeName);
            assertFalse(mode.hideCursor().enabled(), modeName);
            assertFalse(mode.pushModeToHistoryStack(), modeName);
            assertTrue(mode.stopCommandsFromPreviousMode(), modeName);
        }
        // A corner goes where the keys send it at once, not along a path.
        assertFalse(modeMap.get("screenshot-mode").mouse().smoothJumpEnabled());
        assertFalse(modeMap.get("long-screenshot-area-mode").mouse().smoothJumpEnabled());
    }
}
