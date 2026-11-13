package mousemaster;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Joins pictures of one region, taken while what it shows scrolls up or down, into one
 * tall picture. Each new picture is compared with the last one that was joined, row by
 * row, to find how far the content moved. It is then written where that puts it, over
 * the rows already there.
 * <p>
 * A bar that stays put while the rest scrolls, at the top or at the bottom, is written
 * only where the tall picture begins or ends, so it is there once and not once per
 * picture. A picture that cannot be placed (the content moved too far at once, or is
 * mid-animation) is left out, and the next one is tried against the same last picture.
 * <p>
 * Pixels are 0xFFRRGGBB, a row after another. Not thread-safe.
 */
public final class ImageStitcher {

    public enum Result {
        /** The first picture. */
        FIRST,
        /** Nothing scrolled. */
        STATIC,
        /** The content scrolled and the picture was joined. */
        MOVED,
        /** The picture could not be placed against the last one, and was left out. */
        UNKNOWN,
        /** The tall picture is as large as it may get. Nothing more is joined. */
        FULL
    }

    /** A row is compared in this many strips, so a scroll bar only spoils the last one. */
    static final int BANDS = 6;
    static final long DEFAULT_MAX_PIXELS = 24_000_000L;
    /** Rows of the last picture that look the same are too many to tell which one moved. */
    private static final int MAX_SAME_ROWS = 4;

    private final int width;
    private final int height;
    private final long maxPixels;
    private final int[] bandStart = new int[BANDS + 1];
    private final Rows rows;

    private long[] previousHashes;
    private boolean[] previousPlain;
    private long[] hashes;
    private boolean[] plain;
    private boolean started;
    /** The row of the tall picture where the top of the last joined picture is. */
    private int viewTop;
    /** The tall picture is the rows from top to bottom. */
    private int top;
    private int bottom;
    private boolean full;
    private int unplaced;

    // Scratch for matching rows of two pictures.
    private final long[] tableKeys;
    private final int[] tableFirst;
    private final int[] tableCount;
    private final int[] nextSame;
    private final int[] votes;

    public ImageStitcher(int width, int height) {
        this(width, height, DEFAULT_MAX_PIXELS);
    }

    public ImageStitcher(int width, int height, long maxPixels) {
        if (width <= 0 || height <= 0)
            throw new IllegalArgumentException("Empty region: " + width + "x" + height);
        this.width = width;
        this.height = height;
        this.maxPixels = Math.max(maxPixels, (long) width * height);
        for (int band = 0; band <= BANDS; band++)
            bandStart[band] = (int) ((long) width * band / BANDS);
        rows = new Rows(width);
        hashes = new long[BANDS * height];
        plain = new boolean[BANDS * height];
        previousHashes = new long[BANDS * height];
        previousPlain = new boolean[BANDS * height];
        int capacity = 16;
        while (capacity < height * 2)
            capacity <<= 1;
        tableKeys = new long[capacity];
        tableFirst = new int[capacity];
        tableCount = new int[capacity];
        nextSame = new int[height];
        votes = new int[2 * height + 1];
    }

    public int width() {
        return width;
    }

    /** The height of one picture. */
    public int frameHeight() {
        return height;
    }

    /** The height of the tall picture so far. */
    public int height() {
        return bottom - top;
    }

    /** How far down the tall picture the last joined picture is. */
    public int viewOffset() {
        return viewTop - top;
    }

    /** How many pictures in a row could not be placed. */
    public int unplaced() {
        return unplaced;
    }

    public boolean full() {
        return full;
    }

