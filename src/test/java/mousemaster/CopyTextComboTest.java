package mousemaster;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * y is a tap dance: one tap copies the word, two the line, three everything.
 */
class CopyTextComboTest {

    private static final String CONFIG = """
            key-alias.copy=y
            key-alias.windowmod=leftshift
            idle-mode.copy-text.word=^{windowmod} #!{copy}-400 +copy -copy-400
            idle-mode.copy-text.line=^{windowmod} #!{copy}-400 +copy -copy-0-400 +copy -copy-400
            idle-mode.copy-text.all=^{windowmod} #!{copy}-400 +copy -copy-0-400 +copy -copy-0-400 +copy -copy
            idle-mode.break-combo-preparation=^{windowmod} #!{copy}-400 +copy -copy-0-400 +copy -copy-0-400 +copy -copy
            """;

    private final List<Command.CopyText.Kind> copied = new ArrayList<>();
    private Instant now = Instant.parse("2020-01-01T00:00:00Z");
    private ComboWatcher comboWatcher;

    private void load() {
        Configuration configuration = ConfigurationParser.parse(CONFIG.lines().toList(),
                KeyboardLayout.keyboardLayout("00000409", null));
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
                if (command instanceof Command.CopyText copyText)
                    copied.add(copyText.kind());
            }
        };
        comboWatcher = new ComboWatcher(commandRunner, null, () -> new App("test.exe"), null,
                () -> now, unpressed, pressed, new KeyRedactor(KeyRedaction.NONE),
                modeMap, configuration.initiallySetVariables(), configuration.virtualKeys(),
                configuration.initiallyPressedVirtualKeys(),
                configuration.activeKeyboardLayout());
        comboWatcher.setModeListeners(List.of());
        comboWatcher.modeChanged(modeMap.get(Mode.IDLE_MODE_NAME));
    }

    private void tap(String keyName) {
        comboWatcher.keyEvent(new KeyEvent.PressKeyEvent(now, Key.ofName(keyName)));
        now = now.plusMillis(30);
        comboWatcher.keyEvent(new KeyEvent.ReleaseKeyEvent(now, Key.ofName(keyName)));
    }

    private void elapse(long millis) {
        now = now.plus(Duration.ofMillis(millis));
        comboWatcher.update(millis / 1000d);
    }

    @Test
    void oneTapCopiesTheWordAfterTheGap() {
        load();
        tap("y");
        elapse(399);
        assertEquals(List.of(), copied);
        elapse(1);
        assertEquals(List.of(Command.CopyText.Kind.WORD), copied);
    }

    @Test
    void twoTapsCopyTheLine() {
        load();
        tap("y");
        elapse(120);
        tap("y");
        elapse(400);
        assertEquals(List.of(Command.CopyText.Kind.LINE), copied);
    }

    @Test
    void threeTapsSelectAll() {
        load();
        tap("y");
        elapse(120);
        tap("y");
        elapse(120);
        tap("y");
        elapse(10);
        assertEquals(List.of(Command.CopyText.Kind.ALL), copied);
    }

    @Test
    void shiftAndYDoesNotCopy() {
        load();
        comboWatcher.keyEvent(new KeyEvent.PressKeyEvent(now, Key.ofName("leftshift")));
        tap("y");
        elapse(500);
        assertEquals(List.of(), copied);
    }
}
