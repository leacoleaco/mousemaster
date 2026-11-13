package mousemaster;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pictures of one region, taken while it scrolls, are joined into one tall picture.
 */
class ImageStitcherTest {

    private static final int WIDTH = 180;
    private static final int HEIGHT = 240;

    /** A page of text-like rows: every row differs from the others. */
    private static int[] page(int rows, long seed) {
        Random random = new Random(seed);
        int[] page = new int[WIDTH * rows];
        for (int index = 0; index < page.length; index++)
            page[index] = 0xFF000000 | random.nextInt(0x1000000);
        return page;
    }

    /** What the region shows when the page is scrolled down by {@code offset} rows. */
    private static int[] view(int[] page, int offset) {
        int[] view = new int[WIDTH * HEIGHT];
        System.arraycopy(page, offset * WIDTH, view, 0, view.length);
        return view;
    }

    private static int[] rows(int[] page, int from, int to) {
        int[] rows = new int[(to - from) * WIDTH];
        System.arraycopy(page, from * WIDTH, rows, 0, rows.length);
        return rows;
    }

    @Test
    void scrollingDownAddsTheNewRowsBelow() {
        int[] page = page(2000, 1);
        ImageStitcher stitcher = new ImageStitcher(WIDTH, HEIGHT);
        assertEquals(ImageStitcher.Result.FIRST, stitcher.add(view(page, 0)));
        assertEquals(HEIGHT, stitcher.height());
        int offset = 0;
        for (int step : new int[]{1, 7, 40, 120, 199, 3, 88}) {
            offset += step;
            assertEquals(ImageStitcher.Result.MOVED, stitcher.add(view(page, offset)),
                    "step " + step);
            assertEquals(offset + HEIGHT, stitcher.height());
            assertEquals(offset, stitcher.viewOffset());
        }
        assertEquals(WIDTH, stitcher.width());
        assertEquals(HEIGHT, stitcher.frameHeight());
        assertArrayEquals(rows(page, 0, offset + HEIGHT), stitcher.image());
    }

    @Test
    void scrollingUpAddsTheNewRowsAbove() {
        int[] page = page(2000, 2);
        ImageStitcher stitcher = new ImageStitcher(WIDTH, HEIGHT);
        stitcher.add(view(page, 600));
        assertEquals(ImageStitcher.Result.MOVED, stitcher.add(view(page, 540)));
        assertEquals(ImageStitcher.Result.MOVED, stitcher.add(view(page, 400)));
        assertEquals(0, stitcher.viewOffset());
        // Back down, past where it began.
        assertEquals(ImageStitcher.Result.MOVED, stitcher.add(view(page, 560)));
        assertEquals(ImageStitcher.Result.MOVED, stitcher.add(view(page, 700)));
        assertEquals(300, stitcher.viewOffset());
        assertArrayEquals(rows(page, 400, 700 + HEIGHT), stitcher.image());
    }

    @Test
    void aPictureThatDidNotMoveAddsNothing() {
        int[] page = page(1000, 3);
        ImageStitcher stitcher = new ImageStitcher(WIDTH, HEIGHT);
        stitcher.add(view(page, 100));
        assertEquals(ImageStitcher.Result.STATIC, stitcher.add(view(page, 100)));
        // A blinking caret changes a few pixels of a few rows.
        int[] caret = view(page, 100);
        for (int y = 50; y < 62; y++)
            caret[y * WIDTH + 40] ^= 0xFFFFFF;
        assertEquals(ImageStitcher.Result.STATIC, stitcher.add(caret));
        assertEquals(HEIGHT, stitcher.height());
        assertEquals(0, stitcher.unplaced());
    }

    @Test
    void aPictureThatSharesNothingWithTheLastIsLeftOut() {
        int[] page = page(3000, 4);
        ImageStitcher stitcher = new ImageStitcher(WIDTH, HEIGHT);
        stitcher.add(view(page, 0));
        stitcher.add(view(page, 100));
        // A jump of more than one picture: nothing to place it by.
        assertEquals(ImageStitcher.Result.UNKNOWN, stitcher.add(view(page, 1500)));
        assertEquals(1, stitcher.unplaced());
        assertEquals(ImageStitcher.Result.UNKNOWN, stitcher.add(view(page, 1600)));
        assertEquals(2, stitcher.unplaced());
        assertEquals(100 + HEIGHT, stitcher.height());
        // Scrolled back to where the last joined picture overlaps: it goes on.
        assertEquals(ImageStitcher.Result.MOVED, stitcher.add(view(page, 180)));
        assertEquals(0, stitcher.unplaced());
        assertArrayEquals(rows(page, 0, 180 + HEIGHT), stitcher.image());
    }

