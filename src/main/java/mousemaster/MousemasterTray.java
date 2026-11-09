package mousemaster;

import io.qt.gui.QAction;
import io.qt.gui.QColor;
import io.qt.gui.QIcon;
import io.qt.gui.QPainter;
import io.qt.gui.QPen;
import io.qt.gui.QPixmap;
import io.qt.widgets.QApplication;
import io.qt.widgets.QMenu;
import io.qt.widgets.QSystemTrayIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

/**
 * Windows 通知区域图标：默认仅显示在右下角托盘；右键打开菜单，从菜单进入「设置」才会打开设置窗口。
 */
public final class MousemasterTray {

    private static final Logger logger = LoggerFactory.getLogger(MousemasterTray.class);

    private final Mousemaster app;
    private final TrayUiPreferences prefs;
    private QSystemTrayIcon trayIcon;
    private QAction consoleInTaskbarAction;
    private VisualSettingsWindow settingsWindow;
    private boolean active;

    public MousemasterTray(Mousemaster app) {
        this.app = app;
        this.prefs = new TrayUiPreferences(app.configurationPath());
        if (!QSystemTrayIcon.isSystemTrayAvailable()) {
            logger.warn("System tray is not available; tray menu will be disabled.");
            return;
        }
        try {
            QMenu menu = new QMenu();
            menu.addAction("设置").triggered.connect(this::openSettings);
            menu.addSeparator();
            consoleInTaskbarAction = menu.addAction("在任务栏显示控制台");
            consoleInTaskbarAction.setCheckable(true);
            menu.addSeparator();
            menu.addAction("退出").triggered.connect(this::quit);

            QIcon mouseIcon = createTrayIcon();
            QApplication.setWindowIcon(mouseIcon);
            QSystemTrayIcon icon = new QSystemTrayIcon(mouseIcon);
            icon.setToolTip("Mousemaster — 右键打开菜单");
            icon.setContextMenu(menu);

            consoleInTaskbarAction.triggered.connect(this::onConsoleInTaskbarClicked);

            icon.show();
            this.trayIcon = icon;
            this.active = true;

            boolean fromConfig = !app.configuration().hideConsole();
            boolean visible;
            if (Files.isRegularFile(prefs.path()))
                visible = prefs.readConsoleVisibleInTaskbar(fromConfig);
            else {
                visible = false;
                prefs.writeConsoleVisibleInTaskbar(false);
            }
            blockConsoleActionSignals(() -> consoleInTaskbarAction.setChecked(visible));
            applyConsoleVisibility(visible);
            logger.info("System tray icon active; preferences at {}", prefs.path());
        } catch (Exception e) {
            logger.error("Failed to initialize system tray", e);
        }
    }

    /**
     * This Qt build has no ico plugin, so the PNG frames packed in
     * {@code icons/mouse.ico} are loaded on their own. The same file is the
     * executable's icon.
     */
    private static QIcon createTrayIcon() {
        try (InputStream in = MousemasterTray.class.getResourceAsStream("/icons/mouse.ico")) {
            if (in == null) {
                logger.warn("icons/mouse.ico is missing");
                return fallbackTrayIcon();
            }
            QIcon icon = iconFromIco(in.readAllBytes());
            if (icon != null && !icon.isNull())
                return icon;
            logger.warn("icons/mouse.ico did not contain a usable image");
        } catch (IOException e) {
            logger.warn("Could not load icons/mouse.ico", e);
        }
        return fallbackTrayIcon();
    }

    private static QIcon iconFromIco(byte[] bytes) {
        if (bytes.length < 6 || u16(bytes, 0) != 0 || u16(bytes, 2) != 1)
            return null;
        int count = u16(bytes, 4);
        QIcon icon = new QIcon();
        for (int i = 0; i < count; i++) {
            int entry = 6 + i * 16;
            if (entry + 16 > bytes.length)
                break;
            int length = u32(bytes, entry + 8);
            int offset = u32(bytes, entry + 12);
            if (length < 8 || offset < 0 || offset > bytes.length - length)
                continue;
            if (!isPng(bytes, offset))
                continue;
            QPixmap pixmap = new QPixmap();
            if (pixmap.loadFromData(bytes, offset, length, "PNG") && !pixmap.isNull())
                icon.addPixmap(pixmap);
        }
        return icon;
    }

    private static boolean isPng(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) == 0x89
                && bytes[offset + 1] == 0x50
                && bytes[offset + 2] == 0x4E
                && bytes[offset + 3] == 0x47;
    }

    private static int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static int u32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static QIcon fallbackTrayIcon() {
        QPixmap pixmap = new QPixmap(32, 32);
        pixmap.fill(new QColor(0, 0, 0, 0));
        QPainter painter = new QPainter(pixmap);
        painter.setRenderHint(QPainter.RenderHint.Antialiasing);
        painter.setPen(new QPen(new QColor(34, 38, 46), 1.6));
        painter.setBrush(new QColor(245, 247, 250));
        painter.drawEllipse(4, 2, 24, 28);
        painter.setPen(new QPen(new QColor(96, 104, 116), 1.2));
        painter.drawLine(16, 4, 16, 13);
        painter.drawLine(7, 13, 25, 13);
        painter.setPen(new QPen(new QColor(0, 0, 0, 0)));
        painter.setBrush(new QColor(0x20, 0x4e, 0x8a));
        painter.drawRoundedRect(14, 5, 4, 7, 1.5, 1.5);
        painter.end();
        return new QIcon(pixmap);
    }

    public boolean isActive() {
        return active;
    }

    /**
     * Re-applies console visibility from saved tray preferences after configuration reload.
     */
    public void applySavedConsoleVisibility() {
        if (!active || consoleInTaskbarAction == null)
            return;
        try {
            boolean fromConfig = !app.configuration().hideConsole();
            boolean visible = prefs.readConsoleVisibleInTaskbar(fromConfig);
            blockConsoleActionSignals(() -> consoleInTaskbarAction.setChecked(visible));
            applyConsoleVisibility(visible);
        } catch (IOException e) {
            logger.warn("Could not read tray preferences", e);
        }
    }

    private void onConsoleInTaskbarClicked() {
        if (consoleInTaskbarAction == null)
            return;
        boolean checked = consoleInTaskbarAction.isChecked();
        try {
            prefs.writeConsoleVisibleInTaskbar(checked);
        } catch (IOException e) {
            logger.warn("Could not save tray preferences", e);
        }
        applyConsoleVisibility(checked);
    }

    private void applyConsoleVisibility(boolean visible) {
        if (visible)
            app.console().show();
        else
            app.console().hide();
    }

    private void blockConsoleActionSignals(Runnable runnable) {
        if (consoleInTaskbarAction == null) {
            runnable.run();
            return;
        }
        consoleInTaskbarAction.blockSignals(true);
        try {
            runnable.run();
        } finally {
            consoleInTaskbarAction.blockSignals(false);
        }
    }

    private void openSettings() {
        if (settingsWindow != null) {
            settingsWindow.raise();
            settingsWindow.activateWindow();
            return;
        }
        VisualSettingsWindow w = new VisualSettingsWindow(app.configurationPath());
        w.setAttribute(io.qt.core.Qt.WidgetAttribute.WA_DeleteOnClose, true);
        w.finished.connect((Integer r) -> settingsWindow = null);
        settingsWindow = w;
        w.show();
    }

    private void quit() {
        app.exitFromTray();
    }

    /**
     * Used when the JVM exits without going through the tray menu.
     */
    public void hide() {
        if (trayIcon != null) {
            trayIcon.hide();
            trayIcon.dispose();
            trayIcon = null;
        }
        active = false;
    }
}
