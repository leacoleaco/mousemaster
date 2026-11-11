package mousemaster;

import io.qt.core.QTimer;
import io.qt.gui.QAction;
import io.qt.gui.QColor;
import io.qt.gui.QIcon;
import io.qt.gui.QPainter;
import io.qt.gui.QPen;
import io.qt.gui.QPixmap;
import io.qt.widgets.QApplication;
import io.qt.widgets.QMenu;
import io.qt.widgets.QSystemTrayIcon;
import mousemaster.platform.windows.WindowsProcessElevation;
import mousemaster.platform.windows.WindowsStartupShortcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

/**
 * Windows 通知区域图标。配置只从 properties 文件读取；右键菜单显示构建版本，并可暂停、
 * 重新加载配置，以及开机自启。
 */
public final class MousemasterTray implements ModeListener {

    private static final Logger logger = LoggerFactory.getLogger(MousemasterTray.class);

    private final Mousemaster app;
    private final TrayUiPreferences prefs;
    private QSystemTrayIcon trayIcon;
    private QIcon normalIcon;
    private QIcon errorIcon;
    private QMenu menu;
    private QAction modeAction;
    private QAction pauseAction;
    private QAction startupAction;
    private QAction consoleInTaskbarAction;
    private String currentModeName = Mode.IDLE_MODE_NAME;
    private boolean paused;
    private boolean configurationError;
    private boolean active;

