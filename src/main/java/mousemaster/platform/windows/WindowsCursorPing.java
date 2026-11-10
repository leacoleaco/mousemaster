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
import io.qt.core.QRectF;
import io.qt.core.Qt;
import io.qt.gui.QColor;
import io.qt.gui.QImage;
import io.qt.gui.QPainter;
import io.qt.gui.QPainterPath;
import io.qt.gui.QPen;
import mousemaster.Rectangle;
import mousemaster.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Set;

/**
 * Full-screen dim with a white ring at the cursor. Shown when entering normal mode
 * from idle, held for half a second, then hidden so the screen returns as it was.
 * <p>
 * Drawn with UpdateLayeredWindow, the same path as the indicator. A Qt window does not
 * get a full-screen translucent frame onto the desktop.
 */
public final class WindowsCursorPing {

    private static final Logger logger = LoggerFactory.getLogger(WindowsCursorPing.class);

    private static final double HOLD_SECONDS = 0.5;
    /** Logical pixels; multiplied by the screen scale. */
    private static final double CIRCLE_RADIUS = 64;
    private static final double STROKE_WIDTH = 4;
    private static final int DIM_ALPHA = 156;

    private static WinUser.WindowProc windowProc;
    private static WinDef.HWND hwnd;
    private static WinDef.HDC memoryDc;
    private static WinNT.HANDLE previousBitmap;
    private static WinDef.HBITMAP dib;
    private static ByteBuffer dibBuffer;
    private static int surfaceWidth;
    private static int surfaceHeight;
    private static QImage image;
    private static boolean animating;
    private static boolean windowShown;
    private static double elapsed;
    private static double zoomPercent = 1;

    private WindowsCursorPing() {
    }

    public static void setZoomPercent(double zoomPercent) {
        WindowsCursorPing.zoomPercent = zoomPercent <= 0 ? 1 : zoomPercent;
    }

    /** The spotlight window while it is showing, so it stays above the other overlays. */
    public static WinDef.HWND showingHwnd() {
        if (!animating || !windowShown || hwnd == null)
            return null;
        return hwnd;
    }

    public static void startAtCursor() {
        WinDef.POINT point = new WinDef.POINT();
        if (!User32.INSTANCE.GetCursorPos(point))
            return;
        try {
            start(point.x, point.y);
        } catch (RuntimeException e) {
            logger.error("Unable to show the cursor spotlight", e);
            cancel();
        }
    }

