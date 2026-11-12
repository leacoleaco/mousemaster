package mousemaster;

import mousemaster.platform.SwitchableWindows;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * a opens the menu of the program in front. The shipped configuration gives Chrome one.
 * The rows of that menu, and of the window list, are tagged with the menutag letters.
 */
class AppMenuTest {

    private static Configuration shipped() throws Exception {
        return ConfigurationParser.parse(
                PropertiesReader.readPropertiesFile(Files.newBufferedReader(
                        Path.of("configuration/mousemaster.properties"))),
                KeyboardLayout.keyboardLayout("00000409", null));
    }

    private static String output(ResolvedMacro macro) {
        List<String> parts = new ArrayList<>();
        for (ResolvedMacroParallel parallel : macro.output().parallels()) {
            for (ResolvedMacroMove move : parallel.moves())
                parts.add(switch (move) {
                    case ResolvedKeyMacroMove key -> {
                        assertEquals(MacroMoveDestination.OS, key.destination());
                        yield (key.press() ? "+" : "-") + key.key().name();
                    }
                    case StringMacroMove string -> "'" + string.string() + "'";
                });
            if (!parallel.duration().isZero())
                parts.add("wait-" + parallel.duration().toMillis());
        }
        return String.join(" ", parts);
    }

    private static Key key(String character) {
        return Key.ofCharacter(character);
    }

    @Test
    void chromeMenuListsTheNineItemsInOrder() throws Exception {
        Configuration configuration = shipped();
        assertEquals(1, configuration.appMenus().size());
        AppMenuConfiguration chrome = configuration.appMenus().getFirst();
        assertEquals("Chrome", chrome.title());
        assertTrue(chrome.matches(new App("chrome.exe")));
        assertFalse(chrome.matches(new App("notepad.exe")));
        assertFalse(chrome.matches(null));
        assertEquals(List.of("刷新", "后退", "前进", "前一个标签页", "后一个标签页", "打开百度",
                        "打开 Google", "恢复标签页", "打开历史"),
                chrome.items().stream().map(AppMenuConfiguration.Item::label).toList());
    }

    @Test
    void eachChromeItemSendsItsShortcut() throws Exception {
        Configuration configuration = shipped();
        AppMenus menus = new AppMenus(configuration.appMenus(), () -> new App("chrome.exe"));
        menus.modeChanged(configuration.modeMap().get(AppMenus.MODE));
        List<String> outputs = new ArrayList<>();
        for (int item = 0; item < 9; item++) {
            outputs.add(output(menus.selectedMacro()));
            menus.move(1);
        }
        assertEquals(List.of(
                "+f5 -f5",
                "+leftalt +leftarrow -leftarrow -leftalt",
                "+leftalt +rightarrow -rightarrow -leftalt",
                "+leftctrl +rightshift +tab -tab -rightshift -leftctrl",
                "+leftctrl +tab -tab -leftctrl",
                "+leftctrl +t -t -leftctrl wait-200 'https://www.baidu.com/' wait-50 +del -del +enter -enter",
                "+leftctrl +t -t -leftctrl wait-200 'https://www.google.com/' wait-50 +del -del +enter -enter",
                "+leftctrl +rightshift +t -t -rightshift -leftctrl",
                "+leftctrl +h -h -leftctrl"), outputs);
        // Down from the last item wraps to the first.
        assertEquals("+f5 -f5", output(menus.selectedMacro()));
    }

    /** What the real command runner did: the macros it queued and the modes it entered. */
    private static final class Ran {
        final List<String> macros = new ArrayList<>();
        final List<String> modes = new ArrayList<>();
        final CommandRunner runner = new CommandRunner(null, null, null);

        Ran() {
            runner.setModeController(
                    new ModeController(null, null, null, null, null, null, null) {
                        @Override
                        public void switchMode(String newModeName) {
                            modes.add(newModeName);
                        }
                    });
            runner.setMacroPlayer(new MacroPlayer(null, null, null, null, null, null) {
                @Override
                public void submit(ResolvedMacro macro) {
                    macros.add(output(macro));
                }
            });
        }
    }

