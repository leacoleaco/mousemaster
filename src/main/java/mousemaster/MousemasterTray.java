package mousemaster;

import io.qt.gui.QAction;
import io.qt.gui.QColor;
import io.qt.gui.QIcon;
import io.qt.gui.QPixmap;
import io.qt.widgets.QMenu;
import io.qt.widgets.QSystemTrayIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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

            QSystemTrayIcon icon = new QSystemTrayIcon(createTrayIcon());
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

    private static QIcon createTrayIcon() {
        QPixmap pixmap = new QPixmap(32, 32);
        pixmap.fill(new QColor(0x20, 0x4e, 0x8a));
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

    private static void applyConsoleVisibility(boolean visible) {
        if (visible)
            MousemasterApplication.showConsole();
        else
            MousemasterApplication.hideConsole();
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
