package mousemaster;

import mousemaster.platform.ScreenTools.Sprite;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws what the screenshot tools show, into plain pixel arrays: the frozen screen with
 * the region picked out and its two corner dots, the outline kept around the region
 * while a long screenshot is taken, and the panel that previews the long screenshot.
 * Pixels are premultiplied 0xAARRGGBB, a row after another.
 */
public final class ScreenshotPainter {

    static final int RED = 0xFF3B30;
    static final int PANEL = 0x1A1B26;
    static final int LIGHT = 0xC0CAF5;
    static final int BLUE = 0x7AA2F7;
    static final int YELLOW = 0xE0AF68;
    static final int ORANGE = 0xFF9E64;

    public static final int NO_CORNER = -1;
    public static final int FIRST_CORNER = 0;
    public static final int SECOND_CORNER = 1;

    private ScreenshotPainter() {
    }

    /**
     * The frozen screen, dimmed outside the region. The first corner is where the
     * region begins and the second where it ends, until one is moved past the other:
     * the region is always the rectangle between the two.
     */
    public static final class Selection {

        private final int[] shot;
        private final int[] dimmed;
        private final int[] frame;
        private final int width;
        private final int height;
        private final Sprite[] cornerTags;
        private final Sprite hint;
        /** 0 to 9, then the sign between the width and the height. Null when no text is drawn. */
        private final Sprite[] digits;
        private final int border;
        private final int dotRadius;
        private final int activeDotRadius;
        private final int gap;
        private final int padding;

        private boolean painted;
        private int firstX, firstY, secondX, secondY;
        private int active = NO_CORNER;
        private List<Rectangle> decorations = List.of();
        // The rectangle being painted into.
        private int clipLeft, clipTop, clipRight, clipBottom;

        public Selection(int[] shot, int width, int height, double scale,
                         Sprite[] cornerTags, Sprite hint, Sprite[] digits) {
            this.shot = shot;
            this.width = width;
            this.height = height;
            this.cornerTags = cornerTags;
            this.hint = hint;
            this.digits = digits;
            dimmed = new int[shot.length];
            for (int index = 0; index < shot.length; index++)
                dimmed[index] = 0xFF000000 | ((shot[index] >>> 1) & 0x7F7F7F) -
                                             ((shot[index] >>> 3) & 0x1F1F1F);
            frame = new int[shot.length];
            border = Math.max(1, (int) Math.round(1.5 * scale));
            dotRadius = Math.max(4, (int) Math.round(6 * scale));
            activeDotRadius = dotRadius + Math.max(2, (int) Math.round(2.5 * scale));
            gap = Math.max(2, (int) Math.round(3 * scale));
            padding = Math.max(2, (int) Math.round(4 * scale));
        }