    private static <C extends Command> C command(Mode mode, Class<C> type) {
        return mode.comboMap().commandsByCombo().values().stream()
                   .flatMap(List::stream)
                   .filter(type::isInstance)
                   .map(type::cast)
                   .findFirst()
                   .orElseThrow();
    }

    @Test
    void aTagRunsItsItemAtOnce() throws Exception {
        Configuration configuration = shipped();
        Mode menuMode = configuration.modeMap().get(AppMenus.MODE);
        Command.RunMenuItemByTag byTag = command(menuMode, Command.RunMenuItemByTag.class);
        assertEquals("app-menu-done-mode", byTag.modeAfter());
        App[] front = {new App("chrome.exe")};
        AppMenus menus = new AppMenus(configuration.appMenus(), () -> front[0]);
        Ran ran = new Ran();
        ran.runner.setAppMenus(menus);
        menus.modeChanged(menuMode);

        // w is the fourth row. No second key is needed.
        ran.runner.run(byTag, key("w"));
        assertEquals(List.of("+leftctrl +rightshift +tab -tab -rightshift -leftctrl"), ran.macros);
        assertEquals(List.of("app-menu-done-mode"), ran.modes);

        // t and b are tag keys, but the menu has no tenth row. e is not a tag key.
        // None of them runs anything, and the menu stays open.
        ran.runner.run(byTag, key("t"));
        ran.runner.run(byTag, key("e"));
        ran.runner.run(byTag, null);
        assertEquals(1, ran.macros.size());
        assertEquals(1, ran.modes.size());

        ran.runner.run(byTag, key("z"));
        assertEquals("+leftctrl +h -h -leftctrl", ran.macros.getLast());
        assertEquals(List.of("app-menu-done-mode", "app-menu-done-mode"), ran.modes);

        // A program with no menu has no tags. g there only closes the note.
        front[0] = new App("notepad.exe");
        menus.modeChanged(configuration.modeMap().get("normal-mode"));
        menus.modeChanged(menuMode);
        ran.runner.run(byTag, key("f"));
        assertEquals(2, ran.macros.size());
        assertEquals(2, ran.modes.size());
        ran.runner.run(new Command.RunSelectedMenuItem("app-menu-done-mode"), key("g"));
        assertEquals(2, ran.macros.size());
        assertEquals(3, ran.modes.size());
    }

    @Test
    void itemsAreTaggedAndATagMovesTheHighlight() throws Exception {
        Configuration configuration = shipped();
        AppMenus menus = new AppMenus(configuration.appMenus(), () -> new App("chrome.exe"));
        menus.modeChanged(configuration.modeMap().get(AppMenus.MODE));
        String html = menus.html();
        assertTrue(html.contains("class=title>Chrome</td>"));
        assertTrue(html.contains("字母 直接执行"));
        List<String> tags = List.of("F", "C", "A", "W", "V", "R", "X", "Q", "Z");
        List<String> labels = List.of("刷新", "后退", "前进", "前一个标签页", "后一个标签页",
                "打开百度", "打开 Google", "恢复标签页", "打开历史");
        int from = 0;
        for (int row = 0; row < tags.size(); row++) {
            int tag = html.indexOf(">" + tags.get(row) + "</td>", from);
            int label = html.indexOf(">" + labels.get(row) + "</td>", from);
            assertTrue(tag > 0 && label > tag, tags.get(row) + " " + labels.get(row));
            from = label;
        }
        // The first row is highlighted until a tag is pressed.
        assertTrue(html.contains("class=itemon bgcolor=\"#7AA2F7\">刷新</td>"));

        assertTrue(menus.selectTag(key("w")));
        assertTrue(menus.html().contains("class=itemon bgcolor=\"#7AA2F7\">前一个标签页</td>"));
        assertEquals("+leftctrl +rightshift +tab -tab -rightshift -leftctrl",
                output(menus.selectedMacro()));
        assertTrue(menus.selectTag(key("z")));
        assertEquals("+leftctrl +h -h -leftctrl", output(menus.selectedMacro()));
        // t and b are tag keys, but the menu has no tenth row. e is not a tag key.
        assertFalse(menus.selectTag(key("t")));
        assertFalse(menus.selectTag(key("e")));
        assertFalse(menus.selectTag(null));
        assertEquals("+leftctrl +h -h -leftctrl", output(menus.selectedMacro()));
        assertTrue(menus.selectTag(key("f")));
        assertEquals("+f5 -f5", output(menus.selectedMacro()));
    }

