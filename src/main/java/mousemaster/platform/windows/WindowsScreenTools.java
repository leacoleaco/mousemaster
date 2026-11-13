package mousemaster.platform.windows;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.PointerByReference;
import io.qt.core.QPointF;
import io.qt.gui.QColor;
import io.qt.gui.QFont;
import io.qt.gui.QFontMetricsF;
import io.qt.gui.QImage;
import io.qt.gui.QPainter;
import mousemaster.FontWeight;
import mousemaster.Rectangle;
import mousemaster.platform.ScreenTools;
import mousemaster.qt.QtHintFont;
import mousemaster.renderer.IndicatorRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The screen is read with GDI, which needs nothing set up and reads any part of any
 * monitor. The windows are layered ones, shown with UpdateLayeredWindow like the
 * indicator, and the pointer goes through them.
 * <p>
 * Reading the screen and the clipboard is done on the thread of the copy tools. The
 * windows and the text are made on the main thread, which is the one that pumps their
 * messages and the one Qt paints on.
 */
public final class WindowsScreenTools implements ScreenTools {

    private static final Logger logger = LoggerFactory.getLogger(WindowsScreenTools.class);

    private static final int SRCCOPY = 0x00CC0020;
    private static final int CF_DIB = 8;
    private static final int BITMAP_INFO_HEADER_BYTES = 40;
    private static final int CLIPBOARD_ATTEMPTS = 12;
    private static final long CLIPBOARD_RETRY_MILLIS = 25;
    private static final String FONT_NAME = "Microsoft YaHei UI";

    // The bitmap the screen is copied into, kept while the size stays the same.
    private WinDef.HDC captureDc;
    private WinNT.HANDLE capturePreviousBitmap;
    private WinDef.HBITMAP captureDib;
    private Pointer captureBits;
    private int captureWidth;
    private int captureHeight;

    @Override
    public boolean supported() {
        return true;
    }

    @Override
    public synchronized boolean capture(Rectangle rectangle, int[] pixels) {
        int width = rectangle.width();
        int height = rectangle.height();
        if (width <= 0 || height <= 0 || pixels.length < width * height)
            return false;
        WinDef.HDC screenDc = User32.INSTANCE.GetDC(null);
        if (screenDc == null) {
            logger.warn("No screen to read: error code " + Kernel32.INSTANCE.GetLastError());
            return false;
        }
        try {
            if (!reserveCapture(width, height))
                return false;
            if (!GDI32.INSTANCE.BitBlt(captureDc, 0, 0, width, height, screenDc, rectangle.x(),
                    rectangle.y(), SRCCOPY)) {
                logger.warn("Unable to read the screen: error code " +
                            Kernel32.INSTANCE.GetLastError());
                return false;
            }
            ExtendedGDI32.INSTANCE.GdiFlush();
            int count = width * height;
            captureBits.read(0, pixels, 0, count);
            // GDI leaves the fourth byte as it finds it.
            for (int index = 0; index < count; index++)
                pixels[index] |= 0xFF000000;
            return true;
        }
        finally {
            User32.INSTANCE.ReleaseDC(null, screenDc);
        }
    }

    private boolean reserveCapture(int width, int height) {
        if (captureDib != null && captureWidth == width && captureHeight == height)
            return true;
        if (captureDib != null) {
            GDI32.INSTANCE.SelectObject(captureDc, capturePreviousBitmap);
            GDI32.INSTANCE.DeleteObject(captureDib);
            GDI32.INSTANCE.DeleteDC(captureDc);
            captureDib = null;
            captureDc = null;
            captureBits = null;
        }
        WinDef.HDC memoryDc = GDI32.INSTANCE.CreateCompatibleDC(null);
        if (memoryDc == null)
            return false;
        PointerByReference bits = new PointerByReference();
        WinDef.HBITMAP dib = ExtendedGDI32.INSTANCE.CreateDIBSection(memoryDc,
                bitmapInfo(width, height), WinGDI.DIB_RGB_COLORS, bits, null, 0);
        if (dib == null || bits.getValue() == null) {
            logger.warn("No bitmap of " + width + "x" + height + " to read the screen into");
            GDI32.INSTANCE.DeleteDC(memoryDc);
            return false;
        }
        capturePreviousBitmap = GDI32.INSTANCE.SelectObject(memoryDc, dib);
        captureDc = memoryDc;
        captureDib = dib;
        captureBits = bits.getValue();
        captureWidth = width;
        captureHeight = height;
        return true;
    }

