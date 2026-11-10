package mousemaster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class Mousemaster {

    private static final Logger logger = LoggerFactory.getLogger(Mousemaster.class);
    public static final double FRAME_INTERVAL = 0.016;

    private final Path configurationPath;
    private final Platform platform;
    private final WatchService watchService;
    private Configuration configuration;
    private MouseManager mouseManager;
    private CommandRunner commandRunner;
    private MacroPlayer macroPlayer;
    private AnimationPlayer animationPlayer;
    private KeyboardManager keyboardManager;
    private IndicatorManager indicatorManager;
    private HintManager hintManager;
    private ZoomManager zoomManager;
    private ModeController modeController;
    private List<String> configurationProperties;
    private KeyboardLayout activeKeyboardLayout;
    private KeyboardLayout forcedActiveKeyboardLayout;
    private final boolean preWarmHints;
    private boolean logToFileEnabledByConfiguration;
    private MousemasterTray tray;
    private ComboWatcher comboWatcher;
    private List<ModeListener> modeListeners;
    private boolean paused;

    public Mousemaster(Path configurationPath, Platform platform, boolean preWarmHints)
            throws IOException {
        this.configurationPath = configurationPath;
        this.platform = platform;
        this.preWarmHints = preWarmHints;
        this.activeKeyboardLayout = platform.activeKeyboardLayout();
        platform.console().hide();
        QtManager.initialize();
        try {
            loadConfiguration(true, false);
        } catch (Exception e) {
            logger.error("Unable to load configuration file " + configurationPath, e);
            showBlockingConfigurationError(
                    ConfigurationProblem.locate(configurationPath, e).summary(false));
            if (e instanceof IOException ioException)
                throw ioException;
            if (e instanceof RuntimeException runtimeException)
                throw runtimeException;
            throw new IOException("Unable to load configuration file " + configurationPath, e);
        }
        watchService = FileSystems.getDefault().newWatchService();
        configurationPath.toAbsolutePath()
                         .getParent()
                         .register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
        tray = new MousemasterTray(this);
        if (tray.isActive()) {
            modeListeners.add(tray);
            comboWatcher.setModeListeners(modeListeners);
            if (modeController.currentMode() != null)
                tray.modeChanged(modeController.currentMode());
        }
        if (!tray.isActive()) {
            if (configuration.hideConsole())
                platform.console().hide();
            else
                platform.console().show();
        }
    }

    public void run() throws InterruptedException {
        long previousIterationBeginTime = System.nanoTime();
        long previousMacroPlayerUpdateTime = previousIterationBeginTime;
        while (true) {
            long iterationBeginTime = System.nanoTime();
            long deltaNanos = iterationBeginTime - previousIterationBeginTime;
            previousIterationBeginTime = iterationBeginTime;
            double delta = deltaNanos / 1e9d;
            long timeBeforeOp = iterationBeginTime;
            long pumpEventsNanos = 0;
            updateConfiguration();
            long timeAfterOp = System.nanoTime();
            long updateConfigurationDuration = (long) ((timeAfterOp - timeBeforeOp) / 1e6);
            platform.pumpEvents();
            timeBeforeOp = System.nanoTime();
            pumpEventsNanos += timeBeforeOp - timeAfterOp;
            updateActiveKeyboardLayout(delta);
            timeAfterOp = System.nanoTime();
            long updateActiveKeyboardLayoutDuration = (long) ((timeAfterOp - timeBeforeOp) / 1e6);
            platform.pumpEvents();
            timeBeforeOp = System.nanoTime();
            pumpEventsNanos += timeBeforeOp - timeAfterOp;
            QtManager.processEvents();
            timeAfterOp = System.nanoTime();
            long qtDuration = (long) ((timeAfterOp - timeBeforeOp) / 1e6);
            timeBeforeOp = timeAfterOp;
            platform.update(delta);
            timeAfterOp = System.nanoTime();
            long platformDuration = (long) ((timeAfterOp - timeBeforeOp) / 1e6);
            platform.pumpEvents();
            timeBeforeOp = System.nanoTime();
            pumpEventsNanos += timeBeforeOp - timeAfterOp;
            if (!paused)
                modeController.update(delta);
            timeAfterOp = System.nanoTime();
            long modeControllerDuration = (long) ((timeAfterOp - timeBeforeOp) / 1e6);
            platform.pumpEvents();
            timeBeforeOp = System.nanoTime();
            pumpEventsNanos += timeBeforeOp - timeAfterOp;
            if (!paused) {
                mouseManager.update(delta);
                commandRunner.update(delta);
            }
            hintManager.update(delta);
            timeAfterOp = System.nanoTime();
            long mouseControllerDuration = (long) ((timeAfterOp - timeBeforeOp) / 1e6);
            platform.pumpEvents();
            timeBeforeOp = System.nanoTime();
            pumpEventsNanos += timeBeforeOp - timeAfterOp;
            animationPlayer.update(delta);
            if (!paused)
                keyboardManager.update(delta);
            timeAfterOp = System.nanoTime();
            long keyboardManagerDuration = (long) ((timeAfterOp - timeBeforeOp) / 1e6);
            platform.pumpEvents();
            timeBeforeOp = System.nanoTime();
            pumpEventsNanos += timeBeforeOp - timeAfterOp;
            if (!paused)
                modeController.updateBuiltInVirtualKeys();
            if (!hintManager.waitingForUiElements())
                indicatorManager.update(delta);
            timeAfterOp = System.nanoTime();
            long indicatorManagerDuration = (long) ((timeAfterOp - timeBeforeOp) / 1e6);
            platform.pumpEvents();
            timeBeforeOp = System.nanoTime();
            pumpEventsNanos += timeBeforeOp - timeAfterOp;
            zoomManager.update(delta);
            timeAfterOp = System.nanoTime();
            long zoomManagerDuration = (long) ((timeAfterOp - timeBeforeOp) / 1e6);
            platform.pumpEvents();
            timeBeforeOp = System.nanoTime();
            pumpEventsNanos += timeBeforeOp - timeAfterOp;
            if (!paused)
                macroPlayer.update(
                    (timeBeforeOp - previousMacroPlayerUpdateTime) / 1e9d);
            previousMacroPlayerUpdateTime = timeBeforeOp;
            timeAfterOp = System.nanoTime();
            long macroPlayerDuration = (long) ((timeAfterOp - timeBeforeOp) / 1e6);
            long iterationEndTime = System.nanoTime();
            long iterationDuration =
                    (long) ((iterationEndTime - iterationBeginTime) / 1e6);
            if (iterationDuration > 20L) {
                logger.debug("Long iteration duration: " + iterationDuration + "ms, " +
                             "updateConfigurationDuration = " + updateConfigurationDuration + "ms, " +
                             "updateActiveKeyboardLayoutDuration = " + updateActiveKeyboardLayoutDuration + "ms, " +
                             "pumpEventsDuration = " + pumpEventsNanos / 1_000_000 + "ms, " +
                             "qtDuration = " + qtDuration + "ms, " +
                             "platformDuration = " + platformDuration + "ms, " +
                             "modeControllerDuration = " + modeControllerDuration + "ms, " +
                             "mouseControllerDuration = " + mouseControllerDuration + "ms, " +
                             "keyboardManagerDuration = " + keyboardManagerDuration + "ms, " +
                             "indicatorManagerDuration = " + indicatorManagerDuration + "ms, " +
                             "zoomManagerDuration = " + zoomManagerDuration + "ms, " +
                             "macroPlayerDuration = " + macroPlayerDuration + "ms");
            }
            platform.sleep();
        }
    }

    private void updateActiveKeyboardLayout(double delta) {
        if (forcedActiveKeyboardLayout != null)
            return;
        KeyboardLayout newActiveKeyboardLayout = platform.activeKeyboardLayout();
        if (!newActiveKeyboardLayout.equals(activeKeyboardLayout)) {
            activeKeyboardLayout = newActiveKeyboardLayout;
            tryLoadConfiguration(false);
        }
    }

    private void updateConfiguration() {
        WatchKey key = watchService.poll();
        if (key == null)
            return;
        for (WatchEvent<?> event : key.pollEvents()) {
            WatchEvent.Kind<?> kind = event.kind();
            Path path = (Path) event.context();
            if (!path.getFileName()
                     .toString()
                     .equals(configurationPath.getFileName().toString()))
                continue;
            if (kind.equals(StandardWatchEventKinds.ENTRY_DELETE))
                logger.info("Configuration file " + configurationPath + " was deleted");
            else {
                logger.info("Configuration file " + configurationPath + " has changed");
                tryLoadConfiguration(true);
            }
        }
        key.reset();
    }

    private boolean tryLoadConfiguration(boolean reReadFile) {
        try {
            loadConfiguration(reReadFile, true);
            if (tray != null)
                tray.clearConfigurationError();
            return true;
        } catch (Exception e) {
            logger.error(
                    "Unable to load configuration file " + configurationPath, e);
            String message = ConfigurationProblem.locate(configurationPath, e).summary(true);
            if (tray != null && tray.isActive())
                tray.showConfigurationError(message);
            else
                showBlockingConfigurationError(message);
            return false;
        }
    }

    private static void showBlockingConfigurationError(String text) {
        try {
            io.qt.widgets.QMessageBox.critical(null, "Mousemaster 配置错误", text);
        } catch (Exception dialogError) {
            logger.error("Could not show the configuration error dialog", dialogError);
        }
    }

    /**
     * @param applyHideConsoleFromConfig when {@code false} (initial load before tray), skip
     *                                     show/hide console so the tray can apply tray-only startup.
     */
    private void loadConfiguration(boolean readFile, boolean applyHideConsoleFromConfig) throws IOException {
        boolean reload = configuration != null;
        List<String> propertiesRead = null;
        if (readFile) {
            try (BufferedReader reader = Files.newBufferedReader(configurationPath,
                    StandardCharsets.UTF_8)) {
                propertiesRead = PropertiesReader.readPropertiesFile(reader);
            }
            if (propertiesRead.equals(configurationProperties))
                return;
        }
        configuration = ConfigurationParser.parse(
                propertiesRead != null ? propertiesRead : configurationProperties,
                activeKeyboardLayout);
        // User can override the layout. When active layout is dvorak, Windows HKL only
        // gives the language identifier, which is 0409. But it is missing the other part
        // of the layout identifier (00010409).
        forcedActiveKeyboardLayout = configuration.forcedActiveKeyboardLayout();
        activeKeyboardLayout = configuration.activeKeyboardLayout();
        if (configuration.logLevel() != null)
            MousemasterApplication.setLogLevel(configuration.logLevel());
        MousemasterApplication.setModeColumnWidth(
                configuration.modeMap().modes().stream()
                             .mapToInt(mode -> mode.name().length())
                             .max().orElse(0));
        // Only what a configuration turned on is turned back off: --log-to-file asked for the
        // file before there was a configuration, and no configuration mentioning nothing undoes it.
        if (configuration.logToFile()) {
            MousemasterApplication.enableLogToFile();
            logToFileEnabledByConfiguration = true;
        }
        else if (logToFileEnabledByConfiguration) {
            MousemasterApplication.disableLogToFile();
            logToFileEnabledByConfiguration = false;
        }
        if (applyHideConsoleFromConfig) {
            if (tray != null && tray.isActive())
                tray.applySavedConsoleVisibility();
            else if (configuration.hideConsole())
                platform.console().hide();
            else
                platform.console().show();
        }
        logger.info((reload ? "Reloaded" : "Loaded") + " configuration " +
                    (readFile ? "file " + configurationPath + " " : "") +
                    "with active keyboard layout " + activeKeyboardLayout);
        ScreenManager screenManager = new ScreenManager(platform::screens);
        mouseManager = new MouseManager(screenManager, platform.mouse());
        MouseState mouseState = new MouseState(mouseManager);
        KeyRedactor keyRedactor = new KeyRedactor(configuration.keyRedaction());
        hintManager =
                new HintManager(configuration.positionHistoryConfigurationByName(),
                        screenManager, mouseManager, platform.overlay(),
                        platform.uiAutomation(), platform.activeAppFinder(),
                        keyRedactor, platform.vision());
        commandRunner = new CommandRunner(mouseManager, hintManager, platform);
        Set<Key> unpressedComboPreconditionKeys = new HashSet<>();
        Set<Key> pressedComboPreconditionKeys = new HashSet<>();
        for (Mode mode : configuration.modeMap().modes()) {
            for (Combo combo : mode.comboMap().commandsByCombo().keySet()) {
                unpressedComboPreconditionKeys.addAll(combo.precondition()
                                                           .keyPrecondition()
                                                           .unpressedKeySet()
                                                           .stream()
                                                           .toList());
                pressedComboPreconditionKeys.addAll(combo.precondition()
                                                         .keyPrecondition()
                                                         .pressedKeyPrecondition()
                                                         .allKeys());
            }
        }
        comboWatcher =
                new ComboWatcher(commandRunner, hintManager, platform.activeAppFinder(),
                        screenManager, platform.clock(),
                        unpressedComboPreconditionKeys,
                        pressedComboPreconditionKeys, keyRedactor,
                        configuration.modeMap(), configuration.initiallySetVariables(),
                        configuration.virtualKeys(), configuration.initiallyPressedVirtualKeys(),
                        configuration.activeKeyboardLayout());
        KeyRegurgitator keyRegurgitator =
                new KeyRegurgitator(platform.keyboard(), keyRedactor);
        keyboardManager = new KeyboardManager(comboWatcher, hintManager, keyRegurgitator,
                platform.clock());
        macroPlayer = new MacroPlayer(platform.clock(), comboWatcher, keyboardManager,
                platform.keyboard(), keyRedactor, configuration.activeKeyboardLayout());
        keyboardManager.setMacroPlayer(macroPlayer);
        KeyboardState keyboardState = new KeyboardState(keyboardManager);
        animationPlayer = new AnimationPlayer(configuration.animationConfigurationByName());
        indicatorManager = new IndicatorManager(platform.overlay(), animationPlayer, hintManager,
                comboWatcher::lastActiveApp);
        zoomManager = new ZoomManager(screenManager, hintManager, platform.overlay());
        // ComboWatcher is the sole broadcaster to ModeListeners: it broadcasts
        // on mode switch (delegated from ModeController) and on mode mutation.
        // ZoomManager must be notified after HintManager because it calls
        // lastSelectedHintPoint() which is updated by HintManager#modeChanged. IndicatorManager
        // draws with the state of both.
        modeListeners = new ArrayList<>(
                List.of(platform, mouseManager, hintManager, zoomManager, indicatorManager));
        if (tray != null && tray.isActive())
            modeListeners.add(tray);
        comboWatcher.setModeListeners(modeListeners);
        if (modeController != null)
            modeController.showCursor();
        modeController =
                new ModeController(configuration.modeMap(), mouseManager, mouseState,
                        keyboardState,
                        hintManager,
                        comboWatcher, animationPlayer);
        commandRunner.setModeController(modeController);
        commandRunner.setMacroPlayer(macroPlayer);
        commandRunner.setAnimationPlayer(animationPlayer);
        hintManager.setModeController(modeController);
        modeController.switchMode(Mode.IDLE_MODE_NAME);
        platform.reset(mouseManager, keyboardManager, keyRegurgitator, macroPlayer,
                keyRedactor, configuration.logLastKeyEventsOnExit(),
                configuration.modeMap(),
                zoomManager,
                indicatorManager,
                hintManager,
                List.of(mouseManager, hintManager, screenManager), activeKeyboardLayout,
                true);
        if (preWarmHints)
            hintManager.preWarmHintMeshes(configuration.modeMap());
        // Last, so the detector does not compete with the mesh pre-warm for cores.
        Integer density = uiVisionDensity(configuration.modeMap());
        if (density != null)
            platform.vision().preWarm(screenManager.activeScreen(), density);
        if (propertiesRead != null)
            configurationProperties = propertiesRead;
        if (paused)
            showCursorWhilePaused();
    }

    public Path configurationPath() {
        return configurationPath;
    }

    public Configuration configuration() {
        return configuration;
    }

    public mousemaster.platform.Console console() {
        return platform.console();
    }

    public void setPaused(boolean paused) {
        if (this.paused == paused)
            return;
        this.paused = paused;
        logger.info(paused ? "Paused; keyboard and mouse events pass through"
                           : "Resumed keyboard and mouse hooks");
        if (tray != null)
            tray.onPausedChanged(paused);
        platform.setHooksSuspended(paused);
        if (paused && modeController != null) {
            Mode current = modeController.currentMode();
            if (current != null && !Mode.IDLE_MODE_NAME.equals(current.name()))
                modeController.switchMode(Mode.IDLE_MODE_NAME);
            showCursorWhilePaused();
        }
    }

    public void reloadConfiguration() {
        if (tryLoadConfiguration(true) && tray != null)
            tray.showInformation("配置已重新加载", "已从配置文件重新加载。");
    }

    private void showCursorWhilePaused() {
        if (modeController != null)
            modeController.showCursor();
        platform.mouse().showCursor();
    }

    void exitFromTray() {
        if (tray != null) {
            tray.hide();
            tray = null;
        }
        platform.requestKillProcess();
    }

    private static Integer uiVisionDensity(ModeMap modeMap) {
        return modeMap.modes()
                      .stream()
                      .filter(mode -> mode.hintMesh().enabled())
                      .map(mode -> mode.hintMesh().type())
                      .filter(type -> type.type() == HintMeshType.HintMeshTypeType.UI_VISION)
                      .map(HintMeshType::density)
                      .findFirst()
                      .orElse(null);
    }

}