    @Test
    void theMenuIsTheOneOfTheProgramInFrontWhenItOpens() throws Exception {
        Configuration configuration = shipped();
        App[] front = {new App("notepad.exe")};
        AppMenus menus = new AppMenus(configuration.appMenus(), () -> front[0]);
        menus.modeChanged(configuration.modeMap().get(AppMenus.MODE));
        assertTrue(menus.isEmpty());
        assertNull(menus.selectedMacro());
        assertTrue(menus.html().contains("notepad.exe 还没有菜单"));
        assertFalse(menus.html().contains("刷新"));
        menus.move(1);
        assertFalse(menus.selectTag(key("f")));
        assertNull(menus.selectedMacro());

        // Chrome coming to the front does not change a menu that is already open.
        front[0] = new App("chrome.exe");
        menus.modeChanged(configuration.modeMap().get(AppMenus.MODE));
        assertTrue(menus.isEmpty());
        menus.modeChanged(configuration.modeMap().get("normal-mode"));
        menus.modeChanged(configuration.modeMap().get(AppMenus.MODE));
        assertFalse(menus.isEmpty());

        // The highlight starts over each time the menu opens.
        menus.selectTag(key("c"));
        menus.modeChanged(configuration.modeMap().get("normal-mode"));
        menus.modeChanged(configuration.modeMap().get(AppMenus.MODE));
        assertEquals("+f5 -f5", output(menus.selectedMacro()));

        front[0] = null;
        menus.modeChanged(configuration.modeMap().get("normal-mode"));
        menus.modeChanged(configuration.modeMap().get(AppMenus.MODE));
        assertTrue(menus.html().contains("当前程序还没有菜单"));
    }

