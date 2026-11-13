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
 * Hold Shift and e/d/s/f move the pointer the way k/j/h/l do. Release Shift and e/d
 * scroll again. F alone opens hints. Shift+R opens the letter grid.
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
        // Both sets are filled the way Mousemaster fills them.
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
                if (!followModeSwitches)
                    return;
                String modeName = switch (command) {
                    case Command.SwitchMode switchMode -> switchMode.modeName();
                    // g confirms, unless the letter is a window-list row. The row wins.
                    case Command.ActivateSelectedWindow activate ->
                            eventKey != null && MENU_TAGS.contains(eventKey.name()) ?
                                    null : activate.modeAfter();
                    case Command.RunSelectedMenuItem runItem -> runItem.modeAfter();
                    // As if the pressed letter tagged a row.
                    case Command.ActivateWindowByTag byTag -> byTag.modeAfter();
                    case Command.RunMenuItemByTag byTag -> byTag.modeAfter();
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
    void holdingShiftTurnsEdsfIntoMovement() throws Exception {
        load();
        assertTrue(press("leftshift").mustBeEaten());
        assertFalse(ran(new Command.StartMoveUp()));

        assertTrue(press("e").mustBeEaten());
        assertTrue(ran(new Command.StartMoveUp()));
        assertFalse(ran(new Command.StartWheelUp()));
        ranCommands.clear();
        EatAndRegurgitates releaseE = release("e");
        assertTrue(releaseE.mustBeEaten());
        assertTrue(releaseE.regurgitates().isEmpty());
        assertTrue(ran(new Command.StopMoveUp()));
        release("leftshift");

        ranCommands.clear();
        assertTrue(press("d").mustBeEaten());
        assertFalse(ran(new Command.StartMoveDown()));
        release("d");
        press("leftshift");
        ranCommands.clear();
        assertTrue(press("d").mustBeEaten());
        assertTrue(ran(new Command.StartMoveDown()));
        assertFalse(ran(new Command.StartWheelDown()));
        release("d");
        release("leftshift");

        ranCommands.clear();
        assertTrue(press("s").mustBeEaten());
        assertTrue(ran(new Command.SwitchMode("window-switch-mode")));
        assertFalse(ran(new Command.StartMoveLeft()));
        release("s");
        load();
        press("leftshift");
        ranCommands.clear();
        assertTrue(press("s").mustBeEaten());
        assertTrue(ran(new Command.StartMoveLeft()));
        assertFalse(ran(new Command.SwitchMode("window-switch-mode")));
        release("s");
        release("leftshift");

        ranCommands.clear();
        assertTrue(press("f").mustBeEaten());
        assertTrue(ran(new Command.SwitchMode("ui-hint-mode")));
        assertFalse(ran(new Command.StartMoveRight()));
        release("f");

        load();
        press("leftshift");
        ranCommands.clear();
        assertTrue(press("f").mustBeEaten());
        assertTrue(ran(new Command.StartMoveRight()));
        assertFalse(ran(new Command.SwitchMode("ui-hint-mode")));
        assertFalse(ran(new Command.SwitchMode("hint1-mode")));

        load();
        assertTrue(press("r").mustBeEaten());
        assertTrue(ran(new Command.SwitchMode("vision-hint-mode")));
        assertFalse(ran(new Command.SwitchMode("hint1-mode")));

        load();
        press("leftshift");
        ranCommands.clear();
        assertTrue(press("r").mustBeEaten());
        assertTrue(ran(new Command.SwitchMode("hint1-mode")));
        assertFalse(ran(new Command.StartMoveRight()));
        assertFalse(ran(new Command.SwitchMode("vision-hint-mode")));
    }

    @Test
    void releasingShiftStopsHandMovementAndRestoresTheWheel() throws Exception {
        load();
        press("e");
        assertTrue(ran(new Command.StartWheelUp()));
        ranCommands.clear();
        press("leftshift");
        assertTrue(ran(new Command.StopWheelUp()));
        assertTrue(ran(new Command.StartMoveUp()));
        ranCommands.clear();
        EatAndRegurgitates releaseShift = release("leftshift");
        assertTrue(releaseShift.mustBeEaten());
        assertTrue(releaseShift.regurgitates().isEmpty());
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
        press("leftshift");
        press("e");
        ranCommands.clear();
        release("k");
        assertFalse(ran(new Command.StopMoveUp()));
        release("e");
        assertTrue(ran(new Command.StopMoveUp()));
    }

    @Test
    void shiftGTogglesTheLeftButtonTheWayNDoes() throws Exception {
        load();
        assertTrue(press("g").mustBeEaten());
        assertTrue(ran(new Command.PressLeft()));
        assertFalse(ran(new Command.ToggleLeft()));
        ranCommands.clear();
        EatAndRegurgitates releaseG = release("g");
        assertTrue(releaseG.mustBeEaten());
        assertTrue(releaseG.regurgitates().isEmpty());
        assertTrue(ran(new Command.ReleaseLeft()));

        load();
        press("k");
        ranCommands.clear();
        assertTrue(press("g").mustBeEaten());
        assertTrue(ran(new Command.PressLeft()));
        ranCommands.clear();
        releaseG = release("g");
        assertTrue(releaseG.mustBeEaten());
        assertTrue(releaseG.regurgitates().isEmpty());
        assertTrue(ran(new Command.ReleaseLeft()));

        for (String shift : List.of("leftshift", "rightshift")) {
            load();
            assertTrue(press(shift).mustBeEaten());
            press("e");
            ranCommands.clear();
            assertTrue(press("g").mustBeEaten());
            assertTrue(ran(new Command.ToggleLeft()));
            assertFalse(ran(new Command.PressLeft()));
            ranCommands.clear();
            releaseG = release("g");
            assertTrue(releaseG.mustBeEaten());
            assertTrue(releaseG.regurgitates().isEmpty());
            assertFalse(ran(new Command.ReleaseLeft()));
            release(shift);

            ranCommands.clear();
            press(shift);
            press("g");
            assertTrue(ran(new Command.ToggleLeft()));
            assertFalse(ran(new Command.PressLeft()));
            ranCommands.clear();
            releaseG = release("g");
            assertTrue(releaseG.mustBeEaten());
            assertTrue(releaseG.regurgitates().isEmpty());
            assertFalse(ran(new Command.ReleaseLeft()));
        }

        load();
        press("g");
        press("leftshift");
        ranCommands.clear();
        releaseG = release("g");
        assertTrue(releaseG.mustBeEaten());
        assertTrue(releaseG.regurgitates().isEmpty());
        assertTrue(ran(new Command.ReleaseLeft()));

        load();
        assertTrue(press("n").mustBeEaten());
        assertTrue(ran(new Command.ToggleLeft()));
        assertFalse(ran(new Command.PressLeft()));

        for (String modeName : List.of("zoom-mode", "edge-mode")) {
            load();
            comboWatcher.modeChanged(modeMap.get(modeName));
            ranCommands.clear();
            press("leftshift");
            ranCommands.clear();
            assertTrue(press("g").mustBeEaten(), modeName);
            assertTrue(ran(new Command.ToggleLeft()), modeName);
            assertFalse(ran(new Command.PressLeft()), modeName);
            ranCommands.clear();
            releaseG = release("g");
            assertTrue(releaseG.mustBeEaten(), modeName);
            assertTrue(releaseG.regurgitates().isEmpty(), modeName);
            assertFalse(ran(new Command.ReleaseLeft()), modeName);
        }
    }

    @Test
    void ctrlStillPassesEdsfThrough() throws Exception {
        load();
        press("leftshift");
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

        // The s that opens the list is not the second row. Pressed again, it is.
        load(true);
        assertTrue(press("s").mustBeEaten());
        assertTrue(ran(new Command.SwitchMode("window-switch-mode")));
        assertFalse(ranTag(Command.ActivateWindowByTag.class));
        release("s");
        assertFalse(ranTag(Command.ActivateWindowByTag.class));
        ranCommands.clear();
        assertTrue(press("s").mustBeEaten());
        assertTrue(ranTag(Command.ActivateWindowByTag.class));
        release("s");

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
        assertFalse(ranTag(Command.ActivateWindowByTag.class));
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
        ranCommands.clear();
        assertTrue(press("enter").mustBeEaten());
        assertTrue(ran(new Command.ActivateSelectedWindow("center-on-active-window-mode")));
        EatAndRegurgitates releaseEnter = release("enter");
        assertTrue(releaseEnter.mustBeEaten());
        assertTrue(releaseEnter.regurgitates().isEmpty());
        assertTrue(press("esc").mustBeEaten());
        assertTrue(ran(new Command.SwitchMode("normal-mode")));
    }

    @Test
    void spaceInTheWindowListStaysInNormalMode() throws Exception {
        load(true);
        comboWatcher.modeChanged(modeMap.get("window-switch-mode"));
        ranCommands.clear();
        assertTrue(press("space").mustBeEaten());
        assertTrue(ran(new Command.ActivateSelectedWindow("window-switch-done-mode")));
        ranCommands.clear();
        EatAndRegurgitates releaseSpace = release("space");
        assertTrue(releaseSpace.mustBeEaten());
        assertTrue(releaseSpace.regurgitates().isEmpty());
        assertTrue(ran(new Command.SwitchMode("normal-mode")));
        assertFalse(ran(new Command.SwitchMode("idle-mode")));
    }

    private static final List<String> MENU_TAGS =
            List.of("a", "s", "f", "g", "c", "w", "v", "r", "x", "q", "z", "t", "b");

    /** The letters a program menu can run. d and g are also move and confirm. */
    private static final List<String> APP_MENU_TAGS =
            List.of("d", "f", "r", "x", "g", "b", "e", "w", "z", "c", "s");

    private boolean ranTag(Class<? extends Command> tagCommand) {
        return ranCommands.stream().anyMatch(tagCommand::isInstance);
    }

    /**
     * What ran, apart from what a key coming up runs to no effect: the noops that eat the
     * release, and the release of a mouse button that is not down (t is the right button).
     */
    private List<Command> ranBesidesReleases() {
        return ranCommands.stream()
                          .filter(command -> !(command instanceof Command.Noop ||
                                               command instanceof Command.ReleaseLeft ||
                                               command instanceof Command.ReleaseMiddle ||
                                               command instanceof Command.ReleaseRight ||
                                               // d is also the wheel key. Coming up in activation
                                               // mode stops a wheel that was never started.
                                               command instanceof Command.StopWheelDown ||
                                               command instanceof Command.StopWheelUp))
                          .toList();
    }

    @Test
    void windowMenuRowsAreTaggedWithLeftHandLetters() throws Exception {
        load();
        Mode windowSwitchMode = modeMap.get("window-switch-mode");
        assertEquals(MENU_TAGS,
                MenuTags.keys(windowSwitchMode, Command.ActivateWindowByTag.class,
                                Command.ActivateWindowByTag::tagKeys)
                        .stream().map(Key::name).toList());
        comboWatcher.modeChanged(windowSwitchMode);
        // The harness does not follow the jump, so the list stays open: this is a tag whose
        // row does not exist. The key is eaten and its release types nothing.
        for (String tag : MENU_TAGS) {
            ranCommands.clear();
            assertTrue(press(tag).mustBeEaten(), tag);
            assertTrue(ranTag(Command.ActivateWindowByTag.class), tag);
            // g also confirms. Both commands are emitted; with a list open, the row wins.
            // e and d only move the highlight.
            if (tag.equals("g")) {
                assertTrue(ran(new Command.ActivateSelectedWindow("center-on-active-window-mode")), tag);
                assertEquals(2, ranCommands.size(), tag);
            }
            else
                assertEquals(1, ranCommands.size(), tag);
            EatAndRegurgitates releaseTag = release(tag);
            assertTrue(releaseTag.mustBeEaten(), tag);
            assertTrue(releaseTag.regurgitates().isEmpty(), tag);
        }
        // e and d move the highlight. k and j do too. None of them is a tag.
        for (String notTag : List.of("e", "d", "k", "j")) {
            ranCommands.clear();
            press(notTag);
            assertFalse(ranTag(Command.ActivateWindowByTag.class), notTag);
            release(notTag);
        }
    }

    /**
     * A tag jumps at once. Activation mode is back only when the letter is up: there the
     * letter is a command of its own (f opens hints, v selects a screen), and its release
     * must not be typed into the window that was just focused.
     */
    @Test
    void aTagInTheWindowListJumpsAndWaitsForTheLetterToComeUp() throws Exception {
        load(true);
        for (String tag : MENU_TAGS) {
            comboWatcher.modeChanged(modeMap.get("window-switch-mode"));
            ranCommands.clear();
            assertTrue(press(tag).mustBeEaten(), tag);
            assertTrue(ranCommands.stream().anyMatch(
                    command -> command instanceof Command.ActivateWindowByTag byTag &&
                               byTag.modeAfter().equals("window-switch-done-mode")), tag);
            int pressed = tag.equals("g") ? 2 : 1;
            assertEquals(pressed, ranCommands.size(), tag);
            // Auto-repeat while the letter is held does nothing.
            assertTrue(press(tag).mustBeEaten(), tag);
            assertEquals(pressed, ranCommands.size(), tag);
            ranCommands.clear();
            EatAndRegurgitates releaseTag = release(tag);
            assertTrue(releaseTag.mustBeEaten(), tag);
            assertTrue(releaseTag.regurgitates().isEmpty(), tag);
            assertEquals(List.of(new Command.SwitchMode("normal-mode")), ranBesidesReleases(), tag);
        }
    }

    @Test
    void aAloneOpensTheProgramMenu() throws Exception {
        load(true);
        assertTrue(press("a").mustBeEaten());
        assertTrue(ran(new Command.SwitchMode("app-menu-mode")));
        // a is not one of Chrome's row keys. The press that opened the menu does not run
        // a row, and neither does a held down, nor a pressed again once the menu is open.
        assertFalse(ranTag(Command.RunMenuItemByTag.class));
        assertTrue(press("a").mustBeEaten());
        assertFalse(ranTag(Command.RunMenuItemByTag.class));
        EatAndRegurgitates releaseA = release("a");
        assertTrue(releaseA.mustBeEaten());
        assertTrue(releaseA.regurgitates().isEmpty());
        assertFalse(ranTag(Command.RunMenuItemByTag.class));
        // a is only eaten on the way in. Pressed again, with the menu already open, it is
        // not a row key, so it reaches the program.
        ranCommands.clear();
        assertFalse(press("a").mustBeEaten());
        assertFalse(ranTag(Command.RunMenuItemByTag.class));
        release("a");

        load();
        press("leftshift");
        ranCommands.clear();
        press("a");
        assertFalse(ran(new Command.SwitchMode("app-menu-mode")));
        release("a");
        release("leftshift");

        // Ctrl+A, Alt+A and Win+A are the program's and the system's.
        for (String modifier : List.of("leftctrl", "rightctrl", "leftalt", "rightalt",
                "leftwin", "rightwin")) {
            load();
            press(modifier);
            ranCommands.clear();
            assertFalse(press("a").mustBeEaten(), modifier);
            assertFalse(ran(new Command.SwitchMode("app-menu-mode")), modifier);
        }

        load();
        comboWatcher.modeChanged(modeMap.get("zoom-mode"));
        ranCommands.clear();
        press("a");
        assertFalse(ran(new Command.SwitchMode("app-menu-mode")));
    }

    @Test
    void programMenuMovesWithEAndDTagsWithLettersAndRunsWithG() throws Exception {
        load(true);
        Mode appMenuMode = modeMap.get("app-menu-mode");
        assertEquals(APP_MENU_TAGS,
                MenuTags.keys(appMenuMode, Command.RunMenuItemByTag.class,
                                Command.RunMenuItemByTag::tagKeys)
                        .stream().map(Key::name).toList());
        comboWatcher.modeChanged(appMenuMode);
        ranCommands.clear();
        // e and d are row keys, so up is k and down is j.
        assertTrue(press("k").mustBeEaten());
        assertTrue(ran(new Command.SelectMenuItem(-1)));
        ranCommands.clear();
        assertTrue(press("j").mustBeEaten());
        assertTrue(ran(new Command.SelectMenuItem(1)));
        assertFalse(ranTag(Command.RunMenuItemByTag.class));
        for (String key : List.of("k", "j")) {
            EatAndRegurgitates releaseKey = release(key);
            assertTrue(releaseKey.mustBeEaten(), key);
            assertTrue(releaseKey.regurgitates().isEmpty(), key);
        }
        // A row key runs its item at once. These letters only do that. e also matches
        // "up", d also matches "down" and g also matches "confirm"; the program runs the
        // row and ignores the other command. Activation mode is back only when the letter is up.
        for (String tag : List.of("f", "r", "x", "b", "w", "z")) {
            comboWatcher.modeChanged(appMenuMode);
            ranCommands.clear();
            assertTrue(press(tag).mustBeEaten(), tag);
            assertEquals(1, ranCommands.size(), tag);
            assertTrue(ranCommands.getFirst() instanceof Command.RunMenuItemByTag byTag &&
                       byTag.modeAfter().equals("app-menu-done-mode"), tag);
            // Auto-repeat while the letter is held does nothing.
            assertTrue(press(tag).mustBeEaten(), tag);
            assertEquals(1, ranCommands.size(), tag);
            ranCommands.clear();
            EatAndRegurgitates releaseTag = release(tag);
            assertTrue(releaseTag.mustBeEaten(), tag);
            assertTrue(releaseTag.regurgitates().isEmpty(), tag);
            assertEquals(List.of(new Command.SwitchMode("normal-mode")), ranBesidesReleases(), tag);
        }

        // e, d and g match a row and a navigation key. Both commands are emitted; the row
        // wins over moving or confirming.
        comboWatcher.modeChanged(appMenuMode);
        ranCommands.clear();
        assertTrue(press("e").mustBeEaten());
        assertTrue(ran(new Command.SelectMenuItem(-1)));
        assertTrue(ranTag(Command.RunMenuItemByTag.class));
        assertTrue(press("e").mustBeEaten());
        assertEquals(2, ranCommands.size());
        ranCommands.clear();
        EatAndRegurgitates releaseE = release("e");
        assertTrue(releaseE.mustBeEaten());
        assertTrue(releaseE.regurgitates().isEmpty());
        assertEquals(List.of(new Command.SwitchMode("normal-mode")), ranBesidesReleases());

        comboWatcher.modeChanged(appMenuMode);
        ranCommands.clear();
        assertTrue(press("d").mustBeEaten());
        assertTrue(ran(new Command.SelectMenuItem(1)));
        assertTrue(ranTag(Command.RunMenuItemByTag.class));
        assertTrue(press("d").mustBeEaten());
        assertEquals(2, ranCommands.size());
        ranCommands.clear();
        EatAndRegurgitates releaseD = release("d");
        assertTrue(releaseD.mustBeEaten());
        assertTrue(releaseD.regurgitates().isEmpty());
        assertEquals(List.of(new Command.SwitchMode("normal-mode")), ranBesidesReleases());

        comboWatcher.modeChanged(appMenuMode);
        ranCommands.clear();
        assertTrue(press("g").mustBeEaten());
        assertTrue(ran(new Command.RunSelectedMenuItem("app-menu-done-mode")));
        assertTrue(ranTag(Command.RunMenuItemByTag.class));
        assertTrue(press("g").mustBeEaten());
        assertEquals(2, ranCommands.size());
        ranCommands.clear();
        EatAndRegurgitates releaseG = release("g");
        assertTrue(releaseG.mustBeEaten());
        assertTrue(releaseG.regurgitates().isEmpty());
        assertEquals(List.of(new Command.SwitchMode("normal-mode")), ranBesidesReleases());
        assertFalse(ran(new Command.PressLeft()));

        // Space and Enter run the highlighted item. g does not: it is a row key.
        for (String runKey : List.of("space", "enter", "numpadenter")) {
            comboWatcher.modeChanged(appMenuMode);
            ranCommands.clear();
            assertTrue(press(runKey).mustBeEaten(), runKey);
            assertEquals(List.of(new Command.RunSelectedMenuItem("app-menu-done-mode")),
                    ranCommands, runKey);
            // Auto-repeat while the key is held does nothing.
            assertTrue(press(runKey).mustBeEaten(), runKey);
            assertEquals(1, ranCommands.size(), runKey);
            ranCommands.clear();
            EatAndRegurgitates releaseRunKey = release(runKey);
            assertTrue(releaseRunKey.mustBeEaten(), runKey);
            assertTrue(releaseRunKey.regurgitates().isEmpty(), runKey);
            assertTrue(ran(new Command.SwitchMode("normal-mode")), runKey);
            assertFalse(ran(new Command.SwitchMode("idle-mode")), runKey);
            assertFalse(ran(new Command.PressLeft()), runKey);
        }

        comboWatcher.modeChanged(appMenuMode);
        ranCommands.clear();
        assertTrue(press("esc").mustBeEaten());
        assertTrue(ran(new Command.SwitchMode("normal-mode")));
        assertFalse(ranCommands.stream().anyMatch(
                command -> command instanceof Command.RunSelectedMenuItem));
    }

    @Test
    void zoomModeUsesTheSameHandMovement() throws Exception {
        load();
        comboWatcher.modeChanged(modeMap.get("zoom-mode"));
        ranCommands.clear();
        assertTrue(press("leftshift").mustBeEaten());
        assertTrue(press("e").mustBeEaten());
        assertTrue(ran(new Command.StartMoveUp()));
        assertFalse(ran(new Command.StartWheelUp()));
    }
}
