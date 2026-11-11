package mousemaster;

import mousemaster.ScreenFilter.AnyScreenFilter;
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

/** R opens vision hints. Three presses walk the looks, and the fourth returns to the first. */
class VisionHintPassesTest {

    @Test
    void threePressesThenTheNextReturnsToTheFirst() throws Exception {
        Configuration configuration = configuration();
        ModeMap modeMap = configuration.modeMap();
        assertEquals(6, modeMap.get("vision-hint-mode").hintMesh().type().density());
        assertEquals(0, modeMap.get("vision-hint-mode").hintMesh().type().refine());
        assertEquals(8, modeMap.get("vision-hint-2-mode").hintMesh().type().density());
        assertEquals(Vision.straightLines,
                modeMap.get("vision-hint-2-mode").hintMesh().type().refine());
        assertEquals(Vision.blankAreas,
                modeMap.get("vision-hint-3-mode").hintMesh().type().refine());
        for (String modeName : List.of("vision-hint-mode", "vision-hint-2-mode",
                "vision-hint-3-mode")) {
            List<Key> keys = modeMap.get(modeName).hintMesh().keysByFilter()
                    .get(AnyScreenFilter.ANY_SCREEN_FILTER).selectionKeys();
            assertTrue(keys.stream().anyMatch(key -> key.name().equals("q")), modeName);
            assertFalse(keys.stream().anyMatch(key -> key.name().equals("r")), modeName);
            assertFalse(leavesWhenEmpty(modeMap.get(modeName)), modeName);
        }

        List<Command> ran = new ArrayList<>();
        ComboWatcher watcher = watcher(configuration, ran);
        watcher.modeChanged(modeMap.get("normal-mode"));
        ran.clear();
        List<String> seen = new ArrayList<>();
        for (String modeName : List.of("vision-hint-mode", "vision-hint-2-mode",
                "vision-hint-3-mode")) {
            tap(watcher, "r");
            seen.add(modeName);
            assertEquals(seen, switches(ran));
        }
        tap(watcher, "r");
        seen.add("vision-hint-mode");
        assertEquals(seen, switches(ran));
        tap(watcher, "r");
        seen.add("vision-hint-2-mode");
        assertEquals(seen, switches(ran));
    }

    @Test
    void qSelectsAVisionTagAndRDoesNot() throws Exception {
        Mode mode = configuration().modeMap().get("vision-hint-mode");
        boolean qSelects = false;
        for (var entry : mode.comboMap().commandsByCombo().entrySet()) {
            boolean selects = entry.getValue().stream()
                    .anyMatch(command -> command instanceof Command.SelectHintKey);
            if (!selects)
                continue;
            assertFalse(entry.getKey().sequence().allKeys().stream()
                    .anyMatch(key -> key.name().equals("r")));
            qSelects |= entry.getKey().sequence().allKeys().stream()
                    .anyMatch(key -> key.name().equals("q"));
        }
        assertTrue(qSelects);
    }

    private static boolean leavesWhenEmpty(Mode mode) {
        for (Combo combo : mode.comboMap().commandsByCombo().keySet())
            if (combo.toString().contains("isoverlayempty"))
                return true;
        return false;
    }

    private static List<String> switches(List<Command> ran) {
        List<String> names = new ArrayList<>();
        for (Command command : ran)
            if (command instanceof Command.SwitchMode switchMode)
                names.add(switchMode.modeName());
        return names;
    }

    private Instant now = Instant.parse("2026-01-01T00:00:00Z");

    private void tap(ComboWatcher watcher, String keyName) {
        Key key = Key.ofName(keyName);
        watcher.keyEvent(new KeyEvent.PressKeyEvent(now, key));
        now = now.plusMillis(50);
        watcher.keyEvent(new KeyEvent.ReleaseKeyEvent(now, key));
        now = now.plusMillis(50);
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
        ComboWatcher[] watcher = new ComboWatcher[1];
        CommandRunner commandRunner = new CommandRunner(null, null, null) {
            @Override
            public boolean runningAtomicCommand() {
                return false;
            }

            @Override
            public void run(Command command, Key eventKey) {
                ran.add(command);
                if (command instanceof Command.SwitchMode switchMode)
                    watcher[0].modeChanged(modeMap.get(switchMode.modeName()));
            }
        };
        watcher[0] = new ComboWatcher(commandRunner, null, () -> new App("test.exe"), null,
                Instant::now, unpressed, pressed, new KeyRedactor(KeyRedaction.NONE), modeMap,
                configuration.initiallySetVariables(), configuration.virtualKeys(),
                configuration.initiallyPressedVirtualKeys(),
                configuration.activeKeyboardLayout());
        watcher[0].setModeListeners(List.of());
        return watcher[0];
    }
}
