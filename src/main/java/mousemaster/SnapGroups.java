package mousemaster;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Windows that together fill one screen, the way a snap layout does.
 * Alt-Tab shows that layout as one entry. The shell's snap-group id is not a stable
 * public call, so a layout is recognized by its rectangles: the panes do not overlap,
 * and their union is the monitor's work area.
 * Minimized windows are left alone. A window belongs to at most one layout.
 */
public final class SnapGroups {

    /** At most the built-in snap layouts. Larger sets are not searched. */
    private static final int MAX_PANES = 4;
    /** Recent windows only. A layout the user just left is among these. */
    private static final int MAX_CANDIDATES = 8;

    private SnapGroups() {
    }

    /**
     * One window in Alt-Tab order. Coordinates are the visible frame and the work area
     * of the monitor the window is on. {@code iconic} windows are never put in a layout.
     */
    public record Pane(int left, int top, int right, int bottom,
                       int workLeft, int workTop, int workRight, int workBottom,
                       boolean iconic) {
    }

    /**
     * One list row per layout, in the order of the front-most window of each row.
     * A row of one index is a window that is not part of a layout. The first index of
     * a longer row is the window that should take focus.
     */
    public static List<List<Integer>> group(List<Pane> panes) {
        int count = panes.size();
        boolean[] taken = new boolean[count];
        List<List<Integer>> layouts = new ArrayList<>();
        Map<Work, List<Integer>> byScreen = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            Pane pane = panes.get(index);
            if (pane.iconic() || area(pane.left, pane.top, pane.right, pane.bottom) <= 0)
                continue;
            Work work = new Work(pane.workLeft, pane.workTop, pane.workRight, pane.workBottom);
            if (work.area() <= 0)
                continue;
            byScreen.computeIfAbsent(work, key -> new ArrayList<>()).add(index);
        }
        for (Map.Entry<Work, List<Integer>> screen : byScreen.entrySet()) {
            List<Integer> pool = new ArrayList<>(screen.getValue());
            while (pool.size() >= 2) {
                List<Integer> layout = firstLayout(panes, pool, screen.getKey());
                if (layout == null)
                    break;
                for (int index : layout)
                    taken[index] = true;
                layouts.add(layout);
                pool.removeAll(layout);
            }
        }
        layouts.sort(Comparator.comparingInt(List::getFirst));
        List<List<Integer>> rows = new ArrayList<>();
        int cursor = 0;
        for (int index = 0; index < count; index++) {
            if (cursor < layouts.size() && layouts.get(cursor).getFirst() == index) {
                rows.add(layouts.get(cursor));
                cursor++;
            }
            else if (!taken[index])
                rows.add(List.of(index));
        }
        return rows;
    }

    /** The first layout in most-recent order, preferring more panes. */
    private static List<Integer> firstLayout(List<Pane> panes, List<Integer> pool, Work work) {
        int candidates = Math.min(pool.size(), MAX_CANDIDATES);
        for (int size = Math.min(MAX_PANES, candidates); size >= 2; size--) {
            int[] picked = new int[size];
            if (search(panes, pool, candidates, work, size, 0, picked, 0)) {
                List<Integer> layout = new ArrayList<>(size);
                for (int index : picked)
                    layout.add(index);
                return layout;
            }
        }
        return null;
    }

    private static boolean search(List<Pane> panes, List<Integer> pool, int candidates,
                                  Work work, int size, int start, int[] picked, int filled) {
        if (filled == size)
            return tiles(panes, picked, work);
        for (int cursor = start; cursor <= candidates - (size - filled); cursor++) {
            picked[filled] = pool.get(cursor);
            if (search(panes, pool, candidates, work, size, cursor + 1, picked, filled + 1))
                return true;
        }
        return false;
    }

    private static boolean tiles(List<Pane> panes, int[] picked, Work work) {
        long workArea = work.area();
        int tolerance = tolerance(work);
        int unionLeft = Integer.MAX_VALUE;
        int unionTop = Integer.MAX_VALUE;
        int unionRight = Integer.MIN_VALUE;
        int unionBottom = Integer.MIN_VALUE;
        long sum = 0;
        for (int index : picked) {
            Pane pane = panes.get(index);
            long paneArea = area(pane.left, pane.top, pane.right, pane.bottom);
            if (paneArea * 100 < workArea * 8)
                return false;
            if (pane.left < work.left - tolerance || pane.top < work.top - tolerance ||
                pane.right > work.right + tolerance || pane.bottom > work.bottom + tolerance)
                return false;
            unionLeft = Math.min(unionLeft, pane.left);
            unionTop = Math.min(unionTop, pane.top);
            unionRight = Math.max(unionRight, pane.right);
            unionBottom = Math.max(unionBottom, pane.bottom);
            sum += paneArea;
        }
        if (Math.abs(unionLeft - work.left) > tolerance ||
            Math.abs(unionTop - work.top) > tolerance ||
            Math.abs(unionRight - work.right) > tolerance ||
            Math.abs(unionBottom - work.bottom) > tolerance)
            return false;
        if (sum * 100 < workArea * 82 || sum * 100 > workArea * 115)
            return false;
        for (int i = 0; i < picked.length; i++) {
            for (int j = i + 1; j < picked.length; j++) {
                Pane a = panes.get(picked[i]);
                Pane b = panes.get(picked[j]);
                long overlap = overlap(a, b);
                long smaller = Math.min(area(a.left, a.top, a.right, a.bottom),
                        area(b.left, b.top, b.right, b.bottom));
                if (overlap * 100 > smaller * 8)
                    return false;
            }
        }
        return true;
    }

    private static int tolerance(Work work) {
        int smaller = Math.min(work.right - work.left, work.bottom - work.top);
        return Math.max(16, smaller / 80);
    }

    private static long area(int left, int top, int right, int bottom) {
        return Math.max(0L, (long) right - left) * Math.max(0L, (long) bottom - top);
    }

    private static long overlap(Pane a, Pane b) {
        int left = Math.max(a.left, b.left);
        int top = Math.max(a.top, b.top);
        int right = Math.min(a.right, b.right);
        int bottom = Math.min(a.bottom, b.bottom);
        return area(left, top, right, bottom);
    }

    private record Work(int left, int top, int right, int bottom) {
        private long area() {
            return SnapGroups.area(left, top, right, bottom);
        }
    }

}
