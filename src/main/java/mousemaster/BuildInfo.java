package mousemaster;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * Version baked into the build. Change {@code <version>} in {@code pom.xml};
 * the resources plugin writes it to {@code application.properties} as {@code version}.
 */
public final class BuildInfo {

    private static final Properties PROPERTIES = load();

    private BuildInfo() {
    }

    public static String version() {
        return PROPERTIES.getProperty("version");
    }

    public static String commitId() {
        return PROPERTIES.getProperty("commitId");
    }

    private static Properties load() {
        try (InputStream in = BuildInfo.class.getClassLoader()
                                              .getResourceAsStream("application.properties")) {
            Properties properties = new Properties();
            properties.load(in);
            return properties;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
