package mousemaster.platform.windows;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.PointerByReference;

/**
 * Draws a Windows icon into straight-alpha pixels. Drawing it on white and on black
 * recovers the alpha, which a 32-bit bitmap often leaves blank.
 */
final class WindowIconFile {

    private WindowIconFile() {
    }

    /** Null when the icon cannot be drawn or every pixel is clear. */
    static int[] pixels(WinDef.HICON icon, int size) {
        if (icon == null || icon.getPointer() == null || size <= 0)
            return null;
        WinGDI.BITMAPINFO bitmapInfo = new WinGDI.BITMAPINFO();
        bitmapInfo.bmiHeader.biSize = bitmapInfo.bmiHeader.size();
        bitmapInfo.bmiHeader.biWidth = size;
        bitmapInfo.bmiHeader.biHeight = -size;
        bitmapInfo.bmiHeader.biPlanes = 1;
        bitmapInfo.bmiHeader.biBitCount = 32;
        bitmapInfo.bmiHeader.biCompression = WinGDI.BI_RGB;
        WinDef.HDC dc = GDI32.INSTANCE.CreateCompatibleDC(null);
        if (dc == null)
            return null;
        PointerByReference bitsRef = new PointerByReference();
        WinDef.HBITMAP dib = ExtendedGDI32.INSTANCE.CreateDIBSection(
                dc, bitmapInfo, WinGDI.DIB_RGB_COLORS, bitsRef, null, 0);
        if (dib == null) {
            GDI32.INSTANCE.DeleteDC(dc);
            return null;
        }
        WinNT.HANDLE previous = GDI32.INSTANCE.SelectObject(dc, dib);
        try {
            Pointer bits = bitsRef.getValue();
            if (bits == null)
                return null;
            int byteCount = size * size * 4;
            byte[] onWhite = paint(dc, icon, bits, byteCount, size, (byte) 0xFF);
            byte[] onBlack = paint(dc, icon, bits, byteCount, size, (byte) 0x00);
            int[] argb = straightArgb(onWhite, onBlack);
            return visible(argb) ? argb : null;
        }
        finally {
            if (previous != null)
                GDI32.INSTANCE.SelectObject(dc, previous);
            GDI32.INSTANCE.DeleteObject(dib);
            GDI32.INSTANCE.DeleteDC(dc);
        }
    }

    /** {@code onWhite} and {@code onBlack} are BGRA rows from the same icon. */
    static int[] straightArgb(byte[] onWhite, byte[] onBlack) {
        int[] argb = new int[onWhite.length / 4];
        for (int i = 0; i < argb.length; i++) {
            int o = i * 4;
            int wB = onWhite[o] & 0xFF, wG = onWhite[o + 1] & 0xFF, wR = onWhite[o + 2] & 0xFF;
            int bB = onBlack[o] & 0xFF, bG = onBlack[o + 1] & 0xFF, bR = onBlack[o + 2] & 0xFF;
            int a = Math.max(Math.max(alpha(wB, bB), alpha(wG, bG)), alpha(wR, bR));
            if (a == 0) {
                argb[i] = 0;
                continue;
            }
            int r = Math.min(255, bR * 255 / a);
            int g = Math.min(255, bG * 255 / a);
            int b = Math.min(255, bB * 255 / a);
            argb[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        return argb;
    }

    private static int alpha(int onWhite, int onBlack) {
        int alpha = 255 - (onWhite - onBlack);
        if (alpha < 0)
            return 0;
        return Math.min(alpha, 255);
    }

    private static boolean visible(int[] argb) {
        for (int pixel : argb)
            if ((pixel >>> 24) > 24)
                return true;
        return false;
    }

    private static byte[] paint(WinDef.HDC dc, WinDef.HICON icon, Pointer bits,
                                int byteCount, int size, byte fill) {
        bits.setMemory(0, byteCount, fill);
        ExtendedUser32.INSTANCE.DrawIconEx(dc, 0, 0, icon, size, size, 0, null,
                ExtendedUser32.DI_NORMAL);
        ExtendedGDI32.INSTANCE.GdiFlush();
        return bits.getByteArray(0, byteCount);
    }

}