    /** 32 bits a pixel, the first row at the top. */
    private static WinGDI.BITMAPINFO bitmapInfo(int width, int height) {
        WinGDI.BITMAPINFO bitmapInfo = new WinGDI.BITMAPINFO();
        bitmapInfo.bmiHeader.biSize = bitmapInfo.bmiHeader.size();
        bitmapInfo.bmiHeader.biWidth = width;
        bitmapInfo.bmiHeader.biHeight = -height;
        bitmapInfo.bmiHeader.biPlanes = 1;
        bitmapInfo.bmiHeader.biBitCount = 32;
        bitmapInfo.bmiHeader.biCompression = WinGDI.BI_RGB;
        return bitmapInfo;
    }

    /**
     * As a device independent bitmap of 24 bits a pixel, which every program that
     * pastes pictures reads the same way: with 32 bits, some take the fourth byte for
     * transparency.
     */
    @Override
    public boolean copyImage(int[] pixels, int width, int height) {
        if (width <= 0 || height <= 0 || pixels.length < width * height)
            return false;
        int rowBytes = (width * 3 + 3) & ~3;
        long bytes = BITMAP_INFO_HEADER_BYTES + (long) rowBytes * height;
        Pointer memory = ExtendedKernel32.INSTANCE.GlobalAlloc(ExtendedKernel32.GMEM_MOVEABLE,
                bytes);
        if (memory == null) {
            logger.warn("No memory for a picture of " + width + "x" + height);
            return false;
        }
        boolean accepted = false;
        try {
            Pointer locked = ExtendedKernel32.INSTANCE.GlobalLock(memory);
            if (locked == null)
                return false;
            locked.setInt(0, BITMAP_INFO_HEADER_BYTES);
            locked.setInt(4, width);
            // Positive: the first row is the bottom one.
            locked.setInt(8, height);
            locked.setShort(12, (short) 1);
            locked.setShort(14, (short) 24);
            locked.setInt(16, WinGDI.BI_RGB);
            locked.setInt(20, rowBytes * height);
            locked.setInt(24, 0);
            locked.setInt(28, 0);
            locked.setInt(32, 0);
            locked.setInt(36, 0);
            byte[] row = new byte[rowBytes];
            for (int y = 0; y < height; y++) {
                int source = (height - 1 - y) * width;
                for (int x = 0, at = 0; x < width; x++) {
                    int pixel = pixels[source + x];
                    row[at++] = (byte) pixel;
                    row[at++] = (byte) (pixel >> 8);
                    row[at++] = (byte) (pixel >> 16);
                }
                locked.write(BITMAP_INFO_HEADER_BYTES + (long) y * rowBytes, row, 0, rowBytes);
            }
            ExtendedKernel32.INSTANCE.GlobalUnlock(memory);
            accepted = setClipboard(CF_DIB, memory);
            if (accepted)
                logger.info("Copied a picture of " + width + "x" + height);
            return accepted;
        }
        finally {
            if (!accepted)
                Kernel32.INSTANCE.GlobalFree(memory);
        }
    }

