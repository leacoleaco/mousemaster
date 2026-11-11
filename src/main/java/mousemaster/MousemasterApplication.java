package mousemaster;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.filter.ThresholdFilter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.OutputStreamAppender;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Scanner;
import java.util.logging.LogManager;
import java.util.zip.GZIPInputStream;

public class MousemasterApplication {

    private static final Logger logger;
    public static String tempDirectory;
    private static int modeColumnWidth;

    static {
        System.setProperty("slf4j.internal.verbosity", "WARN");
        logger = (Logger) LoggerFactory.getLogger(MousemasterApplication.class);
        // QtJambi uses JUL. We want it bridged with slf4j.
        LogManager.getLogManager().reset();
        SLF4JBridgeHandler.install();
    }

    public static void setTempDirectory(String tempDirectory) {
        if (tempDirectory != null)
            MousemasterApplication.tempDirectory = tempDirectory;
        else {
            if (Os.windows) {
                boolean userNameIsAscii =
                        System.getProperty("user.name").matches("[\\x00-\\x7F]+");
                if (!userNameIsAscii) {
                    // https://github.com/oracle/graal/issues/8095
                    MousemasterApplication.tempDirectory = "mousemaster-temp";
                }
            }
        }
        if (MousemasterApplication.tempDirectory == null)
            MousemasterApplication.tempDirectory =
                    System.getProperty("java.io.tmpdir") + "mousemaster-" +
                    System.getProperty("user.name").hashCode();
        System.setProperty("jna.tmpdir", MousemasterApplication.tempDirectory + "/jna");
    }

