package mousemaster;

import mousemaster.platform.windows.WindowsCursorPing;

/**
 * When leaving idle for normal mode, plays a short double ripple at the cursor
 * so the pointer position is easy to spot.
 */
public class CursorPingManager implements ModeListener {

    private String previousModeName;

    @Override
    public void modeChanged(Mode newMode) {
        if (previousModeName != null
            && previousModeName.equals(Mode.IDLE_MODE_NAME)
            && "normal-mode".equals(newMode.name()))
            WindowsCursorPing.startAtCursor();
        previousModeName = newMode.name();
    }

    @Override
    public void modeTimedOut() {
        // Ignored.
    }
}
