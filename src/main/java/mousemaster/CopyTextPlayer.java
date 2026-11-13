package mousemaster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

/**
 * Copies the text under the pointer. A control that exposes its text is read
 * directly. Anywhere that does not, this plays the clicks that select it, then
 * asks for the keyboard chord that copies it. Clicks are spaced so Windows treats
 * them as a double-click or triple-click, and the pointer is held still for that
 * whole time.
 */
public final class CopyTextPlayer {

    private static final Logger logger = LoggerFactory.getLogger(CopyTextPlayer.class);

    /** How long the button stays down. */
    public static final double HOLD_SECONDS = 0.04;
    /** Gap between two clicks. Together with the hold this stays under the double-click time. */
    public static final double BETWEEN_CLICKS_SECONDS = 0.05;
    /** After the last click, let the target apply the selection before the next keys. */
    public static final double BEFORE_KEYS_SECONDS = 0.10;

    public interface Output {
        void releaseLeft();

        void haltMovement();

        void pressLeft();

        void selectAll();

        /**
         * Read the text under the pointer and put it on the clipboard.
         * The future is false when there is nothing there to read, so the clicks take over.
         * It must not do the read on this thread: this thread owns the keyboard hook.
         */
        Future<Boolean> startCopy(Command.CopyText.Kind kind);

        /** Ctrl+C, or Command+C. The text was selected with clicks. */
        void copy();

        /** The text is already on the clipboard. Show the same confirmation as {@link #copy()}. */
        void showCopied();

        /** The chord of {@link #copy()} with no confirmation: the caller reads the clipboard next. */
        default void copyQuietly() {
            copy();
        }
    }

    private enum Action { PRESS, RELEASE, SELECT_ALL, COPY, COPY_QUIETLY }

    private final Output output;
    private final List<Action> actions = new ArrayList<>();
    private Command.CopyText.Kind pending;
    private Command.CopyText.Kind readingKind;
    private Future<Boolean> copyFuture;
    private int next;
    private double remaining;

    public CopyTextPlayer(Output output) {
        this.output = output;
    }

    public void start(Command.CopyText.Kind kind) {
        // A previous sequence may still be holding the button.
        output.releaseLeft();
        output.haltMovement();
        // Read on the next update, and not on this thread. This runs from the
        // keyboard hook, and a text query here stalls or tears down that hook.
        pending = kind;
        copyFuture = null;
        actions.clear();
        next = 0;
        remaining = 0;
        logger.debug("Copy text at pointer: " + kind);
    }

    /**
     * Selects with clicks straight away, without asking the control for its text first,
     * and sends the copy chord with no confirmation. For a caller that already found
     * nothing to read and wants what the program itself copies.
     */
    public void startClicks(Command.CopyText.Kind kind) {
        output.releaseLeft();
        output.haltMovement();
        pending = null;
        copyFuture = null;
        actions.clear();
        scheduleClicks(kind, Action.COPY_QUIETLY);
        logger.debug("Select with clicks at pointer: " + kind);
    }

    public void update(double delta) {
        if (pending != null) {
            readingKind = pending;
            pending = null;
            copyFuture = output.startCopy(readingKind);
        }
        if (copyFuture != null && copyFuture.isDone()) {
            Future<Boolean> done = copyFuture;
            Command.CopyText.Kind kind = readingKind;
            copyFuture = null;
            if (copied(done)) {
                output.showCopied();
                return;
            }
            scheduleClicks(kind, Action.COPY);
        }
        else if (copyFuture != null)
            return;
        if (next >= actions.size())
            return;
        remaining -= delta;
        while (next < actions.size() && remaining <= 0) {
            Action action = actions.get(next++);
            switch (action) {
                case PRESS -> output.pressLeft();
                case RELEASE -> output.releaseLeft();
                case SELECT_ALL -> output.selectAll();
                case COPY -> output.copy();
                case COPY_QUIETLY -> output.copyQuietly();
            }
            if (next >= actions.size())
                return;
            remaining += delayUntilNext(action, actions.get(next));
        }
    }

    private boolean copied(Future<Boolean> done) {
        try {
            return Boolean.TRUE.equals(done.get());
        }
        catch (Exception e) {
            logger.warn("Reading the text under the pointer failed, selecting it with clicks", e);
            return false;
        }
    }

    private void scheduleClicks(Command.CopyText.Kind kind, Action copy) {
        int clicks = switch (kind) {
            case WORD -> 2;
            case LINE -> 3;
            case ALL -> 1;
        };
        for (int click = 0; click < clicks; click++) {
            actions.add(Action.PRESS);
            actions.add(Action.RELEASE);
        }
        if (kind == Command.CopyText.Kind.ALL)
            actions.add(Action.SELECT_ALL);
        actions.add(copy);
        next = 0;
        remaining = 0;
    }

    private static double delayUntilNext(Action done, Action next) {
        return switch (done) {
            case PRESS -> HOLD_SECONDS;
            case RELEASE -> next == Action.PRESS ? BETWEEN_CLICKS_SECONDS : BEFORE_KEYS_SECONDS;
            case SELECT_ALL -> BEFORE_KEYS_SECONDS;
            case COPY, COPY_QUIETLY -> 0;
        };
    }
}