    /** The build gzips the large resources; an IDE build of the classes directory leaves them raw. */
    public static InputStream resourceStream(String resourcePath) {
        ClassLoader classLoader = MousemasterApplication.class.getClassLoader();
        InputStream inputStream = classLoader.getResourceAsStream(resourcePath);
        if (inputStream != null)
            return inputStream;
        try {
            return new GZIPInputStream(classLoader.getResourceAsStream(resourcePath + ".gz"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void shutdownAfterException(Throwable e, Platform platform,
                                              boolean jnaCallback,
                                              boolean pauseOnError) {
        if (platform != null)
            platform.shutdown();
        logger.error(jnaCallback ? "Error in JNA callback" : "", e);
        // Without a terminal there is nobody to press Enter: the read blocks or throws,
        // either of which skips the shutdown below. System.console() alone stopped
        // meaning interactive in java 22, it is now non null on a redirected stream too.
        if (pauseOnError && System.console() != null && System.console().isTerminal()) {
            logger.info(
                    "An error has occurred. The details of the error should be right above this message. Press Enter in this window to close mousemaster.");
            new Scanner(System.in).nextLine();
        }
        if (platform == null)
            System.exit(1); // Qt was never initialized: exiting cannot hang.
        platform.killProcess(1);
    }

    /**
     * Beside {@code mousemaster.exe} when that is the running program, so a hidden
     * console still leaves the log next to the file the user launched. A java launch
     * keeps the working directory.
     */
    public static Path logFile() {
        Path directory = ProcessHandle.current().info().command()
                                       .map(Path::of)
                                       .filter(path -> {
                                           Path name = path.getFileName();
                                           return name != null &&
                                                  name.toString().equalsIgnoreCase("mousemaster.exe");
                                       })
                                       .map(Path::getParent)
                                       .orElse(null);
        if (directory == null)
            directory = Path.of("").toAbsolutePath();
        return directory.resolve("mousemaster.log");
    }

    /** ERROR records are always written to {@link #logFile()}. */
    public static void ensureErrorLog() {
        try {
            ensureFileAppender(true);
        } catch (RuntimeException e) {
            logger.warn("Could not open error log {}", logFile(), e);
        }
    }

    /**
     * Points the console appender at a new stream. {@code AllocConsole} does not
     * retarget the stream logback captured at startup.
     */
    public static void setConsoleLogStream(OutputStream stream) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        Appender<ILoggingEvent> async = rootLogger.getAppender("ASYNC_CONSOLE");
        if (!(async instanceof AsyncAppender asyncAppender))
            return;
        Appender<ILoggingEvent> console = asyncAppender.getAppender("CONSOLE");
        if (console instanceof OutputStreamAppender<ILoggingEvent> output)
            output.setOutputStream(stream);
    }

    public static void enableLogToFile() {
        ensureFileAppender(false);
    }

    /** Stops the full log. ERROR records keep going to {@link #logFile()}. */
    public static void disableLogToFile() {
        FileAppender<ILoggingEvent> fileAppender = fileAppender();
        if (fileAppender == null)
            return;
        fileAppender.clearAllFilters();
        fileAppender.addFilter(errorFilter());
    }

    private static void ensureFileAppender(boolean errorsOnly) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        FileAppender<ILoggingEvent> existing = fileAppender();
        if (existing != null) {
            existing.clearAllFilters();
            if (errorsOnly)
                existing.addFilter(errorFilter());
            return;
        }
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        startWithCurrentPattern(encoder);
        FileAppender<ILoggingEvent> fileAppender = new FileAppender<>();
        fileAppender.setName("FILE");
        fileAppender.setContext(context);
        fileAppender.setFile(logFile().toString());
        fileAppender.setEncoder(encoder);
        fileAppender.setAppend(true);
        if (errorsOnly)
            fileAppender.addFilter(errorFilter());
        fileAppender.start();
        AsyncAppender asyncAppender = new AsyncAppender();
        asyncAppender.setName("ASYNC_FILE");
        asyncAppender.setContext(context);
        asyncAppender.setQueueSize(4096);
        asyncAppender.setDiscardingThreshold(0);
        asyncAppender.addAppender(fileAppender);
        asyncAppender.start();
        rootLogger.addAppender(asyncAppender);
    }

    private static FileAppender<ILoggingEvent> fileAppender() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        Appender<ILoggingEvent> async = rootLogger.getAppender("ASYNC_FILE");
        if (!(async instanceof AsyncAppender asyncAppender))
            return null;
        Appender<ILoggingEvent> file = asyncAppender.getAppender("FILE");
        if (file instanceof FileAppender<ILoggingEvent> fileAppender)
            return fileAppender;
        return null;
    }

    private static ThresholdFilter errorFilter() {
        ThresholdFilter filter = new ThresholdFilter();
        filter.setLevel(Level.ERROR.levelStr);
        filter.start();
        return filter;
    }

    public static void setLogLevel(String level) {
        Logger logger = (Logger) LoggerFactory.getLogger("mousemaster");
        logger.setLevel(Level.valueOf(level));
    }

    /**
     * The mode column is only as wide as the longest mode name of the loaded
     * configuration.
     */
    public static void setModeColumnWidth(int width) {
        modeColumnWidth = width;
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        for (Iterator<Appender<ILoggingEvent>> asyncAppenders =
             rootLogger.iteratorForAppenders(); asyncAppenders.hasNext(); ) {
            for (Iterator<Appender<ILoggingEvent>> appenders =
                 ((AsyncAppender) asyncAppenders.next()).iteratorForAppenders();
                 appenders.hasNext(); ) {
                Appender<ILoggingEvent> appender = appenders.next();
                if (appender instanceof OutputStreamAppender<ILoggingEvent> outputStreamAppender &&
                    outputStreamAppender.getEncoder() instanceof PatternLayoutEncoder encoder)
                    startWithCurrentPattern(encoder);
            }
        }
    }

    private static void startWithCurrentPattern(PatternLayoutEncoder encoder) {
        encoder.stop();
        encoder.setPattern("%d{HH:mm:ss.SSS} %-5level %-" + modeColumnWidth +
                           "X{mode} %-16logger{0} %msg%n");
        encoder.start();
    }

}
