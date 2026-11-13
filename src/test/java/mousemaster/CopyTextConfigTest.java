package mousemaster;

import mousemaster.KeyboardManager.EatAndRegurgitates;
import mousemaster.platform.KeyboardController;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopyTextConfigTest {

    @Test
    void yOpensTheCopyMenuAndNoLongerOpensTheScreenGrid() throws Exception {
        Configuration configuration = configuration();

        assertNull(configuration.modeMap().get("grid-mode"));
        // The word, the line and everything are no longer y, yy and yyy: they are rows
        // of the menu y opens.
        for (String modeName : List.of("normal-mode", "zoom-mode")) {
            Mode mode = configuration.modeMap().get(modeName);
            assertEquals(EnumSet.noneOf(Command.CopyText.Kind.class), copyKinds(mode), modeName);
            assertTrue(switchesTo(mode, "copy-menu-mode"), modeName);
        }
        EnumSet<Command.CopyItem> rows = EnumSet.noneOf(Command.CopyItem.class);
        for (List<Command> commands : configuration.modeMap().get("copy-menu-mode").comboMap()
                                                   .commandsByCombo().values())
            for (Command command : commands)
                if (command instanceof Command.RunCopyItem row)
                    rows.add(row.item());
        assertEquals(EnumSet.allOf(Command.CopyItem.class), rows);
        assertTrue(switchesTo(configuration.modeMap().get("normal-mode"), "window-mode"));
        assertEquals(false, switchesTo(configuration.modeMap().get("normal-mode"), "grid-mode"));
    }

    @Test
    void pPastesAndQExits() throws Exception {
        Configuration configuration = configuration();
        List<Command> ran = new ArrayList<>();
        ComboWatcher watcher = watcher(configuration, ran);
        Instant now = Instant.parse("2020-01-01T00:00:00Z");
        for (String modeName : List.of("normal-mode", "zoom-mode")) {
            ran.clear();
            watcher.modeChanged(configuration.modeMap().get(modeName));
            watcher.keyEvent(new KeyEvent.PressKeyEvent(now, Key.ofName("p")));
            Command.MacroCommand paste = assertInstanceOf(Command.MacroCommand.class, ran.getFirst());
            assertEquals("paste", paste.macro().name());
            assertTrue(ran.stream().noneMatch(command -> command instanceof Command.SwitchMode));
            now = now.plusMillis(30);
            watcher.keyEvent(new KeyEvent.ReleaseKeyEvent(now, Key.ofName("p")));
        }
        ran.clear();
        watcher.modeChanged(configuration.modeMap().get("normal-mode"));
        now = now.plusMillis(30);
        watcher.keyEvent(new KeyEvent.PressKeyEvent(now, Key.ofName("q")));
        assertTrue(ran.stream().anyMatch(command ->
                command instanceof Command.SwitchMode switchMode &&
                switchMode.modeName().equals("idle-mode")));
    }

    @Test
    void cAloneSendsCtrlCAndStaysSlowBesideAnotherKey() throws Exception {
        Session session = session();
        EatAndRegurgitates c = session.press("c");
        assertTrue(c.mustBeEaten());
        assertTrue(c.regurgitates().isEmpty());
        assertEquals("+leftctrl +c -c -leftctrl", output(session.macro("copyselection")));
        assertTrue(session.release("c").regurgitates().isEmpty());

        session.ran.clear();
        session.press("h");
        EatAndRegurgitates slow = session.press("c");
        assertTrue(slow.mustBeEaten());
        assertTrue(session.ran.stream().noneMatch(command ->
                command instanceof Command.MacroCommand macro &&
                macro.macro().name().equals("copyselection")));
        session.release("c");
        session.release("h");

        session.ran.clear();
        session.watcher.modeChanged(session.modeMap.get("zoom-mode"));
        session.press("m");
        EatAndRegurgitates zoomed = session.press("c");
        assertTrue(zoomed.mustBeEaten());
        assertEquals("+leftctrl +c -c -leftctrl", output(session.macro("copyselection")));
        session.release("c");
        session.release("m");

        session.ran.clear();
        session.watcher.modeChanged(session.modeMap.get("normal-mode"));
        session.press("leftctrl");
        assertFalse(session.press("c").mustBeEaten());
        assertTrue(session.ran.stream().noneMatch(command ->
                command instanceof Command.MacroCommand macro &&
                macro.macro().name().equals("copyselection")));
        session.release("c");
        session.release("leftctrl");

        session.ran.clear();
        session.press("leftalt");
        assertFalse(session.press("c").mustBeEaten());
        assertTrue(session.ran.stream().noneMatch(command ->
                command instanceof Command.MacroCommand macro &&
                macro.macro().name().equals("copyselection")));
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

    private static Configuration configuration() throws Exception {
        return ConfigurationParser.parse(
                PropertiesReader.readPropertiesFile(
                        Files.newBufferedReader(Path.of("configuration/mousemaster.properties"))),
                KeyboardLayout.keyboardLayout("00000409", null));
    }

    private static ComboWatcher watcher(Configuration configuration, List<Command> ran) {
        ModeMap modeMap = configuration.modeMap();
        Set<Key> unpressed = new HashSet<>();
        Set<Key> pressed = new HashSet<>();
        for (Mode mode : modeMap.modes())
            for (Combo combo : mode.comboMap().commandsByCombo().keySet()) {
                unpressed.addAll(combo.precondition().keyPrecondition().unpressedKeySet());
                pressed.addAll(combo.precondition().keyPrecondition()
                                    .pressedKeyPrecondition().allKeys());
            }
        CommandRunner commandRunner = new CommandRunner(null, null, null) {
            @Override
            public boolean runningAtomicCommand() {
                return false;
            }

            @Override
            public void run(Command command, Key eventKey) {
                ran.add(command);
            }
        };
        ComboWatcher comboWatcher = new ComboWatcher(commandRunner, null,
                () -> new App("test.exe"), null, Instant::now, unpressed, pressed,
                new KeyRedactor(KeyRedaction.NONE), modeMap,
                configuration.initiallySetVariables(), configuration.virtualKeys(),
                configuration.initiallyPressedVirtualKeys(),
                configuration.activeKeyboardLayout());
        comboWatcher.setModeListeners(List.of());
        return comboWatcher;
    }

    private static Set<Command.CopyText.Kind> copyKinds(Mode mode) {
        EnumSet<Command.CopyText.Kind> kinds = EnumSet.noneOf(Command.CopyText.Kind.class);
        for (List<Command> commands : mode.comboMap().commandsByCombo().values())
            for (Command command : commands)
                if (command instanceof Command.CopyText copyText)
                    kinds.add(copyText.kind());
        return kinds;
    }

    private static boolean switchesTo(Mode mode, String modeName) {
        for (List<Command> commands : mode.comboMap().commandsByCombo().values())
            for (Command command : commands)
                if (command instanceof Command.SwitchMode switchMode &&
                    switchMode.modeName().equals(modeName))
                    return true;
        return false;
    }

    private static Session session() throws Exception {
        Configuration configuration = configuration();
        ModeMap modeMap = configuration.modeMap();
        List<Command> ran = new ArrayList<>();
        Set<Key> unpressed = new HashSet<>();
        Set<Key> pressed = new HashSet<>();
        for (Mode mode : modeMap.modes())
            for (Combo combo : mode.comboMap().commandsByCombo().keySet()) {
                unpressed.addAll(combo.precondition().keyPrecondition().unpressedKeySet());
                pressed.addAll(combo.precondition().keyPrecondition()
                                    .pressedKeyPrecondition().allKeys());
            }
        Instant[] now = {Instant.parse("2020-01-01T00:00:00Z")};
        CommandRunner commandRunner = new CommandRunner(null, null, null) {
            @Override
            public boolean runningAtomicCommand() {
                return false;
            }

            @Override
            public void run(Command command, Key eventKey) {
                ran.add(command);
            }
        };
        ComboWatcher watcher = new ComboWatcher(commandRunner, null,
                () -> new App("test.exe"), null, () -> now[0], unpressed, pressed,
                new KeyRedactor(KeyRedaction.NONE), modeMap,
                configuration.initiallySetVariables(), configuration.virtualKeys(),
                configuration.initiallyPressedVirtualKeys(),
                configuration.activeKeyboardLayout());
        watcher.setModeListeners(List.of());
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
        KeyboardManager keyboardManager = new KeyboardManager(watcher, null,
                new KeyRegurgitator(keyboard, new KeyRedactor(KeyRedaction.NONE)), () -> now[0]);
        keyboardManager.setMacroPlayer(new MacroPlayer(() -> now[0], watcher, keyboardManager,
                keyboard, new KeyRedactor(KeyRedaction.NONE),
                configuration.activeKeyboardLayout()));
        watcher.modeChanged(modeMap.get("normal-mode"));
        ran.clear();
        return new Session(keyboardManager, watcher, modeMap, ran, now);
    }

    private static final class Session {
        private final KeyboardManager keyboard;
        private final ComboWatcher watcher;
        private final ModeMap modeMap;
        private final List<Command> ran;
        private final Instant[] now;

        private Session(KeyboardManager keyboard, ComboWatcher watcher, ModeMap modeMap,
                        List<Command> ran, Instant[] now) {
            this.keyboard = keyboard;
            this.watcher = watcher;
            this.modeMap = modeMap;
            this.ran = ran;
            this.now = now;
        }

        private EatAndRegurgitates press(String keyName) {
            now[0] = now[0].plusMillis(30);
            return keyboard.keyEvent(new KeyEvent.PressKeyEvent(now[0], Key.ofName(keyName)));
        }

        private EatAndRegurgitates release(String keyName) {
            now[0] = now[0].plusMillis(30);
            return keyboard.keyEvent(new KeyEvent.ReleaseKeyEvent(now[0], Key.ofName(keyName)));
        }

        private Command.MacroCommand macro(String name) {
            return ran.stream()
                      .filter(command -> command instanceof Command.MacroCommand)
                      .map(command -> (Command.MacroCommand) command)
                      .filter(command -> command.macro().name().equals(name))
                      .findFirst()
                      .orElseThrow(() -> new AssertionError(ran.toString()));
        }
    }
}