    public MousemasterTray(Mousemaster app) {
        this.app = app;
        this.prefs = new TrayUiPreferences(app.configurationPath());
        if (!QSystemTrayIcon.isSystemTrayAvailable()) {
            logger.warn("System tray is not available; tray menu will be disabled.");
            return;
        }
        try {
            menu = new QMenu();
            modeAction = menu.addAction("当前模式：" + currentModeName);
            modeAction.setEnabled(false);
            QAction versionAction = menu.addAction("版本：" + BuildInfo.version());
            versionAction.setEnabled(false);
            menu.addSeparator();
            pauseAction = menu.addAction("暂停");
            pauseAction.setCheckable(true);
            pauseAction.triggered.connect(this::onPauseClicked);
            menu.addAction("重新加载配置").triggered.connect(app::reloadConfiguration);
            if (Os.windows) {
                startupAction = menu.addAction("开机自启");
                startupAction.setCheckable(true);
                startupAction.setChecked(WindowsStartupShortcut.isEnabled());
                startupAction.triggered.connect(this::onStartupClicked);
            }
            menu.addSeparator();
            consoleInTaskbarAction = menu.addAction("在任务栏显示控制台");
            consoleInTaskbarAction.setCheckable(true);
            consoleInTaskbarAction.triggered.connect(this::onConsoleInTaskbarClicked);
            menu.addSeparator();
            menu.addAction("退出").triggered.connect(this::quit);
            menu.aboutToShow.connect(this::onMenuAboutToShow);

            normalIcon = createTrayIcon();
            errorIcon = withErrorBadge(normalIcon);
            QApplication.setWindowIcon(normalIcon);
            QSystemTrayIcon icon = new QSystemTrayIcon(normalIcon);
            icon.setContextMenu(menu);
            icon.show();
            this.trayIcon = icon;
            this.active = true;
            refreshStatus();

            boolean fromConfig = !app.configuration().hideConsole();
            boolean visible;
            if (keepConsole())
                visible = true;
            else if (Files.isRegularFile(prefs.path()))
                visible = prefs.readConsoleVisibleInTaskbar(fromConfig);
            else {
                visible = false;
                prefs.writeConsoleVisibleInTaskbar(false);
            }
            blockConsoleActionSignals(() -> consoleInTaskbarAction.setChecked(visible));
            applyConsoleVisibility(visible);
            logger.info("System tray icon active; preferences at {}", prefs.path());
            if (keepConsole())
                logger.info("Console stays visible (-Dmousemaster.keepConsole); close it to exit");
            if (Os.windows) {
                if (WindowsProcessElevation.isElevated()
                        && WindowsStartupShortcut.hasStartupFolderLink()
                        && !WindowsStartupShortcut.hasElevatedTask())
                    logger.info("开机自启快捷方式无法拉起需要管理员权限的程序，请在托盘菜单里重新勾选一次「开机自启」");
                QTimer.singleShot(400, this::warnIfNotAdministrator);
            }
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

    private static QIcon withErrorBadge(QIcon base) {
        QIcon badged = new QIcon();
        boolean any = false;
        for (int size : new int[] {16, 24, 32, 48}) {
            QPixmap source = base.pixmap(size, size);
            if (source == null || source.isNull())
                continue;
            QPixmap pixmap = source.copy();
            QPainter painter = new QPainter(pixmap);
            try {
                painter.setRenderHint(QPainter.RenderHint.Antialiasing);
                int diameter = Math.max(7, size / 2);
                painter.setPen(new QPen(new QColor(255, 255, 255), Math.max(1d, size / 16d)));
                painter.setBrush(new QColor(0xDC, 0x26, 0x26));
                painter.drawEllipse(size - diameter, size - diameter, diameter - 1, diameter - 1);
            } finally {
                painter.end();
            }
            badged.addPixmap(pixmap);
            any = true;
        }
        return any ? badged : base;
    }

    public boolean isActive() {
        return active;
    }

    @Override
    public void modeChanged(Mode newMode) {
        if (!active || newMode == null)
            return;
        currentModeName = newMode.name();
        refreshStatus();
    }

    @Override
    public void modeTimedOut() {
    }

    void onPausedChanged(boolean paused) {
        this.paused = paused;
        refreshStatus();
    }

    void showConfigurationError(String message) {
        configurationError = true;
        if (trayIcon != null && errorIcon != null)
            trayIcon.setIcon(errorIcon);
        refreshStatus();
        showBalloon("配置错误", message, QSystemTrayIcon.MessageIcon.Critical);
    }

    void clearConfigurationError() {
        if (!configurationError)
            return;
        configurationError = false;
        if (trayIcon != null && normalIcon != null)
            trayIcon.setIcon(normalIcon);
        refreshStatus();
    }

    void showInformation(String title, String message) {
        showBalloon(title, message, QSystemTrayIcon.MessageIcon.Information);
    }

    /**
     * Re-applies console visibility from saved tray preferences after configuration reload.
     */
    public void applySavedConsoleVisibility() {
        if (!active || consoleInTaskbarAction == null)
            return;
        try {
            boolean fromConfig = !app.configuration().hideConsole();
            boolean visible = keepConsole() || prefs.readConsoleVisibleInTaskbar(fromConfig);
            blockConsoleActionSignals(() -> consoleInTaskbarAction.setChecked(visible));
            applyConsoleVisibility(visible);
        } catch (IOException e) {
            logger.warn("Could not read tray preferences", e);
        }
    }

    private void warnIfNotAdministrator() {
        if (!active || !Os.windows || WindowsProcessElevation.isElevated())
            return;
        showBalloon("未以管理员身份运行",
                "覆盖层会被管理员窗口挡住，也读不到这些窗口里的控件。需要时请退出后用管理员身份重新运行。",
                QSystemTrayIcon.MessageIcon.Warning);
    }

    private void onMenuAboutToShow() {
        if (startupAction == null)
            return;
        boolean enabled = WindowsStartupShortcut.isEnabled();
        if (startupAction.isChecked() == enabled)
            return;
        startupAction.blockSignals(true);
        try {
            startupAction.setChecked(enabled);
        } finally {
            startupAction.blockSignals(false);
        }
    }

    private void onPauseClicked() {
        app.setPaused(pauseAction.isChecked());
    }

    private void onStartupClicked() {
        if (startupAction == null)
            return;
        boolean want = startupAction.isChecked();
        try {
            WindowsStartupShortcut.setEnabled(want, app.configurationPath());
            showInformation("开机自启", want ? "已加入开机启动。" : "已取消开机启动。");
        } catch (Exception e) {
            logger.warn("Could not update the startup shortcut", e);
            startupAction.blockSignals(true);
            try {
                startupAction.setChecked(!want);
            } finally {
                startupAction.blockSignals(false);
            }
            showBalloon("开机自启", e.getMessage() == null ? "无法更新开机启动项" : e.getMessage(),
                    QSystemTrayIcon.MessageIcon.Warning);
        }
    }

    private void refreshStatus() {
        if (!active)
            return;
        String mode = currentModeName == null ? Mode.IDLE_MODE_NAME : currentModeName;
        String status = paused ? mode + "（已暂停）" : mode;
        if (configurationError)
            status = status + " · 配置有误";
        if (modeAction != null)
            modeAction.setText("当前模式：" + status);
        if (trayIcon != null)
            trayIcon.setToolTip("Mousemaster — " + status);
        if (pauseAction != null && pauseAction.isChecked() != paused) {
            pauseAction.blockSignals(true);
            try {
                pauseAction.setChecked(paused);
            } finally {
                pauseAction.blockSignals(false);
            }
        }
    }

    private void showBalloon(String title, String message, QSystemTrayIcon.MessageIcon icon) {
        if (trayIcon == null || message == null || message.isBlank())
            return;
        trayIcon.showMessage(title, message, icon, 8000);
    }

    private void onConsoleInTaskbarClicked() {
        if (consoleInTaskbarAction == null)
            return;
        if (keepConsole()) {
            blockConsoleActionSignals(() -> consoleInTaskbarAction.setChecked(true));
            applyConsoleVisibility(true);
            return;
        }
        boolean checked = consoleInTaskbarAction.isChecked();
        try {
            prefs.writeConsoleVisibleInTaskbar(checked);
        } catch (IOException e) {
            logger.warn("Could not save tray preferences", e);
        }
        applyConsoleVisibility(checked);
    }

    /**
     * {@code start_dev.cmd} passes {@code -Dmousemaster.keepConsole=true}. The saved tray
     * preference hides the console, which would leave the process running with no window
     * to close. This flag keeps the window for this process and does not rewrite the preference.
     */
    private static boolean keepConsole() {
        return Boolean.getBoolean("mousemaster.keepConsole");
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
