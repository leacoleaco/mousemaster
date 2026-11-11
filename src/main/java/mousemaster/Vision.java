package mousemaster;

import mousemaster.platform.Overlay;
import mousemaster.platform.DesktopCapture;
import mousemaster.platform.UiAutomation;
import mousemaster.platform.UiAutomation.UiElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Runs the stages: ink, dilate, find components, cut each one into elements. */
public class Vision {

    private static final Logger logger = LoggerFactory.getLogger(Vision.class);

    private static final int downsampleFactor = 2;
    /** Detection pixels, about the smallest glyph a screen draws. */
    private static final int minimumElementPixels = 44;
    /** Detection pixels, past which the lighter midpoint has found the pane itself. */
    private static final int maximumLightElementPixels = 500;
    /** Bigger than this is a pane, not something to click. */
    private static final double maximumElementAreaRatio = 0.25;

    private static final int minimumBoundedSide = 2;
    private static final int minimumBoundedArea = 30;
    private static final int[] cornerOutsets = {0, 2, 5, 10};
    /** Antialiasing shortens ink towards the background without turning it. */
    private static final double minimumInkCosine = 0.97;
    /** What tells grey ink from black. */
    private static final double minimumInkLengthRatio = 0.5;
    private static final int widestEdgeMargin = 6;
    private static final int narrowestEdgeMargin = 1;
    private static final int edgeSteps = widestEdgeMargin - narrowestEdgeMargin + 1;
    private static final int verticalEdgeMargin = 1;
    private static final int firstDensityEdgeMargin = 3;
    private static final int inkGap = 3;
    /** Plain text left out, one for each edge margin, then one for the ink. */
    static final int densities = edgeSteps + 2;
    /** Second press: midpoints of long horizontal and vertical straight boundaries. */
    static final int straightLines = 1;
    /** Third press: centres of large, regular areas of one colour. The next press starts again. */
    static final int blankAreas = 2;
    static final int lastRefine = blankAreas;
    /** Screen pixels. A straight line shorter than this is not kept. */
    private static final int longLineScreenPixels = 80;
    /** Levels of 255 between the two sides of a boundary. A dark theme parts two panels
     *  by about 7. */
    private static final int boundaryContrast = 5;
    /** Levels either side may move from one pixel of a boundary to the next. */
    private static final int sideTolerance = 10;
    /** Detection pixels a line may be missing where another line of its colour crosses. */
    private static final int lineGap = 3;
    /** Detection pixels. A line made of stretches shorter than this is text or a picture. */
    private static final int steadyStretch = 16;
    /** Screen pixels to a side of the cells a blank area is made of, about. */
    private static final int blankCellScreenPixels = 4;
    /** Levels a cell may span and still be one colour. */
    private static final int blankCellRange = 5;
    /** Levels between two touching cells of one area. */
    private static final int blankJoin = 5;
    /** Screen pixels. The short side of a blank rectangle is at least the first, and its
     *  long side at least the second. */
    private static final int blankShortScreenPixels = 40;
    private static final int blankLongScreenPixels = 80;
    /** Unzoomed pixels between two feature tags, and from a feature tag to one already
     *  shown. The limit is what eleven keys label with two letters. */
    private static final double featureSpacing = UiAutomation.MIN_DISTANCE_BETWEEN_HINTS_UNZOOMED;
    private static final double featureShownRadius = 8;
    private static final int featureLimit = 120;
    /** Unzoomed pixels. A new tag whose centre is this close sits on the one already shown. */
    private static final double shownTagRadiusUnzoomed = 18;
    private static final String dumpPath = System.getProperty("vision.dump");

