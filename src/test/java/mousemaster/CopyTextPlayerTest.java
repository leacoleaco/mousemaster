package mousemaster;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CopyTextPlayerTest {

    @Test
    void oneTapSelectsTheWordWithADoubleClickThenCopies() {
        List<String> events = new ArrayList<>();
        CopyTextPlayer player = player(events);
        player.start(Command.CopyText.Kind.WORD);
        assertEquals(List.of("release", "halt"), events);

        events.clear();
        player.update(0);
        assertEquals(List.of("pattern-word", "press"), events);
        player.update(CopyTextPlayer.HOLD_SECONDS);
        assertEquals(List.of("pattern-word", "press", "release"), events);
        player.update(CopyTextPlayer.BETWEEN_CLICKS_SECONDS);
        assertEquals(List.of("pattern-word", "press", "release", "press"), events);
        player.update(CopyTextPlayer.HOLD_SECONDS);
        assertEquals(List.of("pattern-word", "press", "release", "press", "release"), events);
        player.update(CopyTextPlayer.BEFORE_KEYS_SECONDS);
        assertEquals(List.of("pattern-word", "press", "release", "press", "release", "copy"), events);
    }

    @Test
    void textPatternCopiesWithoutClicking() {
        for (Command.CopyText.Kind kind : Command.CopyText.Kind.values()) {
            List<String> events = new ArrayList<>();
            CopyTextPlayer player = player(events, true);
            player.start(kind);
            assertEquals(List.of("release", "halt"), events);
            events.clear();
            player.update(0);
            assertEquals(List.of("pattern-" + kind.name().toLowerCase(), "copied"), events);
            player.update(1);
            assertEquals(List.of("pattern-" + kind.name().toLowerCase(), "copied"), events);
        }
    }

    @Test
    void twoTapsSelectTheLineWithATripleClickThenCopy() {
        List<String> events = play(Command.CopyText.Kind.LINE);
        assertEquals(List.of(
                "pattern-line",
                "press", "release",
                "press", "release",
                "press", "release",
                "copy"), events);
    }

    @Test
    void threeTapsFocusTheTargetThenSelectAllAndCopy() {
        List<String> events = play(Command.CopyText.Kind.ALL);
        assertEquals(List.of("pattern-all", "press", "release", "select-all", "copy"), events);
    }

    private static List<String> play(Command.CopyText.Kind kind) {
        List<String> events = new ArrayList<>();
        CopyTextPlayer player = player(events, false);
        player.start(kind);
        events.clear();
        player.update(0);
        player.update(1);
        return events;
    }

    private static CopyTextPlayer player(List<String> events) {
        return player(events, false);
    }

    private static CopyTextPlayer player(List<String> events, boolean patternCopies) {
        return new CopyTextPlayer(new CopyTextPlayer.Output() {
            @Override
            public void releaseLeft() {
                events.add("release");
            }

            @Override
            public void haltMovement() {
                events.add("halt");
            }

            @Override
            public void pressLeft() {
                events.add("press");
            }

            @Override
            public void selectAll() {
                events.add("select-all");
            }

            @Override
            public boolean copyText(Command.CopyText.Kind kind) {
                events.add("pattern-" + kind.name().toLowerCase());
                return patternCopies;
            }

            @Override
            public void copy() {
                events.add("copy");
            }

            @Override
            public void showCopied() {
                events.add("copied");
            }
        });
    }
}
