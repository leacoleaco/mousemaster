package mousemaster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BuildInfoTest {

    @Test
    void versionComesFromFilteredApplicationProperties() {
        String version = BuildInfo.version();
        assertNotNull(version);
        assertFalse(version.isBlank());
        assertFalse(version.contains("${"),
                "pom.xml <version> should be filtered into application.properties");
    }

}