    private final Overlay overlay;
    private final ExecutorService detectExecutor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "vision-detect");
                thread.setDaemon(true);
                return thread;
            });

    public Vision(Overlay overlay) {
        this.overlay = overlay;
    }

    public Future<List<UiElement>> startFindElements(Set<Screen> screens,
                                                     Rectangle area, int density,
                                                     double minSize) {
        return startFindElements(screens, area, density, minSize, 0, List.of());
    }

    /**
     * {@code refine} 0 is the ordinary region look. {@link #straightLines} and
     * {@link #blankAreas} are the other two features, and they leave out anything that
     * would sit on {@code occupied}. The press after {@link #lastRefine} starts again at 0.
     */
    public Future<List<UiElement>> startFindElements(Set<Screen> screens,
                                                     Rectangle area, int density,
                                                     double minSize, int refine,
                                                     List<Rectangle> occupied) {
        List<Rectangle> avoided = List.copyOf(occupied);
        List<Supplier<List<UiElement>>> detections = new ArrayList<>();
        for (Screen screen : screens) {
            Rectangle part = area.intersection(screen.rectangle());
            if (part.isEmpty())
                continue;
            double downscale = Math.max(1, screen.scale());
            DesktopCapture capture;
            try {
                capture = overlay.captureDesktop(part,
                        Math.max(1, (int) Math.ceil(part.width() / downscale)),
                        Math.max(1, (int) Math.ceil(part.height() / downscale)));
            }
            catch (Throwable e) {
                logger.warn("Desktop capture failed, no vision hints on that screen", e);
                continue;
            }
            detections.add(() -> findElements(capture, screen.scale(), density, minSize,
                    refine, avoided));
        }
        if (detections.isEmpty())
            return CompletableFuture.completedFuture(List.of());
        return detectExecutor.submit(() -> {
            long begin = System.nanoTime();
            List<UiElement> elements = new ArrayList<>();
            for (Supplier<List<UiElement>> detection : detections)
                elements.addAll(detection.get());
            logger.debug("Vision found " + elements.size() + " elements on " +
                         detections.size() + " screen(s) in " +
                         (System.nanoTime() - begin) / 1_000_000 + "ms" +
                         (refine == 0 ? "" :
                                 ", refine " + refine + ", avoiding " + avoided.size()));
            return elements;
        });
    }

    public void preWarm(Screen screen, int density) {
        startFindElements(Set.of(screen), screen.rectangle(), density, 0);
    }

    List<UiElement> findElements(DesktopCapture capture, double scale, int density,
                                 double minSize) {
        return findElements(capture, scale, density, minSize, 0, List.of());
    }

    /**
     * {@code refine} 0 is the region look. A higher value is a different feature, and drops
     * anything that would cover a tag in {@code occupied}.
     */
    List<UiElement> findElements(DesktopCapture capture, double scale, int density,
                                 double minSize, int refine, List<Rectangle> occupied) {
        if (refine > 0)
            return featureElements(capture, scale, refine, occupied);
        int width = capture.scaledWidth();
        int height = capture.scaledHeight();
        int lookedDensity = density;
        int gap = inkGap;
        int minInkPixels = minimumElementPixels;
        int minArea = minimumBoundedArea;
        double spacing = UiAutomation.MIN_DISTANCE_BETWEEN_HINTS_UNZOOMED;
        // Plain text left out, then the edge margin narrows, then the ink no region holds.
        int step = Math.clamp(lookedDensity - 2, 0, edgeSteps - 1);
        int edgeMargin = lookedDensity == 1 ? firstDensityEdgeMargin : widestEdgeMargin - step;
        CompletableFuture<List<Rectangle>> ink = lookedDensity == densities
                ? CompletableFuture.supplyAsync(
                        () -> inkBoxes(capture.scaledRgb(), width, height, gap, minInkPixels))
                : CompletableFuture.completedFuture(List.of());
        List<List<Rectangle>> byMidpoint =
                IntStream.range(0, EdgeDetector.contrastMidpoints.length)
                         .parallel()
                         .mapToObj(midpoint -> boundedRegions(capture.scaledRgb(),
                                 width, height, scale,
                                 edgeMargin, verticalEdgeMargin, midpoint, minArea))
                         .toList();
        List<Rectangle> boxes = new ArrayList<>(byMidpoint.getFirst());
        // A lighter midpoint answers only where the first one found nothing.
        for (int midpoint = 1; midpoint < byMidpoint.size(); midpoint++)
            addUncontained(boxes, byMidpoint.get(midpoint));
        if (lookedDensity == 1)
            boxes = boxes.parallelStream()
                         .filter(box -> isGaplessOrBordered(capture.scaledRgb(), width,
                                 height, box))
                         .collect(Collectors.toCollection(ArrayList::new));
        List<Rectangle> inkBoxes = new ArrayList<>(ink.join());
        Rectangle bounds = capture.bounds();
        double screenScaleX = (double) bounds.width() / width;
        double screenScaleY = (double) bounds.height() / height;
        addUncontained(boxes, inkBoxes);
        if (dumpPath != null)
            dump(capture.scaledRgb(),
                    InkDetector.ink(capture.scaledRgb(), width, height), boxes, width,
                    height);
        // Greedy suppression keeps whichever element comes first, so order by area.
        boxes.sort(Comparator.comparingLong(box -> -area(box)));
        List<UiElement> elements = new ArrayList<>(boxes.size());
        List<UiElement> trimmedElements = new ArrayList<>(boxes.size());
        for (Rectangle box : boxes) {
            if (Math.min(box.width() * screenScaleX, box.height() * screenScaleY)
                < minSize * scale)
                continue;
            UiElement element = element(box, bounds, screenScaleX, screenScaleY);
            UiElement trimmedElement = element(trimmed(capture.scaledRgb(), width, height, box),
                    bounds, screenScaleX, screenScaleY);
            if (!UiAutomation.isTooCloseToExistingUiElements(elements, element.centerX(),
                    element.centerY(), scale, spacing)) {
                elements.add(element);
                trimmedElements.add(trimmedElement);
            }
        }
        return trimmedElements;
    }

    /**
     * The second press marks straight colour boundaries, horizontal and vertical.
     * The third marks blank rectangles. The longest or largest come first, spread
     * apart, and none sits on a tag already shown.
     */
    private static List<UiElement> featureElements(DesktopCapture capture, double scale,
                                                  int refine, List<Rectangle> occupied) {
        int width = capture.scaledWidth();
        int height = capture.scaledHeight();
        byte[] rgb = capture.scaledRgb();
        Rectangle bounds = capture.bounds();
        double screenScaleX = (double) bounds.width() / width;
        double screenScaleY = (double) bounds.height() / height;
        List<Mark> marks = new ArrayList<>();
        if (refine == straightLines) {
            marks.addAll(straightBoundaries(rgb, width, height, true,
                    detectionPixels(longLineScreenPixels, screenScaleX)));
            marks.addAll(straightBoundaries(rgb, width, height, false,
                    detectionPixels(longLineScreenPixels, screenScaleY)));
        }
        else {
            double screenScale = Math.max(screenScaleX, screenScaleY);
            int cell = Math.max(1, (int) Math.round(blankCellScreenPixels / screenScale));
            marks.addAll(blankCenters(rgb, width, height, cell,
                    wholeCells(blankShortScreenPixels, cell * screenScale),
                    wholeCells(blankLongScreenPixels, cell * screenScale)));
        }
        marks.sort(Comparator.comparingInt(Mark::score).reversed());
        List<UiElement> elements = new ArrayList<>();
        for (Mark mark : marks) {
            UiElement element = element(new Rectangle(mark.x(), mark.y(), 1, 1), bounds,
                    screenScaleX, screenScaleY);
            if (coversShownTag(element.rectangle(), occupied, scale, featureShownRadius))
                continue;
            if (!UiAutomation.isTooCloseToExistingUiElements(elements, element.centerX(),
                    element.centerY(), scale, featureSpacing))
                elements.add(element);
            if (elements.size() == featureLimit)
                break;
        }
        return elements;
    }

    private record Mark(int x, int y, int score) {}

    /** A length given in screen pixels, in the pixels detection works in. */
    private static int detectionPixels(int screenPixels, double screenPixelsPerPixel) {
        return Math.max(1, (int) Math.ceil(screenPixels / screenPixelsPerPixel));
    }

    /**
     * How many whole cells a blank of that many screen pixels is sure to hold: one less
     * than span it, because it need not start where a cell does.
     */
    private static int wholeCells(int screenPixels, double screenPixelsPerCell) {
        return Math.max(1, (int) Math.ceil(screenPixels / screenPixelsPerCell) - 1);
    }

    /**
     * Midpoints of straight colour boundaries. A stretch is a run of pixels along one row
     * or column where the two sides differ and each side keeps its colour: a drawn stroke
     * is two such boundaries, and the meeting of two panels is one. A line is the
     * stretches that follow one another, so a rule that other rules cross, or that runs
     * under panels of several colours, is still one line. A line and each of its stretches
     * that is long enough gets its midpoint. Text and pictures are short stretches only.
     */
    private static List<Mark> straightBoundaries(byte[] rgb, int width, int height,
                                                boolean horizontal, int longLinePixels) {
        int alongCount = horizontal ? width : height;
        int acrossCount = horizontal ? height : width;
        List<Mark> marks = new ArrayList<>();
        for (int across = 1; across < acrossCount; across++) {
            int start = -1;
            int near = 0, far = 0;
            boolean crisp = true;
            int lineStart = -1, lineEnd = -1, stretches = 0, steady = 0, lineMarks = 0;
            for (int along = 0; along <= alongCount; along++) {
                boolean boundary = false, direct = false;
                int nextNear = 0, nextFar = 0;
                if (along < alongCount) {
                    nextNear = pixel(rgb, width, along, across - 1, horizontal);
                    nextFar = pixel(rgb, width, along, across, horizontal);
                    direct = distance(nextNear, nextFar) >= boundaryContrast;
                    boundary = direct;
                    if (!boundary && across + 1 < acrossCount) {
                        // The downscale can spread one step over two pixels.
                        nextFar = pixel(rgb, width, along, across + 1, horizontal);
                        boundary = distance(nextNear, nextFar) >= boundaryContrast;
                    }
                }
                if (start >= 0 && !(boundary
                                    && distance(near, nextNear) <= sideTolerance
                                    && distance(far, nextFar) <= sideTolerance)) {
                    int length = along - start;
                    // Of the boundaries one edge makes, the crisp one is where it is.
                    if (length >= longLinePixels)
                        marks.add(lineMark(start, length, across, crisp ? 1 : 0, horizontal));
                    if (length >= steadyStretch)
                        steady += length;
                    lineEnd = along - 1;
                    start = -1;
                }
                if (boundary && start < 0) {
                    start = along;
                    crisp = true;
                    if (lineStart < 0) {
                        lineStart = along;
                        stretches = 0;
                        steady = 0;
                        lineMarks = marks.size();
                    }
                    stretches++;
                }
                else if (!boundary && lineStart >= 0
                         && (along - lineEnd > lineGap || along == alongCount)) {
                    int length = lineEnd - lineStart + 1;
                    if (stretches > 1 && length >= longLinePixels
                        && steady * 5 >= length * 4) {
                        Mark line = lineMark(lineStart, length, across, 0, horizontal);
                        // A stretch whose midpoint is the line's own says nothing more.
                        marks.subList(lineMarks, marks.size()).removeIf(stretch ->
                                Math.abs(stretch.x() - line.x()) + Math.abs(stretch.y() - line.y())
                                < longLinePixels / 2);
                        marks.add(line);
                    }
                    lineStart = -1;
                }
                crisp &= direct;
                near = nextNear;
                far = nextFar;
            }
        }
        return marks;
    }

    private static Mark lineMark(int start, int length, int across, int crisp,
                                 boolean horizontal) {
        int middle = start + length / 2;
        int score = 2 * length + crisp;
        return horizontal ? new Mark(middle, across, score) : new Mark(across, middle, score);
    }

    private static int pixel(byte[] rgb, int width, int along, int across,
                             boolean horizontal) {
        return horizontal ? color(rgb, width, along, across) : color(rgb, width, across, along);
    }

    /**
     * Centres of blank rectangles. The frame is cut into cells, a cell is blank when
     * all of it is one colour, and blank cells of a near colour that touch are one area.
     * The largest rectangle that fits in an area is taken, then the largest that fits in
     * what is left, until none has a short side of {@code shortCells} and a long side of
     * {@code longCells}. So every blank strip that wide is reached, whole or in parts.
     * When there are more than can be shown, the largest are the ones returned.
     */
    private static List<Mark> blankCenters(byte[] rgb, int width, int height, int blankCell,
                                           int shortCells, int longCells) {
        int columns = width / blankCell;
        int rows = height / blankCell;
        List<Mark> marks = new ArrayList<>();
        if (columns == 0 || rows == 0)
            return marks;
        int[] areas = blankAreas(rgb, width, blankCell, columns, rows);
        BlankQueue candidates = new BlankQueue(shortCells, longCells);
        blankRectangles(areas, columns, rows, candidates);
        List<Rectangle> taken = new ArrayList<>();
        while (taken.size() < featureLimit && !candidates.largestFirst().isEmpty()) {
            Rectangle candidate = candidates.largestFirst().poll();
            Rectangle cut = null;
            for (Rectangle before : taken)
                if (before.x() < candidate.x() + candidate.width()
                    && candidate.x() < before.x() + before.width()
                    && before.y() < candidate.y() + candidate.height()
                    && candidate.y() < before.y() + before.height()) {
                    cut = before;
                    break;
                }
            if (cut == null) {
                taken.add(candidate);
                continue;
            }
            // Whatever still fits in it lies wholly to one side of the rectangle taken.
            int right = candidate.x() + candidate.width();
            int bottom = candidate.y() + candidate.height();
            addBlank(candidates, candidate.x(), candidate.y(), cut.x() - candidate.x(),
                    candidate.height());
            addBlank(candidates, cut.x() + cut.width(), candidate.y(),
                    right - cut.x() - cut.width(), candidate.height());
            addBlank(candidates, candidate.x(), candidate.y(), candidate.width(),
                    cut.y() - candidate.y());
            addBlank(candidates, candidate.x(), cut.y() + cut.height(), candidate.width(),
                    bottom - cut.y() - cut.height());
        }
        for (Rectangle blank : taken)
            marks.add(new Mark((2 * blank.x() + blank.width()) * blankCell / 2,
                    (2 * blank.y() + blank.height()) * blankCell / 2,
                    blank.width() * blank.height()));
        return marks;
    }

    private static void addBlank(BlankQueue blanks, int x, int y, int wide, int tall) {
        if (Math.min(tall, wide) < blanks.shortCells || Math.max(tall, wide) < blanks.longCells)
            return;
        Rectangle blank = new Rectangle(x, y, wide, tall);
        // Many rectangles leave the same remainder when one is taken out of them.
        if (blanks.queued.add(blank))
            blanks.largestFirst.add(blank);
    }

    private record BlankQueue(PriorityQueue<Rectangle> largestFirst, Set<Rectangle> queued,
                              int shortCells, int longCells) {
        BlankQueue(int shortCells, int longCells) {
            this(new PriorityQueue<>(Comparator.<Rectangle>comparingLong(box -> -area(box))
                                               .thenComparingInt(Rectangle::y)
                                               .thenComparingInt(Rectangle::x)
                                               .thenComparingInt(Rectangle::width)),
                    new HashSet<>(), shortCells, longCells);
        }
    }

    /**
     * The area each cell belongs to, or -1 where the cell is not one colour.
     */
    private static int[] blankAreas(byte[] rgb, int width, int blankCell, int columns,
                                    int rows) {
        int[] colors = new int[columns * rows];
        boolean[] blank = new boolean[columns * rows];
        for (int row = 0; row < rows; row++)
            for (int column = 0; column < columns; column++) {
                int lowRed = 255, lowGreen = 255, lowBlue = 255;
                int highRed = 0, highGreen = 0, highBlue = 0;
                for (int y = row * blankCell; y < (row + 1) * blankCell; y++)
                    for (int x = column * blankCell; x < (column + 1) * blankCell; x++) {
                        int color = color(rgb, width, x, y);
                        int red = color >> 16, green = color >> 8 & 0xff, blue = color & 0xff;
                        lowRed = Math.min(lowRed, red);
                        highRed = Math.max(highRed, red);
                        lowGreen = Math.min(lowGreen, green);
                        highGreen = Math.max(highGreen, green);
                        lowBlue = Math.min(lowBlue, blue);
                        highBlue = Math.max(highBlue, blue);
                    }
                int cell = row * columns + column;
                blank[cell] = highRed - lowRed <= blankCellRange
                              && highGreen - lowGreen <= blankCellRange
                              && highBlue - lowBlue <= blankCellRange;
                colors[cell] = (lowRed + highRed) / 2 << 16 | (lowGreen + highGreen) / 2 << 8 |
                               (lowBlue + highBlue) / 2;
            }
        int[] areas = new int[columns * rows];
        Arrays.fill(areas, -1);
        int[] queue = new int[columns * rows];
        int area = 0;
        for (int first = 0; first < areas.length; first++) {
            if (!blank[first] || areas[first] >= 0)
                continue;
            int head = 0, tail = 0;
            queue[tail++] = first;
            areas[first] = area;
            while (head < tail) {
                int cell = queue[head++];
                int column = cell % columns;
                int[] neighbors = {column > 0 ? cell - 1 : -1,
                                   column < columns - 1 ? cell + 1 : -1,
                                   cell - columns, cell + columns};
                for (int neighbor : neighbors) {
                    if (neighbor < 0 || neighbor >= areas.length || !blank[neighbor]
                        || areas[neighbor] >= 0
                        || distance(colors[cell], colors[neighbor]) > blankJoin)
                        continue;
                    areas[neighbor] = area;
                    queue[tail++] = neighbor;
                }
            }
            area++;
        }
        return areas;
    }

    /**
     * Into {@code blanks}, every rectangle of cells that lies inside one area, is large
     * enough and cannot be grown to any side: the rectangles under a histogram of how far
     * each column of the area reaches up from this row, taken row by row, less those
     * that go on into the row below.
     */
    private static void blankRectangles(int[] areas, int columns, int rows,
                                        BlankQueue blanks) {
        int[] heights = new int[columns];
        int[] stack = new int[columns + 1];
        // Up to each column, how many of this row's cells go on into the row below.
        int[] goingOn = new int[columns + 1];
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                int area = areas[row * columns + column];
                heights[column] = area < 0 ? 0
                        : row > 0 && areas[(row - 1) * columns + column] == area
                                ? heights[column] + 1 : 1;
                goingOn[column + 1] = goingOn[column] + (area >= 0 && row + 1 < rows
                        && areas[(row + 1) * columns + column] == area ? 1 : 0);
            }
            int size = 0;
            int segment = 0;
            for (int column = 0; column <= columns; column++) {
                // Another area ends every rectangle still open.
                boolean continues = column > 0 && column < columns
                                    && areas[row * columns + column]
                                       == areas[row * columns + column - 1];
                int floor = continues ? heights[column] : -1;
                while (size > 0 && heights[stack[size - 1]] > floor) {
                    int tall = heights[stack[--size]];
                    int left = size == 0 ? segment : stack[size - 1] + 1;
                    if (goingOn[column] - goingOn[left] < column - left)
                        addBlank(blanks, left, row - tall + 1, column - left, tall);
                }
                if (!continues)
                    segment = column;
                if (column == columns)
                    break;
                // Columns of one height are one step, which ends at the last of them.
                if (size > 0 && heights[stack[size - 1]] == heights[column])
                    stack[size - 1] = column;
                else
                    stack[size++] = column;
            }
        }
    }

    /**
     * Whether {@code candidate} would put its tag on one already shown: the centres are
     * within a tag of each other, or each centre lies inside the other box (the same
     * control found again, shifted). A smaller control inside a larger one stays, which
     * is the place the earlier look did not mark.
     */
    static boolean coversShownTag(Rectangle candidate, List<Rectangle> shown, double scale) {
        return coversShownTag(candidate, shown, scale, shownTagRadiusUnzoomed);
    }

    /** {@code radiusUnzoomed} is how near two centres are when the tags are one place. */
    static boolean coversShownTag(Rectangle candidate, List<Rectangle> shown, double scale,
                                  double radiusUnzoomed) {
        if (shown.isEmpty())
            return false;
        double radius = radiusUnzoomed * scale;
        double radiusSquared = radius * radius;
        double x = candidate.center().x();
        double y = candidate.center().y();
        for (Rectangle old : shown) {
            double dx = old.center().x() - x;
            double dy = old.center().y() - y;
            if (dx * dx + dy * dy <= radiusSquared)
                return true;
            // On the boundary is the cut between two blocks, not the same control again.
            if (strictlyContains(old, x, y) &&
                strictlyContains(candidate, old.center().x(), old.center().y()))
                return true;
        }
        return false;
    }

    private static boolean strictlyContains(Rectangle box, double pointX, double pointY) {
        return pointX > box.x() && pointX < box.x() + box.width() &&
               pointY > box.y() && pointY < box.y() + box.height();
    }

    private static UiElement element(Rectangle box, Rectangle bounds, double screenScaleX,
                                     double screenScaleY) {
        int left = bounds.x() + (int) Math.round(box.x() * screenScaleX);
        int top = bounds.y() + (int) Math.round(box.y() * screenScaleY);
        int right = bounds.x() + (int) Math.round((box.x() + box.width()) * screenScaleX);
        int bottom = bounds.y() + (int) Math.round((box.y() + box.height()) * screenScaleY);
        return new UiElement(new Rectangle(left, top, right - left, bottom - top));
    }

    /** A pane, a box around other boxes, hides nothing. */
    private static void addUncontained(List<Rectangle> boxes, List<Rectangle> others) {
        List<Rectangle> sortedBoxes = boxes.stream().sorted(Comparator.comparingLong(Vision::area))
                                           .toList();
        Map<Rectangle, Boolean> isPaneByBox = new HashMap<>();
        for (Rectangle other : others)
            if (boxes.stream().noneMatch(box -> box.contains(other)
                    && !isPaneByBox.computeIfAbsent(box, pane -> isPane(pane, sortedBoxes))))
                boxes.add(other);
    }

    private static boolean isPane(Rectangle box, List<Rectangle> sortedBoxes) {
        for (Rectangle sortedBox : sortedBoxes) {
            if (area(sortedBox) > area(box))
                return false;
            if (sortedBox != box && box.contains(sortedBox))
                return true;
        }
        return false;
    }

    private static long area(Rectangle box) {
        return (long) box.width() * box.height();
    }

    /** What the ink splits into once cut apart: one hint each. */
    private static List<Rectangle> inkBoxes(byte[] rgb, int width, int height,
                                           int minimumGapColumns, int minimumPixels) {
        int downsampledWidth = (width + downsampleFactor - 1) / downsampleFactor;
        int downsampledHeight = (height + downsampleFactor - 1) / downsampleFactor;
        long[] ink = InkDetector.ink(rgb, width, height);
        long[] mask = InkDetector.downsampledInk(
                InkDetector.dilated(ink, width, height), width, height, downsampleFactor,
                downsampledWidth, downsampledHeight);
        SummedAreaTable inkTable = new SummedAreaTable(ink, width, height);
        double maximumElementArea = maximumElementAreaRatio * width * height;
        return                 ConnectedComponentFinder.boundingBoxes(mask, downsampledWidth,
                        downsampledHeight,
                        minimumPixels / (downsampleFactor * downsampleFactor))
                .parallelStream()
                .flatMap(box -> {
                    List<Rectangle> parts = new ArrayList<>();
                    XyCut.cut(ink, inkTable, width,
                            detectionBox(box, width, height), maximumElementArea,
                            minimumGapColumns, parts);
                    return parts.stream();
                })
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /** What an edge draws a boundary around: one hint for what the ink inside splits up. */
    private static List<Rectangle> boundedRegions(byte[] rgb, int width, int height,
                                                  double scale, int edgeDilation,
                                                  int verticalDilation, int midpoint,
                                                  int minimumArea) {
        long[] edges = EdgeDetector.edges(rgb, width, height, edgeDilation,
                verticalDilation, midpoint);
        List<Rectangle> bounded = new ArrayList<>();
        // No floor: the dilation already makes every component wider than one.
        for (Rectangle region : ConnectedComponentFinder.boundingBoxes(edges, width,
                height, 1)) {
            // Back to source pixels, less the margins the dilation added.
            Rectangle inside = new Rectangle(region.x() + edgeDilation,
                    region.y() + verticalDilation,
                    region.width() - 2 * edgeDilation,
                    region.height() - 2 * verticalDilation);
            if (midpoint > 0 && area(inside) > maximumLightElementPixels)
                continue;
            if (!isTargetShaped((int) (inside.width() * scale),
                    (int) (inside.height() * scale), minimumArea))
                continue;
            int background = cornerBackground(rgb, width, height, inside);
            if (background < 0)
                continue;
            for (Rectangle part : inkParts(rgb, width, inside, background))
                if (isTargetShaped((int) (part.width() * scale),
                        (int) (part.height() * scale), minimumArea))
                    bounded.add(part);
        }
        return bounded;
    }

    /** Ink without a gap, as an icon or a filled button draws, or a border on every side.
     *  A column under half the strongest is a gap: antialiasing only blurs a word's. */
    private static boolean isGaplessOrBordered(byte[] rgb, int width, int height,
                                               Rectangle part) {
        int background = cornerBackground(rgb, width, height, part);
        if (background < 0)
            return false;
        int[] distances = new int[part.width()];
        for (int column = 0; column < part.width(); column++)
            for (int y = part.y(); y < part.y() + part.height(); y++)
                distances[column] = Math.max(distances[column],
                        distance(color(rgb, width, part.x() + column, y), background));
        int strongest = Arrays.stream(distances).max().orElse(0);
        int runs = 0;
        boolean inked = false;
        for (int distance : distances) {
            boolean ink = isInk(distance) && distance * 2 >= strongest;
            if (ink && !inked)
                runs++;
            inked = ink;
        }
        return runs == 1
               || isBorder(rgb, width, height, part, background, 0, -1)
                  && isBorder(rgb, width, height, part, background, 0, 1)
                  && isBorder(rgb, width, height, part, background, -1, 0)
                  && isBorder(rgb, width, height, part, background, 1, 0);
    }

    /** Whether the nearest ink on that side of the part is a line along all of it. */
    private static boolean isBorder(byte[] rgb, int width, int height, Rectangle part,
                                    int background, int dx, int dy) {
        for (int outset = 1; ; outset++) {
            Rectangle line = dy == 0
                    ? new Rectangle(dx < 0 ? part.x() - outset
                                           : part.x() + part.width() - 1 + outset,
                            part.y(), 1, part.height())
                    : new Rectangle(part.x(), dy < 0 ? part.y() - outset
                                                     : part.y() + part.height() - 1 + outset,
                            part.width(), 1);
            if (line.x() < 0 || line.y() < 0 || line.x() + line.width() > width
                || line.y() + line.height() > height)
                return false;
            int ink = inkPixels(rgb, width, line, background);
            if (ink > 0)
                return ink == line.width() * line.height();
        }
    }

    private static int inkPixels(byte[] rgb, int width, Rectangle area, int background) {
        int pixels = 0;
        for (int y = area.y(); y < area.y() + area.height(); y++)
            for (int x = area.x(); x < area.x() + area.width(); x++)
                if (isInk(distance(color(rgb, width, x, y), background)))
                    pixels++;
        return pixels;
    }

    /** A quarter of the ink threshold, which the faintest outlines reach once scaled down. */
    private static boolean isInk(int distance) {
        return distance > InkDetector.inkThreshold / 4;
    }

    /** The box trimmed to its ink against its corner background. */
    private static Rectangle trimmed(byte[] rgb, int width, int height, Rectangle box) {
        int background = cornerBackground(rgb, width, height, box);
        if (background < 0)
            return box;
        int left = box.x(), top = box.y();
        int right = left + box.width() - 1, bottom = top + box.height() - 1;
        while (top <= bottom && !hasInk(rgb, width, left, top, right, top, background))
            top++;
        if (top > bottom)
            return box;
        while (!hasInk(rgb, width, left, bottom, right, bottom, background))
            bottom--;
        while (!hasInk(rgb, width, left, top, left, bottom, background))
            left++;
        while (!hasInk(rgb, width, right, top, right, bottom, background))
            right--;
        return new Rectangle(left, top, right - left + 1, bottom - top + 1);
    }

    private static boolean hasInk(byte[] rgb, int width, int left, int top, int right,
                                  int bottom, int background) {
        for (int y = top; y <= bottom; y++)
            for (int x = left; x <= right; x++)
                if (isInk(distance(color(rgb, width, x, y), background)))
                    return true;
        return false;
    }

    /** The box parted at each gap between ink of two colours. */
    private static List<Rectangle> inkParts(byte[] rgb, int width, Rectangle box,
                                            int background) {
        int[] inks = new int[box.width()];
        int[] distances = new int[box.width()];
        for (int y = box.y(); y < box.y() + box.height(); y++) {
            for (int column = 0; column < box.width(); column++) {
                int color = color(rgb, width, box.x() + column, y);
                int distance = distance(color, background);
                if (distance > distances[column]) {
                    inks[column] = color;
                    distances[column] = distance;
                }
            }
        }
        List<Rectangle> parts = new ArrayList<>();
        int left = 0, right = -1, ink = 0;
        for (int column = 0; column < box.width(); column++) {
            if (distances[column] <= InkDetector.inkThreshold)
                continue;
            int runLeft = column, farthest = column;
            while (column + 1 < box.width()
                   && distances[column + 1] > InkDetector.inkThreshold)
                if (distances[++column] > distances[farthest])
                    farthest = column;
            if (right < 0)
                left = runLeft;
            else if (!sameInk(background, ink, inks[farthest])) {
                parts.add(new Rectangle(box.x() + left, box.y(), right - left + 1,
                        box.height()));
                left = runLeft;
            }
            right = column;
            ink = inks[farthest];
        }
        if (parts.isEmpty())
            return List.of(box);
        parts.add(new Rectangle(box.x() + left, box.y(), right - left + 1, box.height()));
        return parts;
    }

    private static boolean sameInk(int background, int ink, int otherInk) {
        double dot = 0, length = 0, otherLength = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            int channel = (ink >> shift & 0xff) - (background >> shift & 0xff);
            int otherChannel = (otherInk >> shift & 0xff) - (background >> shift & 0xff);
            dot += channel * otherChannel;
            length += channel * channel;
            otherLength += otherChannel * otherChannel;
        }
        length = Math.sqrt(length);
        otherLength = Math.sqrt(otherLength);
        return dot >= minimumInkCosine * length * otherLength
               && Math.min(length, otherLength)
                  >= minimumInkLengthRatio * Math.max(length, otherLength);
    }

    private static int distance(int color, int otherColor) {
        int distance = 0;
        for (int shift = 0; shift <= 16; shift += 8)
            distance = Math.max(distance,
                    Math.abs((color >> shift & 0xff) - (otherColor >> shift & 0xff)));
        return distance;
    }

    private static boolean isTargetShaped(int width, int height, int minimumArea) {
        return width >= minimumBoundedSide && height >= minimumBoundedSide
               && (long) width * height >= minimumArea;
    }

    /** A box straddling unrelated content has corners of its own at every outset: -1. */
    private static int cornerBackground(byte[] rgb, int width, int height, Rectangle box) {
        for (int outset : cornerOutsets) {
            int x0 = box.x() - outset;
            int x1 = box.x() + box.width() + outset;
            int y0 = box.y();
            int y1 = box.y() + box.height();
            if (x0 < 0 || y0 < 0 || x1 >= width || y1 >= height)
                continue;
            int[] corners = {color(rgb, width, x0, y0), color(rgb, width, x1, y0),
                             color(rgb, width, x0, y1), color(rgb, width, x1, y1)};
            for (int corner = 0; corner < corners.length; corner++)
                for (int other = corner + 1; other < corners.length; other++)
                    if (corners[corner] == corners[other])
                        return corners[corner];
        }
        return -1;
    }

    private static int color(byte[] rgb, int width, int x, int y) {
        int channel = (y * width + x) * 3;
        return (rgb[channel] & 0xff) << 16 | (rgb[channel + 1] & 0xff) << 8 |
               rgb[channel + 2] & 0xff;
    }

    /** A grid box in the detected frame's pixels, clamped to it. */
    private static Rectangle detectionBox(Rectangle box, int width, int height) {
        int left = box.x() * downsampleFactor;
        int top = box.y() * downsampleFactor;
        return new Rectangle(left, top,
                Math.min(width, (box.x() + box.width()) * downsampleFactor) - left,
                Math.min(height, (box.y() + box.height()) * downsampleFactor) - top);
    }

    /** The capture with its ink in red and every emitted box outlined in green. */
    private static void dump(byte[] rgb, long[] ink, List<Rectangle> boxes, int width,
                             int height) {
        byte[] image = rgb.clone();
        int words = Dilation.words(width);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                if (Dilation.isSet(ink, words, x, y))
                    paint(image, y * width + x, 255, 0, 0);
        for (Rectangle box : boxes) {
            for (int x = box.x(); x < box.x() + box.width(); x++) {
                paint(image, box.y() * width + x, 0, 255, 0);
                paint(image, (box.y() + box.height() - 1) * width + x, 0, 255, 0);
            }
            for (int y = box.y(); y < box.y() + box.height(); y++) {
                paint(image, y * width + box.x(), 0, 255, 0);
                paint(image, y * width + box.x() + box.width() - 1, 0, 255, 0);
            }
        }
        try (OutputStream out = Files.newOutputStream(Path.of(dumpPath))) {
            out.write(("P6 " + width + " " + height + " 255 ").getBytes());
            out.write(image);
        }
        catch (IOException e) {
            logger.warn("Vision dump failed", e);
        }
    }

    private static void paint(byte[] image, int pixel, int red, int green, int blue) {
        image[pixel * 3] = (byte) red;
        image[pixel * 3 + 1] = (byte) green;
        image[pixel * 3 + 2] = (byte) blue;
    }

}
