package mousemaster.platform.windows;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WindowIconFileTest {

    @Test
    void whiteAndBlackDrawsRecoverStraightAlpha() {
        byte[] onWhite = new byte[12];
        byte[] onBlack = new byte[12];
        // Opaque red.
        onWhite[2] = onBlack[2] = (byte) 255;
        // Clear.
        onWhite[4] = onWhite[5] = onWhite[6] = (byte) 255;
        // Red at about half opacity: on black the red is halved, on white the other channels lift.
        onBlack[10] = (byte) 128;
        onWhite[8] = onWhite[9] = (byte) 127;
        onWhite[10] = (byte) 255;

        int[] argb = WindowIconFile.straightArgb(onWhite, onBlack);
        assertEquals(0xFFFF0000, argb[0]);
        assertEquals(0, argb[1]);
        assertEquals(128, argb[2] >>> 24);
        assertEquals(255, (argb[2] >> 16) & 0xFF);
        assertEquals(0, (argb[2] >> 8) & 0xFF);
        assertEquals(0, argb[2] & 0xFF);
    }

}
