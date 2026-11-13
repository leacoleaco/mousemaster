package mousemaster;

import mousemaster.platform.ScreenTools.Sprite;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the screenshot tools draw: the frozen screen with the region picked out, the
 * outline around the region of a long screenshot, and the preview of the long picture.
 */
class ScreenshotPainterTest {

    private static final int WIDTH = 320;
    private static final int HEIGHT = 200;

    private static int[] shot() {
        Random random = new Random(1);
        int[] shot = new int[WIDTH * HEIGHT];
        for (int index = 0; index < shot.length; index++)
            shot[index] = 0xFF000000 | random.nextInt(0x1000000);
        return shot;
    }

    private static Sprite sprite(int width, int height, int argb) {
        int[] pixels = new int[width * height];
        Arrays.fill(pixels, argb);
        return new Sprite(pixels, width, height);
    }

    private static ScreenshotPainter.Selection selection(int[] shot, boolean text) {
        if (!text)
            return new ScreenshotPainter.Selection(shot, WIDTH, HEIGHT, 1, null, null, null);
        Sprite[] digits = new Sprite[11];
        for (int digit = 0; digit < digits.length; digit++)
            digits[digit] = sprite(6, 10, 0xFFC0CAF5);
        return new ScreenshotPainter.Selection(shot, WIDTH, HEIGHT, 1,
                new Sprite[]{sprite(9, 12, 0xFFFFFFFF), sprite(9, 12, 0xFFFFFFFF)},
                sprite(120, 12, 0x80606060), digits);
    }

    @Test
    void theRegionShowsTheScreenAsItIsAndTheRestIsDimmed() {
        int[] shot = shot();
        ScreenshotPainter.Selection selection = selection(shot, false);
        List<Rectangle> dirty = selection.paint(60, 40, 200, 150, ScreenshotPainter.NO_CORNER);
        // The first time, all of it.
        assertEquals(List.of(new Rectangle(0, 0, WIDTH, HEIGHT)), dirty);
        assertEquals(new Rectangle(60, 40, 140, 110), selection.region());
        int[] frame = selection.frame();
        // Inside, away from the corner dots.
        for (int[] point : new int[][]{{130, 95}, {61, 100}, {198, 100}, {130, 41}, {130, 148}})
            assertEquals(shot[point[1] * WIDTH + point[0]], frame[point[1] * WIDTH + point[0]]);
        // Outside.
        for (int[] point : new int[][]{{20, 20}, {300, 180}, {130, 10}, {250, 95}}) {
            int pixel = frame[point[1] * WIDTH + point[0]];
            assertNotEquals(shot[point[1] * WIDTH + point[0]], pixel);
            assertEquals(0xFF, pixel >>> 24);
        }
        // The outline is just outside the region.
        int red = 0xFF000000 | ScreenshotPainter.RED;
        assertEquals(red, frame[95 * WIDTH + 59]);
        assertEquals(red, frame[95 * WIDTH + 200]);
        assertEquals(red, frame[39 * WIDTH + 130]);
        assertEquals(red, frame[150 * WIDTH + 130]);
    }

    @Test
    void theRegionIsTheRectangleBetweenTheCornersWhicheverWayTheyCross() {
        ScreenshotPainter.Selection selection = selection(shot(), false);
        selection.paint(200, 150, 60, 40, ScreenshotPainter.FIRST_CORNER);
        assertEquals(new Rectangle(60, 40, 140, 110), selection.region());
        selection.paint(200, 40, 60, 150, ScreenshotPainter.FIRST_CORNER);
        assertEquals(new Rectangle(60, 40, 140, 110), selection.region());
        // Never empty: a corner on the other is one pixel.
        selection.paint(77, 88, 77, 88, ScreenshotPainter.FIRST_CORNER);
        assertEquals(new Rectangle(77, 88, 1, 1), selection.region());
    }

    @Test
    void paintingTheSameCornersAgainChangesNothing() {
        ScreenshotPainter.Selection selection = selection(shot(), true);
        selection.paint(0, 0, WIDTH, HEIGHT, ScreenshotPainter.NO_CORNER);
        assertEquals(List.of(), selection.paint(0, 0, WIDTH, HEIGHT, ScreenshotPainter.NO_CORNER));
        assertTrue(!selection.paint(0, 0, WIDTH, HEIGHT, ScreenshotPainter.FIRST_CORNER).isEmpty());
    }