    @Override
    public boolean copyText(String text) {
        long bytes = (text.length() + 1L) * 2L;
        Pointer memory = ExtendedKernel32.INSTANCE.GlobalAlloc(ExtendedKernel32.GMEM_MOVEABLE,
                bytes);
        if (memory == null)
            return false;
        boolean accepted = false;
        try {
            Pointer locked = ExtendedKernel32.INSTANCE.GlobalLock(memory);
            if (locked == null)
                return false;
            locked.setWideString(0, text);
            ExtendedKernel32.INSTANCE.GlobalUnlock(memory);
            accepted = setClipboard(ExtendedUser32.CF_UNICODETEXT, memory);
            return accepted;
        }
        finally {
            if (!accepted)
                Kernel32.INSTANCE.GlobalFree(memory);
        }
    }

    /** The clipboard owns the memory once it took it. */
    private static boolean setClipboard(int format, Pointer memory) {
        if (!openClipboard())
            return false;
        try {
            if (!ExtendedUser32.INSTANCE.EmptyClipboard())
                return false;
            return ExtendedUser32.INSTANCE.SetClipboardData(format, memory) != null;
        }
        finally {
            ExtendedUser32.INSTANCE.CloseClipboard();
        }
    }

    /** Another program may hold the clipboard for a moment, a clipboard manager for one. */
    private static boolean openClipboard() {
        for (int attempt = 0; attempt < CLIPBOARD_ATTEMPTS; attempt++) {
            if (ExtendedUser32.INSTANCE.OpenClipboard(null))
                return true;
            try {
                Thread.sleep(CLIPBOARD_RETRY_MILLIS);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        logger.warn("The clipboard is held by another program");
        return false;
    }

    @Override
    public String clipboardText() {
        if (!ExtendedUser32.INSTANCE.IsClipboardFormatAvailable(ExtendedUser32.CF_UNICODETEXT))
            return null;
        if (!openClipboard())
            return null;
        try {
            Pointer memory = ExtendedUser32.INSTANCE.GetClipboardData(
                    ExtendedUser32.CF_UNICODETEXT);
            if (memory == null)
                return null;
            Pointer locked = ExtendedKernel32.INSTANCE.GlobalLock(memory);
            if (locked == null)
                return null;
            try {
                return locked.getWideString(0);
            }
            finally {
                ExtendedKernel32.INSTANCE.GlobalUnlock(memory);
            }
        }
        finally {
            ExtendedUser32.INSTANCE.CloseClipboard();
        }
    }

    @Override
    public int clipboardSequence() {
        return ExtendedUser32.INSTANCE.GetClipboardSequenceNumber();
    }

    @Override
    public Sprite renderText(String text, int pixelSize, int rgb) {
        if (text == null || text.isEmpty())
            return null;
        try {
            QFont font = QtHintFont.qFont(FONT_NAME, 12, FontWeight.BOLD);
            font.setPixelSize(pixelSize);
            QFontMetricsF metrics = new QFontMetricsF(font);
            int width = (int) Math.ceil(metrics.horizontalAdvance(text)) + 2;
            double ascent = metrics.ascent();
            int height = (int) Math.ceil(ascent + metrics.descent()) + 2;
            metrics.dispose();
            QImage image = new QImage(width, height, QImage.Format.Format_ARGB32_Premultiplied);
            image.fill(0);
            QPainter painter = new QPainter(image);
            painter.setRenderHint(QPainter.RenderHint.TextAntialiasing, true);
            painter.setFont(font);
            QColor color = new QColor((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
            painter.setPen(color);
            QPointF origin = new QPointF(1, 1 + Math.ceil(ascent));
            painter.drawText(origin, text);
            painter.end();
            painter.dispose();
            origin.dispose();
            color.dispose();
            IndicatorRenderer.IndicatorImage rendered = IndicatorRenderer.indicatorImage(image);
            image.dispose();
            font.dispose();
            return new Sprite(rendered.argb(), rendered.width(), rendered.height());
        }
        catch (RuntimeException e) {
            logger.warn("Unable to draw the text " + text, e);
            return null;
        }
    }

    @Override
    public Surface createSurface(boolean hiddenFromCapture) {
        try {
            return new LayeredSurface(hiddenFromCapture);
        }
        catch (RuntimeException e) {
            logger.error("Unable to create a screenshot window", e);
            return null;
        }
    }

    // Kept in a field: the callback must outlive the registration.
    private static WinUser.WindowProc windowProc;
    private static final String WINDOW_CLASS = "MousemasterScreenshot";

    private static synchronized void registerWindowClass() {
        if (windowProc != null)
            return;
        WinUser.WNDCLASSEX windowClass = new WinUser.WNDCLASSEX();
        windowProc = (window, message, wParam, lParam) ->
                User32.INSTANCE.DefWindowProc(window, message, wParam, lParam);
        windowClass.lpszClassName = WINDOW_CLASS;
        windowClass.lpfnWndProc = windowProc;
        User32.INSTANCE.RegisterClassEx(windowClass);
    }

    private static final class LayeredSurface implements Surface {

        private WinDef.HWND hwnd;
        private WinDef.HDC memoryDc;
        private WinNT.HANDLE previousBitmap;
        private WinDef.HBITMAP dib;
        private Pointer dibBits;
        private int dibWidth;
        private int dibHeight;
        private Rectangle rectangle;
        private boolean showing;

        LayeredSurface(boolean hiddenFromCapture) {
            registerWindowClass();
            int exStyle = User32.WS_EX_TOPMOST |
                          ExtendedUser32.WS_EX_NOACTIVATE |
                          ExtendedUser32.WS_EX_TOOLWINDOW |
                          ExtendedUser32.WS_EX_LAYERED |
                          ExtendedUser32.WS_EX_TRANSPARENT;
            hwnd = User32.INSTANCE.CreateWindowEx(exStyle, WINDOW_CLASS, WINDOW_CLASS,
                    WinUser.WS_POPUP, 0, 0, 1, 1, null, null,
                    Kernel32.INSTANCE.GetModuleHandle(null), null);
            if (hwnd == null)
                throw new IllegalStateException("CreateWindowEx failed: error code " +
                                                Kernel32.INSTANCE.GetLastError());
            if (hiddenFromCapture &&
                !ExtendedUser32.INSTANCE.SetWindowDisplayAffinity(hwnd,
                        ExtendedUser32.WDA_EXCLUDEFROMCAPTURE))
                logger.warn("The window cannot be hidden from captures: error code " +
                            Kernel32.INSTANCE.GetLastError());
        }

        @Override
        public void show(Rectangle rectangle, int[] pixels, Rectangle dirty) {
            if (hwnd == null || rectangle.width() <= 0 || rectangle.height() <= 0 ||
                pixels.length < rectangle.width() * rectangle.height())
                return;
            int width = rectangle.width();
            int height = rectangle.height();
            boolean whole = dirty == null || !showing || !rectangle.equals(this.rectangle) ||
                            width != dibWidth || height != dibHeight;
            Rectangle changed = whole ? rectangle : dirty.intersection(rectangle);
            if (changed.isEmpty())
                return;
            if (!reserve(width, height))
                return;
            int left = changed.x() - rectangle.x();
            int top = changed.y() - rectangle.y();
            if (whole)
                dibBits.write(0, pixels, 0, width * height);
            else
                for (int row = top; row < top + changed.height(); row++)
                    dibBits.write(4L * (row * width + left), pixels, row * width + left,
                            changed.width());
            this.rectangle = rectangle;
            if (!update(rectangle, whole ? null : changed))
                return;
            if (!showing) {
                showing = true;
                User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_SHOWNOACTIVATE);
                User32.INSTANCE.SetWindowPos(hwnd, ExtendedUser32.HWND_TOPMOST, 0, 0, 0, 0,
                        WinUser.SWP_NOMOVE | WinUser.SWP_NOSIZE | WinUser.SWP_NOACTIVATE);
            }
        }

        private boolean reserve(int width, int height) {
            if (dib != null && width == dibWidth && height == dibHeight)
                return true;
            release();
            WinDef.HDC dc = GDI32.INSTANCE.CreateCompatibleDC(null);
            if (dc == null)
                return false;
            PointerByReference bits = new PointerByReference();
            WinDef.HBITMAP bitmap = ExtendedGDI32.INSTANCE.CreateDIBSection(dc,
                    bitmapInfo(width, height), WinGDI.DIB_RGB_COLORS, bits, null, 0);
            if (bitmap == null || bits.getValue() == null) {
                logger.warn("No bitmap of " + width + "x" + height + " for a screenshot window");
                GDI32.INSTANCE.DeleteDC(dc);
                return false;
            }
            previousBitmap = GDI32.INSTANCE.SelectObject(dc, bitmap);
            memoryDc = dc;
            dib = bitmap;
            dibBits = bits.getValue();
            dibWidth = width;
            dibHeight = height;
            return true;
        }

        private void release() {
            if (dib == null)
                return;
            GDI32.INSTANCE.SelectObject(memoryDc, previousBitmap);
            GDI32.INSTANCE.DeleteObject(dib);
            GDI32.INSTANCE.DeleteDC(memoryDc);
            dib = null;
            memoryDc = null;
            dibBits = null;
            dibWidth = 0;
            dibHeight = 0;
        }

        /** The whole window when dirty is null. */
        private boolean update(Rectangle rectangle, Rectangle dirty) {
            WinDef.POINT position = new WinDef.POINT(rectangle.x(), rectangle.y());
            WinUser.SIZE size = new WinUser.SIZE(rectangle.width(), rectangle.height());
            WinDef.POINT source = new WinDef.POINT(0, 0);
            WinUser.BLENDFUNCTION blend = new WinUser.BLENDFUNCTION();
            blend.BlendOp = WinUser.AC_SRC_OVER;
            blend.SourceConstantAlpha = (byte) 255;
            blend.AlphaFormat = WinUser.AC_SRC_ALPHA;
            boolean updated;
            if (dirty == null)
                updated = User32.INSTANCE.UpdateLayeredWindow(hwnd, null, position, size,
                        memoryDc, source, 0, blend, WinUser.ULW_ALPHA);
            else {
                WinDef.RECT dirtyRect = new WinDef.RECT();
                dirtyRect.left = dirty.x() - rectangle.x();
                dirtyRect.top = dirty.y() - rectangle.y();
                dirtyRect.right = dirtyRect.left + dirty.width();
                dirtyRect.bottom = dirtyRect.top + dirty.height();
                position.write();
                size.write();
                source.write();
                blend.write();
                dirtyRect.write();
                ExtendedUser32.UPDATELAYEREDWINDOWINFO info =
                        new ExtendedUser32.UPDATELAYEREDWINDOWINFO();
                info.cbSize = info.size();
                info.pptDst = position.getPointer();
                info.psize = size.getPointer();
                info.hdcSrc = memoryDc;
                info.pptSrc = source.getPointer();
                info.pblend = blend.getPointer();
                info.dwFlags = WinUser.ULW_ALPHA;
                info.prcDirty = dirtyRect.getPointer();
                updated = ExtendedUser32.INSTANCE.UpdateLayeredWindowIndirect(hwnd, info);
            }
            if (!updated)
                logger.warn("Unable to show a screenshot window: error code " +
                            Kernel32.INSTANCE.GetLastError());
            return updated;
        }

        @Override
        public void hide() {
            if (hwnd != null && showing) {
                User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_HIDE);
                showing = false;
            }
        }

        @Override
        public void close() {
            if (hwnd == null)
                return;
            User32.INSTANCE.DestroyWindow(hwnd);
            hwnd = null;
            showing = false;
            release();
        }
    }
}
