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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In Chrome, while mouse mode is active, Tab and Shift+Tab switch tabs the way
 * X and Shift+X close and reopen one. Other apps, idle mode, and Alt/Win/Ctrl
 * chords keep the key.
 */
class ChromeTabComboTest {

    private final List<Command> ranCommands = new ArrayList<>();
    private KeyboardManager keyboardManager;
    private ComboWatcher comboWatcher;
    private ModeMap modeMap;
    private final App[] activeApp = {new App("chrome.exe")};
    private Instant now = Instant.parse("2020-01-01T00:00:00Z");

    private void load() throws Exception {
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
            }
        };
        comboWatcher = new ComboWatcher(commandRunner, null,
                () -> activeApp[0], null, () -> now, Set.of(), pressedPreconditionKeys,
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
        activeApp[0] = new App("chrome.exe");
        comboWatcher.modeChanged(modeMap.get("normal-mode"));
        ranCommands.clear();
    }

    private EatAndRegurgitates press(String keyName) {
        now = now.plusMillis(30);
        return keyboardManager.keyEvent(new KeyEvent.PressKeyEvent(now, Key.ofName(keyName)));
    }

    private void release(String keyName) {
        now = now.plusMillis(30);
        keyboardManager.keyEvent(new KeyEvent.ReleaseKeyEvent(now, Key.ofName(keyName)));
    }

    private List<String> macroNames() {
        return ranCommands.stream()
                .filter(command -> command instanceof Command.MacroCommand)
                .map(command -> ((Command.MacroCommand) command).macro().name())
                .toList();
    }

    private Command.MacroCommand macroNamed(String name) {
        return ranCommands.stream()
                .filter(command -> command instanceof Command.MacroCommand)
                .map(command -> (Command.MacroCommand) command)
                .filter(command -> command.macro().name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(ranCommands.toString()));
    }

    private static String output(Command.MacroCommand macro) {
        StringBuilder builder = new StringBuilder();
        for (MacroParallel parallel : macro.macro().output().parallels()) {
            for (MacroMove move : parallel.moves()) {
                KeyMacroMove keyMove = (KeyMacroMove) move;
                if (!builder.isEmpty())
                    builder.append(' ');
                builder.append(keyMove.press() ? '+' : '-');
                builder.append(keyMove.keyOrAlias().key().name());
            }
        }
        return builder.toString();
    }

    @Test
    void tabSwitchesChromeTabsOnlyWhileMouseModeIsActive() throws Exception {
        load();
        EatAndRegurgitates tab = press("tab");
        assertTrue(tab.mustBeEaten());
        assertEquals(List.of("chromenexttab"), macroNames());
        assertEquals("+leftctrl +tab -tab -leftctrl", output(macroNamed("chromenexttab")));
        release("tab");

        press("leftshift");
        ranCommands.clear();
        EatAndRegurgitates shiftTab = press("tab");
        assertTrue(shiftTab.mustBeEaten());
        assertEquals(List.of("chromeprevioustab"), macroNames());
        assertEquals("+leftctrl +rightshift +tab -tab -rightshift -leftctrl",
                output(macroNamed("chromeprevioustab")));
        release("tab");
        release("leftshift");

        press("rightshift");
        ranCommands.clear();
        assertTrue(press("tab").mustBeEaten());
        assertEquals(List.of("chromeprevioustab"), macroNames());
        release("tab");
        release("rightshift");

        press("leftshift");
        ranCommands.clear();
        assertTrue(press("x").mustBeEaten());
        assertEquals(List.of("chromereopentab"), macroNames());
        release("x");
        release("leftshift");

        comboWatcher.modeChanged(modeMap.get("zoom-mode"));
        ranCommands.clear();
        assertTrue(press("tab").mustBeEaten());
        assertEquals(List.of("chromenexttab"), macroNames());
        release("tab");
        press("leftshift");
        ranCommands.clear();
        assertTrue(press("tab").mustBeEaten());
        assertEquals(List.of("chromeprevioustab"), macroNames());
        release("tab");
        release("leftshift");

        comboWatcher.modeChanged(modeMap.get("idle-mode"));
        ranCommands.clear();
        assertFalse(press("tab").mustBeEaten());
        assertTrue(macroNames().isEmpty());
        release("tab");
    }

    @Test
    void tabIsLeftAloneOutsideChromeAndForWindowSwitchers() throws Exception {
        load();
        activeApp[0] = new App("notepad.exe");
        assertFalse(press("tab").mustBeEaten());
        assertTrue(macroNames().isEmpty());
        release("tab");

        activeApp[0] = new App("chrome.exe");
        ranCommands.clear();
        press("leftctrl");
        assertFalse(press("tab").mustBeEaten());
        assertTrue(macroNames().isEmpty());
        release("tab");
        release("leftctrl");

        ranCommands.clear();
        press("leftalt");
        ranCommands.clear();
        assertFalse(press("tab").mustBeEaten());
        assertTrue(ranCommands.stream().anyMatch(
                command -> command.equals(new Command.SwitchMode("begin-alt-tab-mode"))));
        assertFalse(macroNames().contains("chromenexttab"));
        assertFalse(macroNames().contains("chromeprevioustab"));
        release("tab");
        release("leftalt");

        ranCommands.clear();
        press("leftshift");
        press("leftalt");
        ranCommands.clear();
        assertFalse(press("tab").mustBeEaten());
        assertFalse(macroNames().contains("chromenexttab"));
        assertFalse(macroNames().contains("chromeprevioustab"));
        release("tab");
        release("leftalt");
        release("leftshift");

        ranCommands.clear();
        press("leftwin");
        ranCommands.clear();
        assertFalse(press("tab").mustBeEaten());
        assertTrue(macroNames().isEmpty());
        release("tab");
        release("leftwin");
    }
}