    /**
     * Only what changed is painted again. After any walk of the corners the frame is
     * what painting those corners from nothing gives.
     */
    @Test
    void repaintingOnlyWhatChangedGivesTheSameFrameAsPaintingAll() {
        for (boolean text : new boolean[]{false, true}) {
            int[] shot = shot();
            ScreenshotPainter.Selection selection = selection(shot, text);
            Random random = new Random(text ? 7 : 8);
            int firstX = 0, firstY = 0, secondX = WIDTH, secondY = HEIGHT;
            int active = ScreenshotPainter.NO_CORNER;
            selection.paint(firstX, firstY, secondX, secondY, active);
            for (int step = 0; step < 300; step++) {
                switch (random.nextInt(6)) {
                    case 0 -> active = random.nextInt(3) - 1;
                    case 1 -> firstX = Math.clamp(firstX + random.nextInt(41) - 20, 0, WIDTH - 1);
                    case 2 -> firstY = Math.clamp(firstY + random.nextInt(41) - 20, 0, HEIGHT - 1);
                    case 3 -> secondX = Math.clamp(secondX + random.nextInt(41) - 20, 1, WIDTH);
                    case 4 -> secondY = Math.clamp(secondY + random.nextInt(41) - 20, 1, HEIGHT);
                    default -> {
                        // A jump across the screen, past the other corner.
                        firstX = random.nextInt(WIDTH);
                        firstY = random.nextInt(HEIGHT);
                    }
                }
                List<Rectangle> dirty = selection.paint(firstX, firstY, secondX, secondY, active);
                for (Rectangle rectangle : dirty) {
                    assertTrue(rectangle.x() >= 0 && rectangle.y() >= 0 &&
                               rectangle.x() + rectangle.width() <= WIDTH &&
                               rectangle.y() + rectangle.height() <= HEIGHT &&
                               rectangle.width() > 0 && rectangle.height() > 0,
                            rectangle.toString());
                }
                ScreenshotPainter.Selection fresh = selection(shot, text);
                fresh.paint(firstX, firstY, secondX, secondY, active);
                assertArrayEquals(fresh.frame(), selection.frame(),
                        "step " + step + " corners " + firstX + "," + firstY + " " + secondX +
                        "," + secondY + " active " + active + " text " + text);
            }
        }
    }

    @Test
    void theOutlineHasItsOwnThicknessOnEachSide() {
        int red = 0xFF000000 | ScreenshotPainter.RED;
        int[] all = ScreenshotPainter.outline(10, 8, 2, 2, 2, 2);
        assertEquals(red, all[0]);
        assertEquals(red, all[3 * 10 + 1]);
        assertEquals(red, all[3 * 10 + 8]);
        assertEquals(0, all[3 * 10 + 2]);
        assertEquals(0, all[5 * 10 + 7]);
        assertEquals(red, all[6 * 10 + 5]);
        // Against the left and the top edge of the screen there is no room for it.
        int[] clipped = ScreenshotPainter.outline(10, 8, 0, 0, 2, 2);
        assertEquals(0, clipped[0]);
        assertEquals(0, clipped[3 * 10]);
        assertEquals(red, clipped[3 * 10 + 8]);
        assertEquals(red, clipped[6 * 10]);
        assertEquals(0, clipped[5 * 10 + 7]);
    }

    @Test
    void thePreviewPanelHoldsTheTallPictureInsideAFrame() {
        int width = 100;
        int height = 80;
        Random random = new Random(3);
        int[] page = new int[width * 600];
        for (int index = 0; index < page.length; index++)
            page[index] = 0xFF000000 | random.nextInt(0x1000000);
        ImageStitcher stitcher = new ImageStitcher(width, height);
        for (int offset = 0; offset <= 240; offset += 40)
            stitcher.add(Arrays.copyOfRange(page, offset * width, (offset + height) * width));
        Sprite hint = sprite(40, 10, 0xFFFFFFFF);
        Sprite panel = ScreenshotPainter.previewPanel(stitcher, 160, 220, 1, hint, false);
        assertTrue(panel.width() <= 160 && panel.height() <= 220,
                panel.width() + "x" + panel.height());
        assertEquals(panel.width() * panel.height(), panel.pixels().length);
        assertEquals(0xFF000000 | ScreenshotPainter.BLUE, panel.pixels()[0]);
        // While pictures cannot be placed, the frame says so.
        Sprite lost = ScreenshotPainter.previewPanel(stitcher, 160, 220, 1, hint, true);
        assertEquals(0xFF000000 | ScreenshotPainter.ORANGE, lost.pixels()[0]);
        // No text to draw: the panel is still made.
        Sprite bare = ScreenshotPainter.previewPanel(stitcher, 160, 220, 1, null, false);
        assertTrue(bare.width() <= 160 && bare.height() <= 220);
        assertEquals(bare.width() * bare.height(), bare.pixels().length);
    }

    @Test
    void croppingTakesTheRegionOutOfTheScreen() {
        int[] shot = shot();
        Rectangle region = new Rectangle(17, 9, 40, 25);
        int[] cropped = CopyTools.crop(shot, WIDTH, region);
        assertEquals(40 * 25, cropped.length);
        for (int y = 0; y < 25; y++)
            for (int x = 0; x < 40; x++)
                assertEquals(shot[(9 + y) * WIDTH + 17 + x], cropped[y * 40 + x]);
    }
}