    public static void start(int screenX, int screenY) {
        WinDef.POINT point = new WinDef.POINT();
        point.x = screenX;
        point.y = screenY;
        Screen screen = WindowsScreen.findActiveScreen(point);
        Set<Screen> screens = WindowsScreen.findScreens();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (Screen s : screens) {
            Rectangle r = s.rectangle();
            minX = Math.min(minX, r.x());
            minY = Math.min(minY, r.y());
            maxX = Math.max(maxX, r.x() + r.width());
            maxY = Math.max(maxY, r.y() + r.height());
        }
        int width = Math.max(1, maxX - minX);
        int height = Math.max(1, maxY - minY);
        double scale = screen.scale() * zoomPercent;
        int radius = Math.max(1, (int) Math.round(CIRCLE_RADIUS * scale));
        int penWidth = Math.max(2, (int) Math.round(STROKE_WIDTH * scale));
        ensureWindow();
        if (hwnd == null)
            return;
        ensureSurface(width, height);
        draw(screenX - minX, screenY - minY, radius, penWidth, width, height);
        if (!present(minX, minY, width, height))
            return;
        elapsed = 0;
        animating = true;
        if (!windowShown) {
            User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_SHOWNOACTIVATE);
            windowShown = true;
        }
        User32.INSTANCE.SetWindowPos(hwnd, ExtendedUser32.HWND_TOPMOST, 0, 0, 0, 0,
                WinUser.SWP_NOMOVE | WinUser.SWP_NOSIZE | WinUser.SWP_NOACTIVATE);
        logger.info("Cursor spotlight at " + screenX + "," + screenY +
                    " radius " + radius + " for " + HOLD_SECONDS + "s");
    }

    public static void update(double delta) {
        if (!animating || hwnd == null)
            return;
        elapsed += delta;
        if (elapsed >= HOLD_SECONDS)
            cancel();
    }

    public static void cancel() {
        animating = false;
        elapsed = 0;
        if (hwnd != null && windowShown) {
            User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_HIDE);
            windowShown = false;
        }
    }

    private static void ensureWindow() {
        if (hwnd != null)
            return;
        WinUser.WNDCLASSEX windowClass = new WinUser.WNDCLASSEX();
        windowProc = (window, message, wParam, lParam) ->
                User32.INSTANCE.DefWindowProc(window, message, wParam, lParam);
        windowClass.lpszClassName = "MousemasterCursorPing";
        windowClass.lpfnWndProc = windowProc;
        User32.INSTANCE.RegisterClassEx(windowClass);
        int exStyle = User32.WS_EX_TOPMOST |
                      ExtendedUser32.WS_EX_NOACTIVATE |
                      ExtendedUser32.WS_EX_TOOLWINDOW |
                      ExtendedUser32.WS_EX_LAYERED |
                      ExtendedUser32.WS_EX_TRANSPARENT;
        hwnd = User32.INSTANCE.CreateWindowEx(exStyle, windowClass.lpszClassName,
                "MousemasterCursorPing", WinUser.WS_POPUP, 0, 0, 1, 1, null, null,
                Kernel32.INSTANCE.GetModuleHandle(null), null);
        if (hwnd == null)
            logger.error("Unable to create the cursor spotlight window: error code " +
                         Kernel32.INSTANCE.GetLastError());
    }

    private static void ensureSurface(int width, int height) {
        if (image != null && surfaceWidth == width && surfaceHeight == height)
            return;
        if (dib != null) {
            GDI32.INSTANCE.SelectObject(memoryDc, previousBitmap);
            GDI32.INSTANCE.DeleteObject(dib);
            GDI32.INSTANCE.DeleteDC(memoryDc);
            image.dispose();
            dib = null;
            image = null;
            memoryDc = null;
        }
        WinGDI.BITMAPINFO bitmapInfo = new WinGDI.BITMAPINFO();
        bitmapInfo.bmiHeader.biSize = bitmapInfo.bmiHeader.size();
        bitmapInfo.bmiHeader.biWidth = width;
        bitmapInfo.bmiHeader.biHeight = -height;
        bitmapInfo.bmiHeader.biPlanes = 1;
        bitmapInfo.bmiHeader.biBitCount = 32;
        bitmapInfo.bmiHeader.biCompression = WinGDI.BI_RGB;
        memoryDc = GDI32.INSTANCE.CreateCompatibleDC(null);
        PointerByReference bits = new PointerByReference();
        dib = ExtendedGDI32.INSTANCE.CreateDIBSection(memoryDc, bitmapInfo,
                WinGDI.DIB_RGB_COLORS, bits, null, 0);
        previousBitmap = GDI32.INSTANCE.SelectObject(memoryDc, dib);
        dibBuffer = bits.getValue().getByteBuffer(0, 4L * width * height);
        surfaceWidth = width;
        surfaceHeight = height;
        image = new QImage(width, height, QImage.Format.Format_ARGB32_Premultiplied);
    }

    /** Dims the whole desktop and leaves a clear circle, outlined in white, at the cursor. */
    private static void draw(int centerX, int centerY, int radius, int penWidth,
                             int width, int height) {
        QPainter painter = new QPainter(image);
        painter.setCompositionMode(QPainter.CompositionMode.CompositionMode_Source);
        QColor clear = new QColor(0, 0, 0, 0);
        painter.fillRect(0, 0, width, height, clear);
        clear.dispose();
        painter.setCompositionMode(QPainter.CompositionMode.CompositionMode_SourceOver);
        painter.setRenderHint(QPainter.RenderHint.Antialiasing, true);
        QPainterPath dim = new QPainterPath();
        dim.setFillRule(Qt.FillRule.OddEvenFill);
        dim.addRect(0, 0, width, height);
        double diameter = radius * 2.0;
        dim.addEllipse(centerX - radius, centerY - radius, diameter, diameter);
        QColor dimColor = new QColor(0, 0, 0, DIM_ALPHA);
        painter.setPen(Qt.PenStyle.NoPen);
        painter.setBrush(dimColor);
        painter.drawPath(dim);
        dim.dispose();
        dimColor.dispose();
        QColor white = new QColor(255, 255, 255);
        QPen pen = new QPen(white);
        pen.setWidth(penWidth);
        painter.setPen(pen);
        painter.setBrush(Qt.BrushStyle.NoBrush);
        double strokeRadius = radius + penWidth / 2.0;
        QRectF ring = new QRectF(centerX - strokeRadius, centerY - strokeRadius,
                strokeRadius * 2.0, strokeRadius * 2.0);
        painter.drawEllipse(ring);
        ring.dispose();
        pen.dispose();
        white.dispose();
        painter.end();
        painter.dispose();
        ByteBuffer bits = image.bits();
        int bytesPerLine = (int) image.bytesPerLine();
        int rowBytes = width * 4;
        for (int row = 0; row < height; row++)
            dibBuffer.put(row * rowBytes, bits, row * bytesPerLine, rowBytes);
    }

    private static boolean present(int x, int y, int width, int height) {
        WinUser.BLENDFUNCTION blend = new WinUser.BLENDFUNCTION();
        blend.BlendOp = WinUser.AC_SRC_OVER;
        blend.SourceConstantAlpha = (byte) 255;
        blend.AlphaFormat = WinUser.AC_SRC_ALPHA;
        boolean presented = User32.INSTANCE.UpdateLayeredWindow(hwnd, null,
                new WinDef.POINT(x, y), new WinUser.SIZE(width, height), memoryDc,
                new WinDef.POINT(0, 0), 0, blend, WinUser.ULW_ALPHA);
        if (!presented)
            logger.error("Unable to present the cursor spotlight: error code " +
                         Kernel32.INSTANCE.GetLastError());
        return presented;
    }
}
