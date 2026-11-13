package mousemaster;

import mousemaster.Command.CopyItem;
import mousemaster.Command.RunCopyItem;
import mousemaster.platform.ScreenTools;
import mousemaster.platform.ScreenTools.Sprite;
import mousemaster.platform.UiAutomation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The copy menu and the tools it opens: the word picker, the screenshot, and the long
 * screenshot.
 * <p>
 * The commands arrive on the thread that reads the keyboard, which must return at once:
 * a command only records what was asked. Reading text, reading the screen, joining
 * pictures and writing the clipboard run on a thread of their own, and {@link #update}
 * picks up what they produced. A tool lives while the mode it switched to has copy-tool
 * commands. Leaving to any other mode, such as with Esc, drops it.
 */
public final class CopyTools implements ModeListener {

    private static final Logger logger = LoggerFactory.getLogger(CopyTools.class);

    /** Left in the indicator text and replaced with the menu when it is drawn. */
    public static final String MENU_PLACEHOLDER = "[[copy-menu]]";

    /** The menu leaves the screen before anything is read from under it. */
    private static final double MENU_GONE_SECONDS = 0.06;
    private static final double SCREEN_CLEAR_SECONDS = 0.12;
    private static final long READ_TEXT_TIMEOUT_MILLIS = 3000;
    private static final double CLICKS_TIMEOUT_SECONDS = 1.5;
    /** The program that was asked to copy is still filling the clipboard just after it changes. */
    private static final double CLIPBOARD_SETTLE_SECONDS = 0.08;
    private static final int MAX_TEXT_CHARACTERS = 20_000;
    private static final long LONG_CAPTURE_INTERVAL_MILLIS = 40;
    private static final long LONG_PREVIEW_INTERVAL_MILLIS = 120;
    /** The frozen screen leaves the screen before the region is read live. */
    private static final long LONG_FIRST_CAPTURE_MILLIS = 180;

    /** What this needs done by the rest of the program. */
    public interface Output {

        /** Copy the word, or everything, at the pointer. */
        void copyText(Command.CopyText.Kind kind);

        /** Select the line at the pointer with clicks and send the copy chord, saying nothing. */
        void selectLineAndCopy();

        void showCopied();

        /** Go back to the mode the menu was opened from. */
        void leave();
    }

    /** The mouse pointer. A corner of the region is moved by moving it. */
    public interface Pointer {

        int x();

        int y();

        void moveTo(int x, int y);

        /** Still on its way to where {@link #moveTo} sent it. */
        boolean jumping();

        /** Stop the movement the keys started. */
        void halt();
    }

    private enum Tool { NONE, TEXT, SCREENSHOT, LONG_SCREENSHOT }

    private enum Stage {
        /** Waiting for the menu to leave the screen. */
        WAIT,
        // The word picker.
        READ, SAVE_CLIPBOARD, CLICK, SETTLE, READ_CLIPBOARD, READY, EMPTY,
        // The screenshots. LOCKED is a long screenshot whose region was just fixed.
        CAPTURE, SELECT, LOCKED, SCROLL
    }

    private final Pointer pointer;
    private final ScreenManager screenManager;
    private final ScreenTools screenTools;
    private final UiAutomation uiAutomation;
    private final Output output;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "copy-tools");
        thread.setDaemon(true);
        return thread;
    });

    private String modeName;
    private AppMenu menu = AppMenu.empty("复制");
    private List<RunCopyItem> menuItems = List.of();
    private String menuMode;

    private Command.CopyText.Kind pendingCopy;
    private double pendingCopyIn;
    /** A copy that is being written to the clipboard. */
    private Future<Boolean> copying;

    private Tool tool = Tool.NONE;
    private Stage stage = Stage.WAIT;
    private double wait;
    /** The keys of the mode the tool is in. */
    private List<Key> toolKeys = List.of();

    // The word picker.
    private Future<TextPicker> pickerTask;
    private Future<String> clipboardTask;
    private TextPicker picker;
    private boolean clipboardTaken;
    private String clipboardBefore;
    private int clipboardSequence;

    // The screenshots.
    private int mouseXBefore;
    private int mouseYBefore;
    private Screen screen;
    private Future<int[]> captureTask;
    private int[] shot;
    private ScreenshotPainter.Selection selection;
    private ScreenTools.Surface selectionSurface;
    private int firstX, firstY, secondX, secondY;
    private int activeCorner = ScreenshotPainter.NO_CORNER;
    private LongCapture longCapture;
    private Future<Boolean> longTask;
    private ScreenTools.Surface outlineSurface;
    private ScreenTools.Surface previewSurface;
    private final Map<String, Sprite> textSprites = new HashMap<>();

    public CopyTools(Pointer pointer, ScreenManager screenManager,
                     ScreenTools screenTools, UiAutomation uiAutomation, Output output) {
        this.pointer = pointer;
        this.screenManager = screenManager;
        this.screenTools = screenTools;
        this.uiAutomation = uiAutomation;
        this.output = output;
    }

    @Override
    public void modeChanged(Mode newMode) {
        String name = newMode.name();
        if (name.equals(modeName))
            return;
        modeName = name;
        List<RunCopyItem> items = new ArrayList<>();
        List<Key> keys = List.of();
        boolean toolMode = false;
        for (List<Command> commands : newMode.comboMap().commandsByCombo().values())
            for (Command command : commands) {
                if (command instanceof RunCopyItem item && !items.contains(item))
                    items.add(item);
                else if (command instanceof Command.CopyToolKey toolKey) {
                    keys = toolKey.keys();
                    toolMode = true;
                }
                else if (command instanceof Command.CopyToolConfirm ||
                         command instanceof Command.CopyToolUndo)
                    toolMode = true;
            }
        if (!items.isEmpty() && !name.equals(menuMode))
            openMenu(items);
        menuMode = items.isEmpty() ? null : name;
        if (toolMode)
            toolKeys = keys;
        else if (tool != Tool.NONE)
            cancel();
    }

    @Override
    public void modeTimedOut() {
    }

    // The menu.

    private void openMenu(List<RunCopyItem> items) {
        items.sort(Comparator.comparing(RunCopyItem::item));
        menuItems = List.copyOf(items);
        menu = AppMenu.withTags("复制",
                menuItems.stream().map(item -> item.item().label()).toList(),
                menuItems.stream().map(RunCopyItem::tagKey).toList());
    }

    /** Whether the menu runs this key itself. */
    public boolean hasMenuTag(Key key) {
        return menu.rowOf(key) >= 0;
    }

    public void moveMenu(int delta) {
        menu = menu.move(delta);
    }

    /** The highlighted row, or null when the menu has none. */
    public RunCopyItem selectedMenuItem() {
        return menuItems.isEmpty() ? null : menuItems.get(menu.selected());
    }

    public String menuHtml() {
        return menu.html();
    }

    /** Runs a row of the menu. The caller then switches to the row's mode. */
    public void run(CopyItem item) {
        if (tool != Tool.NONE)
            cancel();
        for (int row = 0; row < menuItems.size(); row++)
            if (menuItems.get(row).item() == item)
                menu = menu.select(row);
        logger.info("Copy menu: " + item.propertyName());
        switch (item) {
            case WORD -> startCopy(Command.CopyText.Kind.WORD);
            case ALL -> startCopy(Command.CopyText.Kind.ALL);
            case TEXT -> start(Tool.TEXT, MENU_GONE_SECONDS);
            case SCREENSHOT -> start(Tool.SCREENSHOT, SCREEN_CLEAR_SECONDS);
            case LONG_SCREENSHOT -> start(Tool.LONG_SCREENSHOT, SCREEN_CLEAR_SECONDS);
        }
    }

    private void startCopy(Command.CopyText.Kind kind) {
        pendingCopy = kind;
        pendingCopyIn = MENU_GONE_SECONDS;
    }

    private void start(Tool tool, double wait) {
        this.tool = tool;
        this.stage = Stage.WAIT;
        this.wait = wait;
        picker = null;
        pickerTask = null;
        clipboardTask = null;
        clipboardTaken = false;
        clipboardBefore = null;
        captureTask = null;
        activeCorner = ScreenshotPainter.NO_CORNER;
        if (pointer != null) {
            mouseXBefore = pointer.x();
            mouseYBefore = pointer.y();
        }
    }

    /** Whether a tool is open. */
    public boolean active() {
        return tool != Tool.NONE;
    }

    // Commands of the open tool.

    public void key(Key key) {
        switch (tool) {
            case TEXT -> {
                if (stage == Stage.READY && picker != null)
                    picker.type(key);
            }
            case SCREENSHOT, LONG_SCREENSHOT -> {
                int corner = toolKeys.indexOf(key);
                if (stage == Stage.LOCKED || stage == Stage.SCROLL ||
                    corner != ScreenshotPainter.FIRST_CORNER &&
                    corner != ScreenshotPainter.SECOND_CORNER)
                    return;
                activeCorner = corner;
                if (stage == Stage.SELECT)
                    movePointerToActiveCorner();
            }
            case NONE -> {
            }
        }
    }

    public void undo() {
        if (tool == Tool.TEXT && stage == Stage.READY && picker != null)
            picker.undo();
    }

    /**
     * Finishes the step the tool is at. True when it did, so the caller switches mode.
     * False while the tool is still getting ready.
     */
    public boolean confirm() {
        switch (tool) {
            case TEXT -> {
                if (stage == Stage.READY && picker != null) {
                    String text = picker.selectedText();
                    copying = worker.submit(() -> screenTools.copyText(text));
                    // What is on the clipboard now is what was asked for.
                    clipboardTaken = false;
                    close();
                    return true;
                }
                if (stage == Stage.EMPTY) {
                    cancel();
                    return true;
                }
                return false;
            }
            case SCREENSHOT -> {
                if (stage != Stage.SELECT)
                    return false;
                Rectangle region = selection.region();
                int[] pixels = shot;
                int width = selection.width();
                copying = worker.submit(() -> screenTools.copyImage(
                        crop(pixels, width, region), region.width(), region.height()));
                close();
                return true;
            }
            case LONG_SCREENSHOT -> {
                if (stage == Stage.SELECT) {
                    // The windows change on the next update, not on the keyboard's thread.
                    stage = Stage.LOCKED;
                    return true;
                }
                if (stage == Stage.SCROLL) {
                    longCapture.copy = true;
                    longCapture.stop = true;
                    copying = longTask;
                    close();
                    return true;
                }
                return false;
            }
            case NONE -> {
                return false;
            }
        }
        return false;
    }

    /** Drops the open tool. Nothing is copied, and the clipboard gets back the text it had. */
    public void cancel() {
        if (tool == Tool.NONE)
            return;
        logger.info("Copy tool cancelled");
        if (clipboardTaken && clipboardBefore != null) {
            String text = clipboardBefore;
            worker.submit(() -> screenTools.copyText(text));
        }
        close();
    }

    /** The configuration that made this is gone: nothing more will be asked of it. */
    public void shutdown() {
        pendingCopy = null;
        cancel();
        worker.shutdown();
    }

    /** Takes the tool off the screen and puts the pointer back. */
    private void close() {
        boolean screenshot = tool == Tool.SCREENSHOT || tool == Tool.LONG_SCREENSHOT;
        boolean pointerMoved = screenshot &&
                               (stage == Stage.SELECT || stage == Stage.LOCKED || stage == Stage.SCROLL);
        tool = Tool.NONE;
        stage = Stage.WAIT;
        picker = null;
        pickerTask = null;
        clipboardTask = null;
        clipboardTaken = false;
        clipboardBefore = null;
        captureTask = null;
        shot = null;
        selection = null;
        activeCorner = ScreenshotPainter.NO_CORNER;
        if (longCapture != null)
            longCapture.stop = true;
        longCapture = null;
        longTask = null;
        selectionSurface = closed(selectionSurface);
        outlineSurface = closed(outlineSurface);
        previewSurface = closed(previewSurface);
        if (pointerMoved && pointer != null)
            pointer.moveTo(mouseXBefore, mouseYBefore);
    }

    private static ScreenTools.Surface closed(ScreenTools.Surface surface) {
        if (surface != null) {
            try {
                surface.close();
            }
            catch (RuntimeException e) {
                logger.warn("Unable to close a screenshot window", e);
            }
        }
        return null;
    }

    /** What the picker's panel shows. Empty when the picker is not open. */
    public String textPickerHtml() {
        if (tool != Tool.TEXT)
            return "";
        if (stage == Stage.READY && picker != null)
            return picker.html();
        if (stage == Stage.EMPTY)
            return TextPicker.emptyHtml();
        return TextPicker.loadingHtml();
    }

    public void update(double delta) {
        if (pendingCopy != null) {
            pendingCopyIn -= delta;
            if (pendingCopyIn <= 0) {
                Command.CopyText.Kind kind = pendingCopy;
                pendingCopy = null;
                output.copyText(kind);
            }
        }
        if (copying != null && copying.isDone()) {
            Boolean copied = result(copying);
            copying = null;
            if (Boolean.TRUE.equals(copied))
                output.showCopied();
            else
                logger.warn("Nothing was put on the clipboard");
        }
        try {
            switch (tool) {
                case TEXT -> updateText(delta);
                case SCREENSHOT, LONG_SCREENSHOT -> updateScreenshot(delta);
                case NONE -> {
                }
            }
        }
        catch (RuntimeException e) {
            logger.error("The copy tool failed", e);
            fail();
        }
    }

    /** The tool cannot go on: drop it and return to the mode the menu was opened from. */
    private void fail() {
        cancel();
        output.leave();
    }

    private static <T> T result(Future<T> future) {
        try {
            return future.get();
        }
        catch (Exception e) {
            logger.warn("A copy tool task failed", e);
            return null;
        }
    }

    // The word picker.

    private void updateText(double delta) {
        switch (stage) {
            case WAIT -> {
                wait -= delta;
                if (wait > 0)
                    return;
                Future<String> read = uiAutomation.startReadText();
                List<Key> letters = toolKeys;
                pickerTask = worker.submit(() -> picker(readWithin(read), letters));
                stage = Stage.READ;
            }
            case READ -> {
                if (!pickerTask.isDone())
                    return;
                picker = result(pickerTask);
                pickerTask = null;
                if (picker != null && !picker.isEmpty())
                    stage = Stage.READY;
                else if (screenTools.supported()) {
                    // No text to read there: select it with clicks and copy it, the way
                    // the program itself would. The clipboard is given back on Esc.
                    picker = null;
                    clipboardTask = worker.submit(screenTools::clipboardText);
                    stage = Stage.SAVE_CLIPBOARD;
                }
                else
                    stage = Stage.EMPTY;
            }
            case SAVE_CLIPBOARD -> {
                if (!clipboardTask.isDone())
                    return;
                clipboardBefore = result(clipboardTask);
                clipboardTask = null;
                clipboardTaken = true;
                clipboardSequence = screenTools.clipboardSequence();
                output.selectLineAndCopy();
                wait = CLICKS_TIMEOUT_SECONDS;
                stage = Stage.CLICK;
            }
            case CLICK -> {
                wait -= delta;
                if (screenTools.clipboardSequence() != clipboardSequence) {
                    wait = CLIPBOARD_SETTLE_SECONDS;
                    stage = Stage.SETTLE;
                }
                else if (wait <= 0) {
                    clipboardTaken = false;
                    stage = Stage.EMPTY;
                }
            }
            case SETTLE -> {
                wait -= delta;
                if (wait > 0)
                    return;
                List<Key> letters = toolKeys;
                pickerTask = worker.submit(() -> picker(screenTools.clipboardText(), letters));
                stage = Stage.READ_CLIPBOARD;
            }
            case READ_CLIPBOARD -> {
                if (!pickerTask.isDone())
                    return;
                picker = result(pickerTask);
                pickerTask = null;
                stage = picker != null && !picker.isEmpty() ? Stage.READY : Stage.EMPTY;
            }
            default -> {
            }
        }
    }

    private static String readWithin(Future<String> read) {
        try {
            return read.get(READ_TEXT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }
        catch (Exception e) {
            logger.warn("Reading the text at the pointer failed", e);
            // Not interrupted: the thread that reads is shared, and is inside the other program.
            read.cancel(false);
            return null;
        }
    }

    /** Null when there is no text to pick from. Cutting the text reads the word list. */
    static TextPicker picker(String text, List<Key> letters) {
        if (text == null)
            return null;
        // A control stands in the text as U+FFFC.
        String cleaned = text.replace('\uFFFC', ' ').replace("\r\n", "\n").replace('\r', '\n')
                             .strip();
        if (cleaned.isEmpty())
            return null;
        if (cleaned.length() > MAX_TEXT_CHARACTERS)
            cleaned = cleaned.substring(0, MAX_TEXT_CHARACTERS);
        return TextPicker.of(cleaned, new TextTokenizer(ChineseWords.shared()), letters);
    }

    // The screenshots.

    private void updateScreenshot(double delta) {
        switch (stage) {
            case WAIT -> {
                wait -= delta;
                if (wait > 0)
                    return;
                if (!screenTools.supported()) {
                    logger.warn("Screenshots are not available on this platform");
                    fail();
                    return;
                }
                // The key that ran the row can also be one that moves the pointer (l), and
                // is still down: no corner is chosen yet, so it has nothing to move.
                if (activeCorner == ScreenshotPainter.NO_CORNER)
                    pointer.halt();
                screen = screenManager.nearestScreenContaining(mouseXBefore, mouseYBefore);
                Rectangle rectangle = screen.rectangle();
                captureTask = worker.submit(() -> screenTools.capture(rectangle));
                stage = Stage.CAPTURE;
            }
            case CAPTURE -> {
                if (!captureTask.isDone())
                    return;
                shot = result(captureTask);
                captureTask = null;
                if (shot == null) {
                    logger.warn("The screen could not be read");
                    fail();
                    return;
                }
                startSelecting();
            }
            case SELECT -> {
                followPointer();
                showSelection();
            }
            case LOCKED -> startScrolling();
            case SCROLL -> {
                Preview preview = longCapture.preview.getAndSet(null);
                if (preview == null)
                    return;
                if (previewSurface == null)
                    previewSurface = screenTools.createSurface(true);
                if (previewSurface != null)
                    previewSurface.show(preview.rectangle(), preview.pixels(), null);
            }
            default -> {
            }
        }
    }

    private void startSelecting() {
        Rectangle rectangle = screen.rectangle();
        double scale = screen.scale();
        Sprite[] cornerTags = new Sprite[2];
        for (int corner = 0; corner < 2; corner++)
            if (corner < toolKeys.size())
                cornerTags[corner] = text(toolKeys.get(corner).hintLabel(),
                        (int) Math.round(16 * scale), 0xFFFFFF);
        String first = toolKeys.size() > 0 ? toolKeys.get(0).hintLabel() : "";
        String second = toolKeys.size() > 1 ? toolKeys.get(1).hintLabel() : "";
        Sprite hint = text(first + " 左上角   " + second + " 右下角   H J K L 移动   Shift 微调   " +
                           (tool == Tool.LONG_SCREENSHOT ? "空格 / 回车 确定范围" : "空格 / 回车 复制") +
                           "   Esc 取消", (int) Math.round(15 * scale), ScreenshotPainter.LIGHT);
        Sprite[] digits = new Sprite[11];
        boolean allDigits = true;
        for (int digit = 0; digit < digits.length; digit++) {
            digits[digit] = text(digit < 10 ? String.valueOf(digit) : " × ",
                    (int) Math.round(13 * scale), ScreenshotPainter.LIGHT);
            allDigits &= digits[digit] != null;
        }
        if (!allDigits)
            digits = null;
        selection = new ScreenshotPainter.Selection(shot, rectangle.width(), rectangle.height(),
                scale, cornerTags[0] == null || cornerTags[1] == null ? null : cornerTags,
                hint, digits);
        firstX = 0;
        firstY = 0;
        secondX = rectangle.width();
        secondY = rectangle.height();
        selectionSurface = screenTools.createSurface(false);
        if (selectionSurface == null)
            throw new IllegalStateException("No window to show the screenshot in");
        stage = Stage.SELECT;
        if (activeCorner != ScreenshotPainter.NO_CORNER)
            movePointerToActiveCorner();
        showSelection();
    }

    private Sprite text(String text, int pixelSize, int rgb) {
        return textSprites.computeIfAbsent(text + '\n' + pixelSize + '\n' + rgb,
                key -> screenTools.renderText(text, Math.max(8, pixelSize), rgb));
    }

    /** The pointer is hidden, and is where the chosen corner is: moving it moves the corner. */
    private void movePointerToActiveCorner() {
        Rectangle rectangle = screen.rectangle();
        boolean first = activeCorner == ScreenshotPainter.FIRST_CORNER;
        int x = Math.clamp(first ? firstX : secondX - 1, 0, rectangle.width() - 1);
        int y = Math.clamp(first ? firstY : secondY - 1, 0, rectangle.height() - 1);
        pointer.halt();
        pointer.moveTo(rectangle.x() + x, rectangle.y() + y);
    }

    private void followPointer() {
        if (activeCorner == ScreenshotPainter.NO_CORNER || pointer.jumping())
            return;
        Rectangle rectangle = screen.rectangle();
        int x = Math.clamp(pointer.x() - rectangle.x(), 0, rectangle.width() - 1);
        int y = Math.clamp(pointer.y() - rectangle.y(), 0, rectangle.height() - 1);
        if (activeCorner == ScreenshotPainter.FIRST_CORNER) {
            firstX = x;
            firstY = y;
        }
        else {
            // The pixel under the pointer is the last one of the region.
            secondX = x + 1;
            secondY = y + 1;
        }
    }

    private void showSelection() {
        Rectangle rectangle = screen.rectangle();
        for (Rectangle dirty : selection.paint(firstX, firstY, secondX, secondY, activeCorner))
            selectionSurface.show(rectangle, selection.frame(),
                    new Rectangle(rectangle.x() + dirty.x(), rectangle.y() + dirty.y(),
                            dirty.width(), dirty.height()));
    }

    static int[] crop(int[] pixels, int width, Rectangle region) {
        int[] cropped = new int[region.width() * region.height()];
        for (int y = 0; y < region.height(); y++)
            System.arraycopy(pixels, (region.y() + y) * width + region.x(), cropped,
                    y * region.width(), region.width());
        return cropped;
    }

    /** The region is fixed. From here the region is read live while it is scrolled. */
    private void startScrolling() {
        Rectangle screenRectangle = screen.rectangle();
        Rectangle inside = selection.region();
        Rectangle region = new Rectangle(screenRectangle.x() + inside.x(),
                screenRectangle.y() + inside.y(), inside.width(), inside.height());
        double scale = screen.scale();
        selectionSurface = closed(selectionSurface);
        selection = null;
        shot = null;
        activeCorner = ScreenshotPainter.NO_CORNER;
        // The outline is outside the region, on the sides where the screen has room.
        int thickness = Math.max(2, (int) Math.round(2 * scale));
        Rectangle outer = new Rectangle(region.x() - thickness, region.y() - thickness,
                region.width() + 2 * thickness, region.height() + 2 * thickness)
                .intersection(screenRectangle);
        outlineSurface = screenTools.createSurface(true);
        if (outlineSurface != null)
            outlineSurface.show(outer, ScreenshotPainter.outline(outer.width(), outer.height(),
                    region.x() - outer.x(), region.y() - outer.y(),
                    outer.x() + outer.width() - region.x() - region.width(),
                    outer.y() + outer.height() - region.y() - region.height()), null);
        Sprite hint = text("E / D 滚动   空格 / 回车 完成   Esc 取消",
                (int) Math.round(13 * scale), ScreenshotPainter.LIGHT);
        pointer.halt();
        pointer.moveTo(region.x() + region.width() / 2, region.y() + region.height() / 2);
        longCapture = new LongCapture(screenTools, region, screenRectangle, scale, hint);
        longTask = worker.submit(longCapture);
        stage = Stage.SCROLL;
    }

    /** The preview panel, where it goes on the screen. */
    private record Preview(Rectangle rectangle, int[] pixels) {
    }

    /**
     * Reads the region over and over while it is scrolled and joins what it reads.
     * Runs on the worker thread until it is told to stop, and then copies the long
     * picture if that was asked.
     */
    private static final class LongCapture implements Callable<Boolean> {

        private final ScreenTools screenTools;
        private final Rectangle region;
        private final Rectangle screen;
        private final double scale;
        private final Sprite hint;
        final AtomicReference<Preview> preview = new AtomicReference<>();
        volatile boolean stop;
        volatile boolean copy;

        LongCapture(ScreenTools screenTools, Rectangle region, Rectangle screen, double scale,
                    Sprite hint) {
            this.screenTools = screenTools;
            this.region = region;
            this.screen = screen;
            this.scale = scale;
            this.hint = hint;
        }

        @Override
        public Boolean call() {
            try {
                ImageStitcher stitcher = new ImageStitcher(region.width(), region.height());
                boolean any = false;
                boolean previewStale = false;
                boolean lost = false;
                long previewAt = 0;
                int[] frame = new int[region.width() * region.height()];
                pause(LONG_FIRST_CAPTURE_MILLIS);
                while (!stop) {
                    long before = System.nanoTime();
                    if (screenTools.capture(region, frame)) {
                        ImageStitcher.Result result = stitcher.add(frame);
                        any = true;
                        boolean nowLost = stitcher.unplaced() >= 8 || stitcher.full();
                        if (result == ImageStitcher.Result.FIRST ||
                            result == ImageStitcher.Result.MOVED || nowLost != lost)
                            previewStale = true;
                        lost = nowLost;
                    }
                    long now = System.nanoTime();
                    if (previewStale && now - previewAt >= LONG_PREVIEW_INTERVAL_MILLIS * 1_000_000) {
                        preview.set(preview(stitcher, lost));
                        previewStale = false;
                        previewAt = now;
                    }
                    pause(Math.max(5, LONG_CAPTURE_INTERVAL_MILLIS -
                                      (System.nanoTime() - before) / 1_000_000));
                }
                if (!copy || !any)
                    return false;
                return screenTools.copyImage(stitcher.image(), stitcher.width(), stitcher.height());
            }
            catch (Throwable e) {
                logger.error("The long screenshot failed", e);
                return false;
            }
        }

        private void pause(long millis) {
            try {
                Thread.sleep(millis);
            }
            catch (InterruptedException e) {
                stop = true;
            }
        }

        /** In a corner of the screen, the bottom left one unless the region is there. */
        private Preview preview(ImageStitcher stitcher, boolean lost) {
            int maxWidth = Math.max(160, screen.width() * 22 / 100);
            int maxHeight = Math.max(160, screen.height() * 60 / 100);
            Sprite panel = ScreenshotPainter.previewPanel(stitcher, maxWidth, maxHeight, scale,
                    hint, lost);
            int margin = (int) Math.round(16 * scale);
            int bottomMargin = (int) Math.round(64 * scale);
            int left = screen.x() + margin;
            int right = screen.x() + screen.width() - margin - panel.width();
            int top = screen.y() + margin;
            int bottom = screen.y() + screen.height() - bottomMargin - panel.height();
            int[][] corners = {{left, bottom}, {right, bottom}, {left, top}, {right, top}};
            Rectangle rectangle = null;
            for (int[] corner : corners) {
                Rectangle candidate =
                        new Rectangle(corner[0], corner[1], panel.width(), panel.height());
                if (rectangle == null)
                    rectangle = candidate;
                if (candidate.intersection(region).isEmpty()) {
                    rectangle = candidate;
                    break;
                }
            }
            return new Preview(rectangle, panel.pixels());
        }
    }
}