    public Result add(int[] frame) {
        if (frame.length < width * height)
            throw new IllegalArgumentException("Picture smaller than the region");
        if (!started) {
            started = true;
            hash(frame);
            swap();
            for (int y = 0; y < height; y++)
                rows.write(y, frame, y * width);
            viewTop = 0;
            top = 0;
            bottom = height;
            return Result.FIRST;
        }
        if (full)
            return Result.FULL;
        hash(frame);
        int shift = shift();
        if (shift == 0) {
            unplaced = 0;
            return Result.STATIC;
        }
        if (shift == Integer.MIN_VALUE) {
            unplaced++;
            return Result.UNKNOWN;
        }
        unplaced = 0;
        int newViewTop = viewTop + shift;
        // The bar at the top is written only where the tall picture begins, and the bar
        // at the bottom only where it ends.
        int from = newViewTop <= top ? 0 : sameRowsAtTop();
        int to = newViewTop + height >= bottom ? height : height - sameRowsAtBottom();
        if (from >= to) {
            from = 0;
            to = height;
        }
        int newTop = Math.min(top, newViewTop + from);
        int newBottom = Math.max(bottom, newViewTop + to);
        if ((long) (newBottom - newTop) * width > maxPixels) {
            full = true;
            return Result.FULL;
        }
        for (int y = from; y < to; y++)
            rows.write(newViewTop + y, frame, y * width);
        viewTop = newViewTop;
        top = newTop;
        bottom = newBottom;
        swap();
        return Result.MOVED;
    }

    private void swap() {
        long[] swappedHashes = previousHashes;
        previousHashes = hashes;
        hashes = swappedHashes;
        boolean[] swappedPlain = previousPlain;
        previousPlain = plain;
        plain = swappedPlain;
    }

    /** One number per strip of each row, and whether that strip is a single color. */
    private void hash(int[] frame) {
        for (int y = 0; y < height; y++) {
            int rowStart = y * width;
            for (int band = 0; band < BANDS; band++) {
                int begin = rowStart + bandStart[band];
                int end = rowStart + bandStart[band + 1];
                if (begin >= end) {
                    hashes[band * height + y] = 0;
                    plain[band * height + y] = true;
                    continue;
                }
                int first = frame[begin] & 0xFFFFFF;
                long hash = 0;
                boolean single = true;
                for (int index = begin; index < end; index++) {
                    int pixel = frame[index] & 0xFFFFFF;
                    hash = (hash ^ pixel) * 0x9E3779B97F4A7C15L;
                    hash ^= hash >>> 29;
                    if (pixel != first)
                        single = false;
                }
                hashes[band * height + y] = hash == 0 ? 1 : hash;
                plain[band * height + y] = single;
            }
        }
    }

    /**
     * How many rows the content moved up since the last joined picture: positive when
     * it was scrolled down. 0 when it did not move, and Integer.MIN_VALUE when the two
     * pictures do not say.
     */
    private int shift() {
        Arrays.fill(votes, 0);
        int compared = 0;
        int mask = tableKeys.length - 1;
        for (int band = 0; band < BANDS; band++) {
            int base = band * height;
            Arrays.fill(tableKeys, 0);
            Arrays.fill(tableCount, 0);
            for (int y = height - 1; y >= 0; y--) {
                if (previousPlain[base + y])
                    continue;
                long key = previousHashes[base + y];
                int slot = (int) (key ^ (key >>> 32)) & mask;
                while (tableKeys[slot] != 0 && tableKeys[slot] != key)
                    slot = (slot + 1) & mask;
                if (tableKeys[slot] == 0) {
                    tableKeys[slot] = key;
                    nextSame[y] = -1;
                }
                else
                    nextSame[y] = tableFirst[slot];
                tableFirst[slot] = y;
                tableCount[slot]++;
            }
            for (int y = 0; y < height; y++) {
                if (plain[base + y])
                    continue;
                long key = hashes[base + y];
                int slot = (int) (key ^ (key >>> 32)) & mask;
                while (tableKeys[slot] != 0 && tableKeys[slot] != key)
                    slot = (slot + 1) & mask;
                if (tableKeys[slot] == 0) {
                    compared++;
                    continue;
                }
                if (tableCount[slot] > MAX_SAME_ROWS)
                    continue;
                compared++;
                for (int previousY = tableFirst[slot]; previousY >= 0;
                     previousY = nextSame[previousY])
                    votes[previousY - y + height]++;
            }
        }
        if (compared < BANDS * 2)
            return Integer.MIN_VALUE;
        int still = votes[height];
        if (still >= compared * 0.92)
            return 0;
        int least = Math.max(8, height / 12);
        int best = Integer.MIN_VALUE;
        int bestVotes = 0;
        for (int shift = -(height - least); shift <= height - least; shift++) {
            if (shift == 0)
                continue;
            int count = votes[shift + height];
            if (count > bestVotes) {
                bestVotes = count;
                best = shift;
            }
        }
        if (best == Integer.MIN_VALUE)
            return Integer.MIN_VALUE;
        // The rows a bar keeps in place say the picture is still: they are set aside.
        double expected = (double) (compared - still) * (height - Math.abs(best)) / height;
        if (bestVotes < Math.max(BANDS * 2, 0.4 * expected))
            return Integer.MIN_VALUE;
        return best;
    }