    /** A window list of {@code count} windows that records which ones were focused. */
    private static WindowSwitcher switcher(int count, List<Integer> activated,
                                           boolean[] focusAllowed) {
        SwitchableWindows windows = new SwitchableWindows() {
            @Override
            public List<WindowEntry> entries() {
                List<WindowEntry> entries = new ArrayList<>();
                for (int index = 0; index < count; index++)
                    entries.add(new WindowEntry("窗口" + index, index + ".exe", null));
                return entries;
            }

            @Override
            public boolean activate(int index) {
                activated.add(index);
                return focusAllowed[0];
            }
        };
        Platform platform = (Platform) Proxy.newProxyInstance(
                Platform.class.getClassLoader(), new Class<?>[]{Platform.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "switchableWindows" -> windows;
                    case "inKeyboardHook" -> false;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return new WindowSwitcher(platform);
    }

    @Test
    void aTagInTheWindowListJumpsToThatWindowAtOnce() throws Exception {
        Configuration configuration = shipped();
        Mode listMode = configuration.modeMap().get(WindowSwitcher.MODE);
        Command.ActivateWindowByTag byTag =
                command(listMode, Command.ActivateWindowByTag.class);
        assertEquals("window-switch-done-mode", byTag.modeAfter());
        List<Integer> activated = new ArrayList<>();
        boolean[] focusAllowed = {true};
        WindowSwitcher switcher = switcher(4, activated, focusAllowed);
        Ran ran = new Ran();
        ran.runner.setWindowSwitcher(switcher);
        switcher.modeChanged(listMode);

        // Until the list has been drawn there is no row, so a tag does nothing.
        ran.runner.run(byTag, key("f"));
        assertTrue(activated.isEmpty());
        assertTrue(ran.modes.isEmpty());

        String html = switcher.html();
        assertEquals(List.of("F", "C", "A", "W"),
                List.of("F", "C", "A", "W", "V").stream()
                    .filter(tag -> html.contains(">" + tag + "</td>"))
                    .collect(Collectors.toList()));

        // w is the fourth row. No second key is needed.
        ran.runner.run(byTag, key("w"));
        assertEquals(List.of(3), activated);
        assertEquals(List.of("window-switch-done-mode"), ran.modes);
        ran.runner.run(byTag, key("f"));
        assertEquals(List.of(3, 0), activated);
        assertEquals(2, ran.modes.size());

        // v tags a fifth row, which this list does not have. e is not a tag key.
        ran.runner.run(byTag, key("v"));
        ran.runner.run(byTag, key("e"));
        ran.runner.run(byTag, null);
        assertEquals(List.of(3, 0), activated);
        assertEquals(2, ran.modes.size());

        // Windows refused the focus: the list stays open, with the highlight on that row.
        focusAllowed[0] = false;
        ran.runner.run(byTag, key("a"));
        assertEquals(List.of(3, 0, 2), activated);
        assertEquals(2, ran.modes.size());
        // e and d step from where the tag left the highlight.
        focusAllowed[0] = true;
        switcher.move(-1);
        assertTrue(switcher.activateSelected());
        assertEquals(List.of(3, 0, 2, 1), activated);
    }

    @Test
    void aTagThatScrolledOutOfSightJumpsNowhere() throws Exception {
        Configuration configuration = shipped();
        Mode listMode = configuration.modeMap().get(WindowSwitcher.MODE);
        Command.ActivateWindowByTag byTag =
                command(listMode, Command.ActivateWindowByTag.class);
        List<Integer> activated = new ArrayList<>();
        WindowSwitcher switcher = switcher(20, activated, new boolean[]{true});
        Ran ran = new Ran();
        ran.runner.setWindowSwitcher(switcher);
        switcher.modeChanged(listMode);
        switcher.html();
        // Down to the fifteenth window: the list now starts at the ninth.
        for (int step = 0; step < 13; step++)
            switcher.move(1);
        String html = switcher.html();
        assertFalse(html.contains(">F</td>"));
        assertTrue(html.contains(">B</td>"));

        // f tags the first window, which is no longer drawn. b tags the eleventh, which is.
        ran.runner.run(byTag, key("f"));
        assertTrue(activated.isEmpty());
        assertTrue(ran.modes.isEmpty());
        ran.runner.run(byTag, key("b"));
        assertEquals(List.of(10), activated);
        assertEquals(List.of("window-switch-done-mode"), ran.modes);
    }

    @Test
    void aMenuNeedsItsAppAliasAndAnOutput() {
        KeyboardLayout layout = KeyboardLayout.keyboardLayoutByShortName.get("us-qwerty");
        List<String> modes = List.of("idle-mode.to.other-mode=+a", "other-mode.to.idle-mode=+esc");
        List<String> noAlias = new ArrayList<>(modes);
        noAlias.add("app-menu.editor.item.save=保存 -> +leftctrl +s -s -leftctrl");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ConfigurationParser.parse(noAlias, layout)).getMessage()
                                                                 .contains("app-alias.editor"));

        List<String> noOutput = new ArrayList<>(modes);
        noOutput.add("app-alias.editor=editor.exe");
        noOutput.add("app-menu.editor.item.save=保存");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ConfigurationParser.parse(noOutput, layout)).getMessage()
                                                                  .contains("<label> -> <macro output>"));

        List<String> titleOnly = new ArrayList<>(modes);
        titleOnly.add("app-alias.editor=editor.exe");
        titleOnly.add("app-menu.editor.title=编辑器");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ConfigurationParser.parse(titleOnly, layout)).getMessage()
                                                                   .contains("has no item"));

        List<String> valid = new ArrayList<>(modes);
        valid.add("app-alias.editor=editor.exe other.exe");
        valid.add("app-menu.editor.item.save=保存 -> +leftctrl +s -s -leftctrl");
        valid.add("app-menu.editor.item.close=关闭 -> +leftalt +f4 -f4 -leftalt");
        Configuration configuration = ConfigurationParser.parse(valid, layout);
        AppMenuConfiguration editor = configuration.appMenus().getFirst();
        // Without a title line, the menu is headed with the alias.
        assertEquals("editor", editor.title());
        assertTrue(editor.matches(new App("other.exe")));
        assertEquals(List.of("保存", "关闭"),
                editor.items().stream().map(AppMenuConfiguration.Item::label).toList());
        // A configuration with no app-menu line has no menu.
        assertTrue(ConfigurationParser.parse(modes, layout).appMenus().isEmpty());
    }

}