        public int[] frame() {
            return frame;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        /** The region, inside the picture. */
        public Rectangle region() {
            int left = Math.min(firstX, secondX);
            int top = Math.min(firstY, secondY);
            return new Rectangle(left, top, Math.max(1, Math.abs(secondX - firstX)),
                    Math.max(1, Math.abs(secondY - firstY)));
        }

        /**
         * Paints the corners where they are now, and returns the rectangles of the frame
         * that changed. All of it the first time.
         */
        public List<Rectangle> paint(int firstX, int firstY, int secondX, int secondY,
                                     int active) {
            Rectangle before = painted ? region() : null;
            List<Rectangle> decorationsBefore = decorations;
            boolean same = painted && firstX == this.firstX && firstY == this.firstY &&
                           secondX == this.secondX && secondY == this.secondY &&
                           active == this.active;
            if (same)
                return List.of();
            this.firstX = firstX;
            this.firstY = firstY;
            this.secondX = secondX;
            this.secondY = secondY;
            this.active = active;
            Rectangle region = region();
            decorations = decorations(region);
            List<Rectangle> dirty = new ArrayList<>();
            if (!painted) {
                painted = true;
                dirty.add(new Rectangle(0, 0, width, height));
            }
            else {
                dirty.addAll(changedBands(before, region, border + 1));
                dirty.addAll(decorationsBefore);
                dirty.addAll(decorations);
            }
            List<Rectangle> clipped = new ArrayList<>();
            for (Rectangle rectangle : dirty) {
                Rectangle inside = rectangle.intersection(new Rectangle(0, 0, width, height));
                if (inside.isEmpty())
                    continue;
                paint(inside, region);
                clipped.add(inside);
            }
            return clipped;
        }

        /**
         * What differs between two regions: the bands between their edges, widened by
         * {@code margin} for the outline.
         */
        static List<Rectangle> changedBands(Rectangle before, Rectangle after, int margin) {
            int left = Math.min(before.x(), after.x()) - margin;
            int top = Math.min(before.y(), after.y()) - margin;
            int right = Math.max(before.x() + before.width(), after.x() + after.width()) + margin;
            int bottom = Math.max(before.y() + before.height(), after.y() + after.height()) + margin;
            int innerLeft = Math.max(before.x(), after.x()) + margin;
            int innerTop = Math.max(before.y(), after.y()) + margin;
            int innerRight = Math.min(before.x() + before.width(), after.x() + after.width()) - margin;
            int innerBottom = Math.min(before.y() + before.height(), after.y() + after.height()) - margin;
            if (innerLeft >= innerRight || innerTop >= innerBottom)
                return List.of(new Rectangle(left, top, right - left, bottom - top));
            List<Rectangle> bands = new ArrayList<>(4);
            bands.add(new Rectangle(left, top, right - left, innerTop - top));
            bands.add(new Rectangle(left, innerBottom, right - left, bottom - innerBottom));
            bands.add(new Rectangle(left, innerTop, innerLeft - left, innerBottom - innerTop));
            bands.add(new Rectangle(innerRight, innerTop, right - innerRight, innerBottom - innerTop));
            return bands;
        }

        private int dotX(int x) {
            return Math.clamp(x, Math.min(activeDotRadius + 1, width / 2),
                    Math.max(width - activeDotRadius - 1, width / 2));
        }

        private int dotY(int y) {
            return Math.clamp(y, Math.min(activeDotRadius + 1, height / 2),
                    Math.max(height - activeDotRadius - 1, height / 2));
        }

        private Rectangle dotRectangle(int x, int y) {
            int reach = activeDotRadius + 2;
            return new Rectangle(dotX(x) - reach, dotY(y) - reach, 2 * reach + 1, 2 * reach + 1);
        }

        /** The tag sits away from the region, or inside it when the corner is at the edge of the screen. */
        private Rectangle tagRectangle(int corner) {
            Sprite tag = cornerTags == null ? null : cornerTags[corner];
            if (tag == null)
                return null;
            int boxWidth = tag.width() + 2 * padding;
            int boxHeight = tag.height() + padding;
            int x = dotX(corner == FIRST_CORNER ? firstX : secondX);
            int y = dotY(corner == FIRST_CORNER ? firstY : secondY);
            boolean firstIsStart = firstX <= secondX && firstY <= secondY;
            boolean before = (corner == FIRST_CORNER) == firstIsStart;
            int reach = activeDotRadius + gap;
            int beforeX = x - reach - boxWidth;
            int beforeY = y - reach - boxHeight;
            int afterX = x + reach;
            int afterY = y + reach;
            if (before && (beforeX < 0 || beforeY < 0))
                before = false;
            else if (!before && (afterX + boxWidth > width || afterY + boxHeight > height))
                before = true;
            return before ? new Rectangle(beforeX, beforeY, boxWidth, boxHeight) :
                    new Rectangle(afterX, afterY, boxWidth, boxHeight);
        }

        private int digitIndex(char character) {
            return character >= '0' && character <= '9' ? character - '0' : 10;
        }

        private String sizeText(Rectangle region) {
            return region.width() + "x" + region.height();
        }

        private Rectangle sizeRectangle(Rectangle region) {
            if (digits == null)
                return null;
            String text = sizeText(region);
            int textWidth = 0;
            int textHeight = 0;
            for (int at = 0; at < text.length(); at++) {
                Sprite digit = digits[digitIndex(text.charAt(at))];
                textWidth += digit.width();
                textHeight = Math.max(textHeight, digit.height());
            }
            int boxWidth = textWidth + 2 * padding;
            int boxHeight = textHeight + padding;
            int x = Math.clamp(region.x() + region.width() / 2 - boxWidth / 2, 0,
                    Math.max(0, width - boxWidth));
            int y = region.y() - boxHeight - gap - border;
            if (y < 0)
                y = Math.min(region.y() + gap + border, Math.max(0, height - boxHeight));
            return new Rectangle(x, y, boxWidth, boxHeight);
        }

        private List<Rectangle> decorations(Rectangle region) {
            List<Rectangle> rectangles = new ArrayList<>();
            rectangles.add(dotRectangle(firstX, firstY));
            rectangles.add(dotRectangle(secondX, secondY));
            for (int corner = FIRST_CORNER; corner <= SECOND_CORNER; corner++) {
                Rectangle tag = tagRectangle(corner);
                if (tag != null)
                    rectangles.add(tag);
            }
            Rectangle size = sizeRectangle(region);
            if (size != null)
                rectangles.add(size);
            return rectangles;
        }

        private void paint(Rectangle clip, Rectangle region) {
            clipLeft = clip.x();
            clipTop = clip.y();
            clipRight = clip.x() + clip.width();
            clipBottom = clip.y() + clip.height();
            int regionLeft = Math.max(clipLeft, region.x());
            int regionRight = Math.min(clipRight, region.x() + region.width());
            for (int y = clipTop; y < clipBottom; y++) {
                int row = y * width;
                System.arraycopy(dimmed, row + clipLeft, frame, row + clipLeft,
                        clipRight - clipLeft);
                if (y >= region.y() && y < region.y() + region.height() &&
                    regionLeft < regionRight)
                    System.arraycopy(shot, row + regionLeft, frame, row + regionLeft,
                            regionRight - regionLeft);
            }
            // The outline is just outside the region, so the region itself is untouched.
            int left = region.x();
            int top = region.y();
            int right = region.x() + region.width();
            int bottom = region.y() + region.height();
            fill(left - border, top - border, right + border, top, RED);
            fill(left - border, bottom, right + border, bottom + border, RED);
            fill(left - border, top, left, bottom, RED);
            fill(right, top, right + border, bottom, RED);
            if (hint != null) {
                int boxWidth = hint.width() + 4 * padding;
                int boxHeight = hint.height() + 2 * padding;
                int x = (width - boxWidth) / 2;
                int y = height - boxHeight - Math.max(8, height / 12);
                blend(x, y, x + boxWidth, y + boxHeight, PANEL, 225);
                sprite(x + 2 * padding, y + padding, hint);
            }
            Rectangle size = sizeRectangle(region);
            if (size != null) {
                blend(size.x(), size.y(), size.x() + size.width(), size.y() + size.height(),
                        PANEL, 225);
                String text = sizeText(region);
                int x = size.x() + padding;
                for (int at = 0; at < text.length(); at++) {
                    Sprite digit = digits[digitIndex(text.charAt(at))];
                    sprite(x, size.y() + padding / 2, digit);
                    x += digit.width();
                }
            }
            paintCorner(FIRST_CORNER, firstX, firstY);
            paintCorner(SECOND_CORNER, secondX, secondY);
        }

        private void paintCorner(int corner, int x, int y) {
            int centerX = dotX(x);
            int centerY = dotY(y);
            if (corner == active) {
                disc(centerX, centerY, activeDotRadius, 0xFFFFFF);
                disc(centerX, centerY, activeDotRadius - Math.max(1, border), RED);
            }
            else {
                disc(centerX, centerY, dotRadius + 1, 0xFFFFFF);
                disc(centerX, centerY, dotRadius, RED);
            }
            Rectangle tag = tagRectangle(corner);
            if (tag == null)
                return;
            blend(tag.x(), tag.y(), tag.x() + tag.width(), tag.y() + tag.height(),
                    corner == active ? RED : PANEL, 235);
            sprite(tag.x() + padding, tag.y() + padding / 2, cornerTags[corner]);
        }

        private void fill(int left, int top, int right, int bottom, int rgb) {
            left = Math.max(left, clipLeft);
            top = Math.max(top, clipTop);
            right = Math.min(right, clipRight);
            bottom = Math.min(bottom, clipBottom);
            for (int y = top; y < bottom; y++)
                java.util.Arrays.fill(frame, y * width + left, y * width + Math.max(left, right),
                        0xFF000000 | rgb);
        }

        private void blend(int left, int top, int right, int bottom, int rgb, int alpha) {
            left = Math.max(left, clipLeft);
            top = Math.max(top, clipTop);
            right = Math.min(right, clipRight);
            bottom = Math.min(bottom, clipBottom);
            for (int y = top; y < bottom; y++)
                for (int x = left; x < right; x++)
                    frame[y * width + x] = over(frame[y * width + x], rgb, alpha);
        }

        private void disc(int centerX, int centerY, int radius, int rgb) {
            int left = Math.max(centerX - radius - 1, clipLeft);
            int top = Math.max(centerY - radius - 1, clipTop);
            int right = Math.min(centerX + radius + 2, clipRight);
            int bottom = Math.min(centerY + radius + 2, clipBottom);
            for (int y = top; y < bottom; y++)
                for (int x = left; x < right; x++) {
                    double distance = Math.hypot(x - centerX, y - centerY);
                    double coverage = Math.clamp(radius + 0.5 - distance, 0, 1);
                    if (coverage > 0)
                        frame[y * width + x] = over(frame[y * width + x], rgb,
                                (int) Math.round(coverage * 255));
                }
        }

        private void sprite(int x, int y, Sprite sprite) {
            int left = Math.max(x, clipLeft);
            int top = Math.max(y, clipTop);
            int right = Math.min(x + sprite.width(), clipRight);
            int bottom = Math.min(y + sprite.height(), clipBottom);
            int[] pixels = sprite.pixels();
            for (int row = top; row < bottom; row++)
                for (int column = left; column < right; column++)
                    frame[row * width + column] = overPremultiplied(frame[row * width + column],
                            pixels[(row - y) * sprite.width() + column - x]);
        }
    }