    private boolean sameRow(int y) {
        for (int band = 0; band < BANDS; band++)
            if (hashes[band * height + y] != previousHashes[band * height + y])
                return false;
        return true;
    }

    private int sameRowsAtTop() {
        int count = 0;
        while (count < height / 2 && sameRow(count))
            count++;
        return count;
    }

    private int sameRowsAtBottom() {
        int count = 0;
        while (count < height / 2 && sameRow(height - 1 - count))
            count++;
        return count;
    }

    /** The tall picture. */
    public int[] image() {
        int[] image = new int[width * height()];
        for (int y = top; y < bottom; y++)
            rows.read(y, image, (y - top) * width);
        return image;
    }

    /**
     * The tall picture made to fit, never enlarged. {@code size} gets the width and
     * the height of what is returned.
     */
    public int[] preview(int maxWidth, int maxHeight, int[] size) {
        int tall = height();
        double scale = Math.min(1, Math.min((double) maxWidth / width, (double) maxHeight / tall));
        int previewWidth = Math.max(1, (int) Math.round(width * scale));
        int previewHeight = Math.max(1, (int) Math.round(tall * scale));
        int[] preview = new int[previewWidth * previewHeight];
        int[] row = new int[width];
        int[] columns = new int[previewWidth];
        for (int x = 0; x < previewWidth; x++)
            columns[x] = Math.min(width - 1, (int) ((x + 0.5) * width / previewWidth));
        for (int y = 0; y < previewHeight; y++) {
            int source = Math.min(tall - 1, (int) ((y + 0.5) * tall / previewHeight));
            rows.read(top + source, row, 0);
            int offset = y * previewWidth;
            for (int x = 0; x < previewWidth; x++)
                preview[offset + x] = row[columns[x]] | 0xFF000000;
        }
        size[0] = previewWidth;
        size[1] = previewHeight;
        return preview;
    }

    /** Rows kept in blocks, so the picture grows either way without being copied. */
    private static final class Rows {

        private final int width;
        private final int blockRows;
        private final List<int[]> blocks = new ArrayList<>();
        /** The row the first block begins at. */
        private int firstRow;

        Rows(int width) {
            this.width = width;
            this.blockRows = Math.max(1, (1 << 20) / width);
        }

        private int[] block(int row) {
            while (row < firstRow) {
                blocks.addFirst(new int[blockRows * width]);
                firstRow -= blockRows;
            }
            while (row >= firstRow + blocks.size() * blockRows)
                blocks.add(new int[blockRows * width]);
            return blocks.get((row - firstRow) / blockRows);
        }

        void write(int row, int[] source, int sourceOffset) {
            int[] block = block(row);
            System.arraycopy(source, sourceOffset, block,
                    ((row - firstRow) % blockRows) * width, width);
        }

        void read(int row, int[] target, int targetOffset) {
            int[] block = block(row);
            System.arraycopy(block, ((row - firstRow) % blockRows) * width, target,
                    targetOffset, width);
        }
    }
}
