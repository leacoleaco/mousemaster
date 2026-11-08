package mousemaster.platform.windows;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import io.qt.core.QRectF;
import io.qt.core.Qt;
import io.qt.gui.QColor;
import io.qt.gui.QPaintEvent;
import io.qt.gui.QPainter;
import io.qt.gui.QPen;
import io.qt.widgets.QWidget;
import mousemaster.Rectangle;
import mousemaster.Screen;
import mousemaster.qt.TransparentWindow;

import java.util.Set;

/**
 * Brief expanding rings at the cursor, for example when entering normal mode from idle.
 */
public final class WindowsCursorPing {

    private static final double PULSE_SECONDS = 0.36;
    private static final double GAP_SECONDS = 0.06;

    private static CursorPingHost host;
    private static boolean animating;
    private static double elapsed;
    private static double zoomPercent = 1;

    private WindowsCursorPing() {
    }

    public static void setZoomPercent(double zoomPercent) {
        WindowsCursorPing.zoomPercent = zoomPercent <= 0 ? 1 : zoomPercent;
    }

    public static void startAtCursor() {
        WinDef.POINT point = new WinDef.POINT();
        if (!User32.INSTANCE.GetCursorPos(point))
            return;
        start(point.x, point.y);
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
        int maxR = (int) Math.round(170 * scale);
        int penW = Math.max(2, (int) Math.round(3 * scale));
        if (host == null)
            createWindow();
        host.window.move(minX, minY);
        host.window.resize(width, height);
        host.widget.setGeometry(0, 0, width, height);
        host.widget.setGeometryParams(screenX - minX, screenY - minY, maxR, penW);
        host.widget.setRingRgb(0, 190, 255);
        host.widget.setPulseState(-1, -1);
        elapsed = 0;
        animating = true;
        host.window.show();
        host.widget.show();
        host.widget.raise();
        host.widget.repaint();
        User32.INSTANCE.SetWindowPos(host.hwnd, ExtendedUser32.INSTANCE.HWND_TOPMOST,
                0, 0, 0, 0,
                WinUser.SWP_NOMOVE | WinUser.SWP_NOSIZE | WinUser.SWP_NOACTIVATE);
    }

    public static void update(double delta) {
        if (!animating || host == null)
            return;
        elapsed += delta;
        double p0 = -1;
        double p1 = -1;
        if (elapsed < PULSE_SECONDS)
            p0 = elapsed / PULSE_SECONDS;
        else if (elapsed < PULSE_SECONDS + GAP_SECONDS) {
            // Quiet gap between the two ripples.
        }
        else if (elapsed < PULSE_SECONDS + GAP_SECONDS + PULSE_SECONDS)
            p1 = (elapsed - PULSE_SECONDS - GAP_SECONDS) / PULSE_SECONDS;
        else {
            cancel();
            return;
        }
        host.widget.setPulseState(p0, p1);
        host.widget.repaint();
    }

    public static void cancel() {
        animating = false;
        elapsed = 0;
        if (host != null) {
            host.window.hide();
            host.widget.setPulseState(-1, -1);
        }
    }

    private static void createWindow() {
        TransparentWindow window = new TransparentWindow();
        CursorPingWidget widget = new CursorPingWidget(window);
        widget.setGeometry(0, 0, 1, 1);
        WinDef.HWND hwnd = new WinDef.HWND(new Pointer(window.winId()));
        long currentStyle =
                User32.INSTANCE.GetWindowLongPtr(hwnd, WinUser.GWL_EXSTYLE).longValue();
        long newStyle = currentStyle | User32.WS_EX_TOPMOST |
                ExtendedUser32.WS_EX_NOACTIVATE |
                ExtendedUser32.WS_EX_TOOLWINDOW |
                ExtendedUser32.WS_EX_LAYERED | ExtendedUser32.WS_EX_TRANSPARENT;
        User32.INSTANCE.SetWindowLongPtr(hwnd, WinUser.GWL_EXSTYLE, new Pointer(newStyle));
        host = new CursorPingHost(hwnd, window, widget);
    }

    private record CursorPingHost(WinDef.HWND hwnd, TransparentWindow window,
                                  CursorPingWidget widget) {
    }

    private static final class CursorPingWidget extends QWidget {

        private int centerX;
        private int centerY;
        private int maxRadius;
        private int penWidth;
        private int ringRed;
        private int ringGreen = 200;
        private int ringBlue = 255;
        private double pulse0T = -1;
        private double pulse1T = -1;

        CursorPingWidget(QWidget parent) {
            super(parent);
            setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground);
            setAttribute(Qt.WidgetAttribute.WA_TransparentForMouseEvents);
        }

        void setRingRgb(int red, int green, int blue) {
            ringRed = red;
            ringGreen = green;
            ringBlue = blue;
        }

        void setGeometryParams(int centerX, int centerY, int maxRadius, int penWidth) {
            this.centerX = centerX;
            this.centerY = centerY;
            this.maxRadius = maxRadius;
            this.penWidth = penWidth;
        }

        void setPulseState(double pulse0T, double pulse1T) {
            this.pulse0T = pulse0T;
            this.pulse1T = pulse1T;
        }

        @Override
        protected void paintEvent(QPaintEvent event) {
            QPainter painter = new QPainter(this);
            painter.setCompositionMode(QPainter.CompositionMode.CompositionMode_Clear);
            QColor clear = new QColor(0, 0, 0, 0);
            painter.fillRect(rect(), clear);
            clear.dispose();
            painter.setCompositionMode(QPainter.CompositionMode.CompositionMode_SourceOver);
            painter.setRenderHint(QPainter.RenderHint.Antialiasing, true);
            if (pulse0T >= 0)
                drawPulse(painter, pulse0T);
            if (pulse1T >= 0)
                drawPulse(painter, pulse1T);
            painter.end();
            painter.dispose();
            super.paintEvent(event);
        }

        private void drawPulse(QPainter painter, double t) {
            double clampedT = Math.max(0, Math.min(1, t));
            double ease = 1.0 - Math.pow(1.0 - clampedT, 2.0);
            int radius = (int) Math.round(maxRadius * ease);
            if (radius < 1)
                return;
            int alpha = (int) Math.round(220 * (1.0 - clampedT));
            QColor strokeColor = new QColor(ringRed, ringGreen, ringBlue, alpha);
            QPen pen = new QPen(strokeColor);
            pen.setWidth(penWidth);
            painter.setPen(pen);
            painter.setBrush(Qt.BrushStyle.NoBrush);
            pen.dispose();
            strokeColor.dispose();
            painter.drawEllipse(new QRectF(centerX - radius, centerY - radius,
                    2.0 * radius, 2.0 * radius));
        }
    }
}