    /** A color at that opacity (0 to 255) over an opaque pixel. */
    static int over(int below, int rgb, int alpha) {
        int inverse = 255 - alpha;
        int red = (((rgb >> 16) & 0xFF) * alpha + ((below >> 16) & 0xFF) * inverse + 127) / 255;
        int green = (((rgb >> 8) & 0xFF) * alpha + ((below >> 8) & 0xFF) * inverse + 127) / 255;
        int blue = ((rgb & 0xFF) * alpha + (below & 0xFF) * inverse + 127) / 255;
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    /** A premultiplied pixel over another. */
    static int overPremultiplied(int below, int above) {
        int alpha = above >>> 24;
        if (alpha == 0)
            return below;
        if (alpha == 255)
            return above;
        int inverse = 255 - alpha;
        int belowAlpha = below >>> 24;
        int resultAlpha = Math.min(255, alpha + (belowAlpha * inverse + 127) / 255);
        int red = Math.min(255, ((above >> 16) & 0xFF) + (((below >> 16) & 0xFF) * inverse + 127) / 255);
        int green = Math.min(255, ((above >> 8) & 0xFF) + (((below >> 8) & 0xFF) * inverse + 127) / 255);
        int blue = Math.min(255, (above & 0xFF) + ((below & 0xFF) * inverse + 127) / 255);
        return resultAlpha << 24 | red << 16 | green << 8 | blue;
    }

    /** An outline with nothing inside. Each side has its own thickness, 0 for none. */
    public static int[] outline(int width, int height, int left, int top, int right,
                                int bottom) {
        int[] pixels = new int[width * height];
        int color = 0xFF000000 | RED;
        for (int y = 0; y < height; y++) {
            boolean edgeRow = y < top || y >= height - bottom;
            for (int x = 0; x < width; x++)
                if (edgeRow || x < left || x >= width - right)
                    pixels[y * width + x] = color;
        }
        return pixels;
    }

    /**
     * The panel that previews the long screenshot: the picture so far, made to fit, with
     * a frame around the part the region shows now, and the keys under it. The frame of
     * the panel turns orange while new pictures cannot be placed.
     */
    public static Sprite previewPanel(ImageStitcher stitcher, int maxWidth, int maxHeight,
                                      double scale, Sprite hint, boolean lost) {
        int padding = Math.max(4, (int) Math.round(8 * scale));
        int line = Math.max(1, (int) Math.round(scale));
        int hintHeight = hint == null ? 0 : hint.height() + padding / 2;
        int[] size = new int[2];
        int[] preview = stitcher.preview(Math.max(16, maxWidth - 2 * padding),
                Math.max(16, maxHeight - 2 * padding - hintHeight), size);
        int previewWidth = size[0];
        int previewHeight = size[1];
        int contentWidth = Math.max(previewWidth, hint == null ? 0 : hint.width());
        int panelWidth = contentWidth + 2 * padding;
        int panelHeight = previewHeight + hintHeight + 2 * padding;
        int[] panel = new int[panelWidth * panelHeight];
        int background = premultiplied(PANEL, 240);
        int edge = 0xFF000000 | (lost ? ORANGE : BLUE);
        for (int y = 0; y < panelHeight; y++)
            for (int x = 0; x < panelWidth; x++)
                panel[y * panelWidth + x] =
                        y < line || y >= panelHeight - line || x < line || x >= panelWidth - line ?
                                edge : background;
        int previewLeft = padding + (contentWidth - previewWidth) / 2;
        for (int y = 0; y < previewHeight; y++)
            System.arraycopy(preview, y * previewWidth, panel,
                    (padding + y) * panelWidth + previewLeft, previewWidth);
        // Where the region is in the long picture.
        int tall = Math.max(1, stitcher.height());
        int viewTop = (int) ((long) stitcher.viewOffset() * previewHeight / tall);
        int viewBottom = (int) Math.min(previewHeight,
                ((long) stitcher.viewOffset() + stitcher.frameHeight()) * previewHeight / tall);
        int marker = 0xFF000000 | YELLOW;
        int markerLine = Math.max(1, line * 2);
        for (int y = Math.max(0, viewTop); y < viewBottom; y++) {
            boolean edgeRow = y < viewTop + markerLine || y >= viewBottom - markerLine;
            for (int x = 0; x < previewWidth; x++)
                if (edgeRow || x < markerLine || x >= previewWidth - markerLine)
                    panel[(padding + y) * panelWidth + previewLeft + x] = marker;
        }
        if (hint != null) {
            int hintLeft = padding + (contentWidth - hint.width()) / 2;
            int hintTop = padding + previewHeight + padding / 2;
            for (int y = 0; y < hint.height(); y++)
                for (int x = 0; x < hint.width(); x++) {
                    int at = (hintTop + y) * panelWidth + hintLeft + x;
                    panel[at] = overPremultiplied(panel[at], hint.pixels()[y * hint.width() + x]);
                }
        }
        return new Sprite(panel, panelWidth, panelHeight);
    }

    static int premultiplied(int rgb, int alpha) {
        int red = ((rgb >> 16) & 0xFF) * alpha / 255;
        int green = ((rgb >> 8) & 0xFF) * alpha / 255;
        int blue = (rgb & 0xFF) * alpha / 255;
        return alpha << 24 | red << 16 | green << 8 | blue;
    }
}
