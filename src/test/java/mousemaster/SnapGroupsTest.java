package mousemaster;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SnapGroupsTest {

    @Test
    void leftAndRightHalvesAreOneRowAndTheRestStaySingle() {
        List<SnapGroups.Pane> panes = List.of(
                full(1920, 0, 3840, 1080, 1920, 0),
                pane(0, 0, 960, 1080),
                pane(960, 0, 1920, 1080),
                pane(100, 100, 400, 400));
        assertEquals(List.of(List.of(0), List.of(1, 2), List.of(3)), SnapGroups.group(panes));
    }

    @Test
    void theFrontWindowOfALayoutIsTheOneThatTakesFocus() {
        assertEquals(List.of(List.of(0, 1)),
                SnapGroups.group(List.of(pane(960, 0, 1920, 1080), pane(0, 0, 960, 1080))));
    }

    @Test
    void topAndBottomAndColumnsAndQuartersAreLayouts() {
        assertEquals(List.of(List.of(0, 1)), SnapGroups.group(List.of(
                pane(0, 0, 1920, 540), pane(0, 540, 1920, 1080))));
        assertEquals(List.of(List.of(0, 1, 2)), SnapGroups.group(List.of(
                pane(0, 0, 640, 1080), pane(640, 0, 1280, 1080), pane(1280, 0, 1920, 1080))));
        assertEquals(List.of(List.of(0, 1, 2, 3)), SnapGroups.group(List.of(
                pane(0, 0, 960, 540), pane(960, 0, 1920, 540),
                pane(0, 540, 960, 1080), pane(960, 540, 1920, 1080))));
    }

    @Test
    void aGapOfAFewPixelsStillCountsAsSnapped() {
        assertEquals(List.of(List.of(0, 1)), SnapGroups.group(List.of(
                pane(0, 0, 950, 1080), pane(960, 0, 1920, 1080))));
    }

    @Test
    void aHalfWithoutAPartnerAndAnOverlapStaySeparate() {
        assertEquals(List.of(List.of(0), List.of(1)), SnapGroups.group(List.of(
                pane(0, 0, 960, 1080), pane(100, 100, 800, 700))));
        assertEquals(List.of(List.of(0), List.of(1)), SnapGroups.group(List.of(
                pane(0, 0, 1200, 1080), pane(800, 0, 1920, 1080))));
    }

    @Test
    void anotherMonitorIsItsOwnLayout() {
        SnapGroups.Pane left = pane(0, 0, 960, 1080);
        SnapGroups.Pane onTheOther = new SnapGroups.Pane(
                1920, 0, 2880, 1080, 1920, 0, 3840, 1080, false);
        SnapGroups.Pane right = pane(960, 0, 1920, 1080);
        assertEquals(List.of(List.of(0, 2), List.of(1)),
                SnapGroups.group(List.of(left, onTheOther, right)));
    }

    @Test
    void aMinimizedWindowIsNotPartOfTheLayout() {
        SnapGroups.Pane minimized = new SnapGroups.Pane(0, 0, 960, 1080, 0, 0, 1920, 1080, true);
        assertEquals(List.of(List.of(0), List.of(1)),
                SnapGroups.group(List.of(minimized, pane(960, 0, 1920, 1080))));
    }

    @Test
    void aDialogBetweenTheHalvesDoesNotSplitTheLayout() {
        assertEquals(List.of(List.of(0, 2), List.of(1)), SnapGroups.group(List.of(
                pane(0, 0, 960, 1080),
                pane(400, 200, 700, 500),
                pane(960, 0, 1920, 1080))));
    }

    private static SnapGroups.Pane pane(int left, int top, int right, int bottom) {
        return new SnapGroups.Pane(left, top, right, bottom, 0, 0, 1920, 1080, false);
    }

    private static SnapGroups.Pane full(int left, int top, int right, int bottom,
                                        int workLeft, int workTop) {
        return new SnapGroups.Pane(left, top, right, bottom,
                workLeft, workTop, workLeft + 1920, workTop + 1080, false);
    }

}
