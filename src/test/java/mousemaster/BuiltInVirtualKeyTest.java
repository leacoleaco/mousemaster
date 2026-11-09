package mousemaster;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The keys mousemaster presses itself: readable, but not writable. */
class BuiltInVirtualKeyTest {

    private static final List<Key> KEYS =
            List.of(BuiltInVirtualKey.IS_IDLING, BuiltInVirtualKey.IS_MOVING,
                    BuiltInVirtualKey.IS_MOVING_UP, BuiltInVirtualKey.IS_MOVING_DOWN,
                    BuiltInVirtualKey.IS_MOVING_LEFT, BuiltInVirtualKey.IS_MOVING_RIGHT,
                    BuiltInVirtualKey.IS_WHEELING, BuiltInVirtualKey.IS_WHEELING_UP,
                    BuiltInVirtualKey.IS_WHEELING_DOWN, BuiltInVirtualKey.IS_WHEELING_LEFT,
                    BuiltInVirtualKey.IS_WHEELING_RIGHT, BuiltInVirtualKey.IS_MOUSE_PRESSING,
                    BuiltInVirtualKey.IS_LEFT_MOUSE_PRESSING,
                    BuiltInVirtualKey.IS_MIDDLE_MOUSE_PRESSING,
                    BuiltInVirtualKey.IS_RIGHT_MOUSE_PRESSING,
                    BuiltInVirtualKey.IS_UNHANDLED_KEY_PRESSING,
                    BuiltInVirtualKey.IS_OVERLAY_EMPTY);

    private static Configuration parse(String... lines) {
        return ConfigurationParser.parse(List.of(lines),
                KeyboardLayout.keyboardLayout("00000409", null));
    }

    @Test
    void theyAreDeclaredWithoutAVirtualKeysLine() {
        for (Key key : KEYS) {
            Configuration configuration = parse("idle-mode.indicator.fill-color=#FF0000 | _{" +
                                                key.name() + "} -> #00FF00");
            assertTrue(configuration.virtualKeys().contains(key), key.name());
            assertFalse(configuration.initiallyPressedVirtualKeys().contains(key),
                    key.name());
        }
    }

    @Test
    void ishintmeshemptyIsTheDeprecatedNameOfIsoverlayempty() {
        assertTrue(parse("idle-mode.indicator.fill-color=#FF0000 | _{ishintmeshempty} -> #00FF00")
                .virtualKeys().contains(BuiltInVirtualKey.IS_OVERLAY_EMPTY));
    }

    @Test
    void theyAreNotVariables() {
        for (Key key : KEYS) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> parse("some-mode.set-variable." + key.name() + "=+a"));
            assertTrue(e.getMessage().contains(key.name()), e.getMessage());
        }
    }

    @Test
    void theyCannotBePressedByAMacro() {
        for (Key key : KEYS) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> parse("idle-mode.macro.x=+a -> #" + key.name()));
            assertTrue(e.getMessage().contains("built-in"), e.getMessage());
        }
    }
}
