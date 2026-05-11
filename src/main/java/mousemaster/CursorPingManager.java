package mousemaster;

import com.sun.jna.platform.win32.WinDef;

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
            && "normal-mode".equals(newMode.name())) {
            WinDef.POINT p = WindowsMouse.tryFindMousePosition();
            if (p != null)
                WindowsOverlay.startCursorPing(p.x, p.y);
        }
        previousModeName = newMode.name();
    }

    @Override
    public void modeTimedOut() {
        // Ignored.
    }
}
