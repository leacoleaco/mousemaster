package mousemaster;

import mousemaster.platform.windows.WindowsCursorPing;

/**
 * When leaving idle for normal mode, dims the screen and draws a white ring at
 * the cursor for one second so the pointer position is easy to spot.
 * Repeating the activation double-tap while already active is a separate command.
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
