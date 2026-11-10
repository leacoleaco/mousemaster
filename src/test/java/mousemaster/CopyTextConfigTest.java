package mousemaster;

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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopyTextConfigTest {

    @Test
    void yCopiesTextAndNoLongerOpensTheScreenGrid() throws Exception {
        Configuration configuration = configuration();

        assertNull(configuration.modeMap().get("grid-mode"));
        assertEquals(EnumSet.allOf(Command.CopyText.Kind.class),
                copyKinds(configuration.modeMap().get("normal-mode")));
        assertEquals(EnumSet.allOf(Command.CopyText.Kind.class),
                copyKinds(configuration.modeMap().get("zoom-mode")));
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
}
