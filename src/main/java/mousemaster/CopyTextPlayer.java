package mousemaster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Plays the mouse clicks that select text under the pointer, then asks for the
 * keyboard chord that copies it. Clicks are spaced so Windows treats them as a
 * double-click or triple-click, and the pointer is held still for that whole time.
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

        void copy();
    }

    private enum Action { PRESS, RELEASE, SELECT_ALL, COPY }

    private final Output output;
    private final List<Action> actions = new ArrayList<>();
    private int next;
    private double remaining;

    public CopyTextPlayer(Output output) {
        this.output = output;
    }

    public void start(Command.CopyText.Kind kind) {
        // A previous sequence may still be holding the button.
        output.releaseLeft();
        output.haltMovement();
        actions.clear();
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
        actions.add(Action.COPY);
        next = 0;
        remaining = 0;
        logger.debug("Copy text at pointer: " + kind);
    }

    public void update(double delta) {
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
            }
            if (next >= actions.size())
                return;
            remaining += delayUntilNext(action, actions.get(next));
        }
    }

    private static double delayUntilNext(Action done, Action next) {
        return switch (done) {
            case PRESS -> HOLD_SECONDS;
            case RELEASE -> next == Action.PRESS ? BETWEEN_CLICKS_SECONDS : BEFORE_KEYS_SECONDS;
            case SELECT_ALL -> BEFORE_KEYS_SECONDS;
            case COPY -> 0;
        };
    }
}