    @Test
    void aBarThatStaysAtTheTopIsInTheTallPictureOnce() {
        int bar = 30;
        int[] page = page(2000, 5);
        int[] header = page(bar, 6);
        ImageStitcher stitcher = new ImageStitcher(WIDTH, HEIGHT);
        int offset = 0;
        for (int step : new int[]{0, 50, 90, 130, 60}) {
            offset += step;
            int[] view = view(page, offset);
            System.arraycopy(header, 0, view, 0, bar * WIDTH);
            stitcher.add(view);
        }
        assertEquals(offset + HEIGHT, stitcher.height());
        int[] image = stitcher.image();
        // The bar, then the page from under it on: no copy of the bar further down.
        assertArrayEquals(header, java.util.Arrays.copyOf(image, bar * WIDTH));
        assertArrayEquals(rows(page, bar, offset + HEIGHT),
                java.util.Arrays.copyOfRange(image, bar * WIDTH, image.length));
    }

    @Test
    void aBarThatStaysAtTheBottomIsInTheTallPictureOnce() {
        int bar = 24;
        int[] page = page(2000, 7);
        int[] footer = page(bar, 8);
        ImageStitcher stitcher = new ImageStitcher(WIDTH, HEIGHT);
        int offset = 0;
        for (int step : new int[]{0, 70, 110, 45}) {
            offset += step;
            int[] view = view(page, offset);
            System.arraycopy(footer, 0, view, (HEIGHT - bar) * WIDTH, bar * WIDTH);
            stitcher.add(view);
        }
        int[] image = stitcher.image();
        int tall = offset + HEIGHT;
        assertEquals(tall, stitcher.height());
        assertArrayEquals(rows(page, 0, tall - bar),
                java.util.Arrays.copyOf(image, (tall - bar) * WIDTH));
        assertArrayEquals(footer,
                java.util.Arrays.copyOfRange(image, (tall - bar) * WIDTH, image.length));
    }

    @Test
    void blankRowsDoNotPassForMovement() {
        // A page that is mostly white, with a few lines of text far apart.
        int rows = 1500;
        int[] page = new int[WIDTH * rows];
        java.util.Arrays.fill(page, 0xFFFFFFFF);
        Random random = new Random(9);
        for (int y = 0; y < rows; y += 37)
            for (int line = 0; line < 9; line++)
                for (int x = 10; x < WIDTH - 10; x++)
                    page[(y + line) * WIDTH + x] = 0xFF000000 | random.nextInt(0x1000000);
        ImageStitcher stitcher = new ImageStitcher(WIDTH, HEIGHT);
        stitcher.add(view(page, 0));
        int offset = 0;
        for (int step : new int[]{20, 37, 74, 5, 111}) {
            offset += step;
            assertEquals(ImageStitcher.Result.MOVED, stitcher.add(view(page, offset)),
                    "step " + step);
        }
        assertArrayEquals(rows(page, 0, offset + HEIGHT), stitcher.image());
    }

    @Test
    void theTallPictureStopsGrowingAtItsLimit() {
        int[] page = page(2000, 10);
        ImageStitcher stitcher = new ImageStitcher(WIDTH, HEIGHT, (long) WIDTH * 400);
        stitcher.add(view(page, 0));
        assertEquals(ImageStitcher.Result.MOVED, stitcher.add(view(page, 150)));
        assertFalse(stitcher.full());
        assertEquals(ImageStitcher.Result.FULL, stitcher.add(view(page, 300)));
        assertTrue(stitcher.full());
        assertEquals(ImageStitcher.Result.FULL, stitcher.add(view(page, 310)));
        assertEquals(150 + HEIGHT, stitcher.height());
        assertArrayEquals(rows(page, 0, 150 + HEIGHT), stitcher.image());
    }

    @Test
    void thePreviewFitsAndIsNeverEnlarged() {
        int[] page = page(2000, 11);
        ImageStitcher stitcher = new ImageStitcher(WIDTH, HEIGHT);
        stitcher.add(view(page, 0));
        stitcher.add(view(page, 200));
        stitcher.add(view(page, 400));
        int[] size = new int[2];
        int[] preview = stitcher.preview(90, 100, size);
        assertTrue(size[0] <= 90 && size[1] <= 100, size[0] + "x" + size[1]);
        assertEquals(size[0] * size[1], preview.length);
        // The same shape as the tall picture.
        assertEquals((double) WIDTH / stitcher.height(), (double) size[0] / size[1], 0.05);
        int[] large = stitcher.preview(4000, 4000, size);
        assertEquals(WIDTH, size[0]);
        assertEquals(stitcher.height(), size[1]);
        assertArrayEquals(stitcher.image(), large);
    }
}
