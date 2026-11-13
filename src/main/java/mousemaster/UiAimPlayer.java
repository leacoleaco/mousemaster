package mousemaster;

import mousemaster.platform.UiAutomation;
import mousemaster.platform.UiAutomation.UiElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

/**
 * Moves the pointer onto a control after a menu item asks for it.
 * The lookup runs off the thread that reads the keyboard: doing it there stalls
 * that thread and Windows then removes the hook. A click waits until a smooth
 * jump has arrived, then holds the button down briefly.
 */
public final class UiAimPlayer {

    private static final Logger logger = LoggerFactory.getLogger(UiAimPlayer.class);

    /** After keys that show a view, let that view appear before it is looked up. */
    private static final double AFTER_KEYS_SECONDS = 0.30;
    /** One more look, when the view was not there yet. */
    private static final double RETRY_SECONDS = 0.25;
    /** How long the button stays down. */
    private static final double HOLD_SECONDS = 0.04;

    private enum Phase { IDLE, SETTLE, QUERY, ARRIVE, HOLD }

    private final MouseManager mouseManager;
    private final Platform platform;
    private final BooleanSupplier macroBusy;

    private UiAim aim;
    private boolean afterKeys;
    private int attempts;
    private Phase phase = Phase.IDLE;
    private double remaining;
    private Future<UiAutomation.WindowControls> future;

    public UiAimPlayer(MouseManager mouseManager, Platform platform,
                       BooleanSupplier macroBusy) {
        this.mouseManager = mouseManager;
        this.platform = platform;
        this.macroBusy = macroBusy;
    }

    /** {@code afterKeys} waits for the item's keys to finish and for the view to appear. */
    public void start(UiAim aim, boolean afterKeys) {
        release();
        this.aim = aim;
        this.afterKeys = afterKeys;
        this.attempts = afterKeys ? 2 : 1;
        this.future = null;
        this.phase = Phase.SETTLE;
        this.remaining = afterKeys ? AFTER_KEYS_SECONDS : 0;
        logger.debug("Aim " + aim.kind().name().toLowerCase() + " " + aim.name());
    }

    public void update(double delta) {
        if (phase == Phase.IDLE)
            return;
        try {
            step(delta);
        }
        catch (RuntimeException e) {
            logger.warn("Aiming at " + aim.name() + " failed", e);
            release();
            phase = Phase.IDLE;
        }
    }

    private void step(double delta) {
        switch (phase) {
            case IDLE -> {
            }
            case SETTLE -> {
                if (macroBusy != null && macroBusy.getAsBoolean())
                    return;
                remaining -= delta;
                if (remaining > 0)
                    return;
                beginQuery();
            }
            case QUERY -> {
                if (future == null || !future.isDone())
                    return;
                Future<UiAutomation.WindowControls> done = future;
                future = null;
                UiElement element = found(done);
                if (element == null) {
                    attempts--;
                    if (attempts > 0) {
                        logger.debug("No " + aim.name() + " yet, looking again");
                        phase = Phase.SETTLE;
                        remaining = RETRY_SECONDS;
                        return;
                    }
                    logger.info("No control named " + aim.name() + " to " +
                                aim.kind().name().toLowerCase());
                    phase = Phase.IDLE;
                    return;
                }
                moveTo(element);
            }
            case ARRIVE -> {
                if (mouseManager != null && mouseManager.jumping())
                    return;
                if (mouseManager != null)
                    mouseManager.pressLeft();
                phase = Phase.HOLD;
                remaining = HOLD_SECONDS;
            }
            case HOLD -> {
                remaining -= delta;
                if (remaining > 0)
                    return;
                release();
                phase = Phase.IDLE;
            }
        }
    }

    private void beginQuery() {
        if (platform == null || platform.uiAutomation() == null) {
            logger.info("No control named " + aim.name() + " to " +
                        aim.kind().name().toLowerCase());
            phase = Phase.IDLE;
            return;
        }
        future = platform.uiAutomation().startFindActiveWindowControls();
        phase = Phase.QUERY;
    }

    private UiElement found(Future<UiAutomation.WindowControls> done) {
        UiAutomation.WindowControls controls;
        try {
            controls = done.get();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Looking up " + aim.name() + " failed", e);
            return null;
        }
        catch (Exception e) {
            logger.warn("Looking up " + aim.name() + " failed", e);
            return null;
        }
        if (controls == null)
            return null;
        return aim.locate(controls.window(), controls.elements());
    }

    private void moveTo(UiElement element) {
        if (mouseManager == null) {
            phase = Phase.IDLE;
            return;
        }
        Point center = element.rectangle().center();
        int x = (int) center.x();
        int y = (int) center.y();
        String label = element.name() == null ? aim.name() : element.name();
        logger.info((aim.kind() == UiAim.Kind.CLICK ? "Clicking " : "Moving the pointer to ") +
                    label + " at " + x + "," + y);
        mouseManager.haltMovement();
        mouseManager.moveTo(x, y);
        if (aim.kind() == UiAim.Kind.POINT) {
            phase = Phase.IDLE;
            return;
        }
        phase = Phase.ARRIVE;
        if (!mouseManager.jumping()) {
            mouseManager.pressLeft();
            phase = Phase.HOLD;
            remaining = HOLD_SECONDS;
        }
    }

    private void release() {
        if (mouseManager != null && phase == Phase.HOLD)
            mouseManager.releaseLeft();
    }

}
