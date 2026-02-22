package org.openhab.binding.hyundaibluelink.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Build-time regression tests that ensure the binding is packaged as a valid
 * OSGi bundle.
 */
@NonNullByDefault
@SuppressWarnings("null")
class OsgiManifestGenerationTest {

    private static final Path MANIFEST_PATH = Path.of("target", "classes", "META-INF", "MANIFEST.MF");

    @Test
    @DisplayName("Bnd generates an OSGi manifest with the expected headers")
    void manifestContainsExpectedHeaders() throws IOException {
        assertTrue(Files.exists(MANIFEST_PATH), "The generated manifest is missing. Run mvn package first.");

        try (InputStream manifestStream = Files.newInputStream(MANIFEST_PATH)) {
            Manifest manifest = new Manifest(manifestStream);
            Attributes main = manifest.getMainAttributes();

            assertEquals("2", main.getValue("Bundle-ManifestVersion"),
                    "Bundle-ManifestVersion must be 2 for OSGi R4+ compatibility");
            assertEquals("org.openhab.binding.hyundaibluelink", main.getValue("Bundle-SymbolicName"),
                    "Unexpected bundle symbolic name detected");
            assertNotNull(main.getValue("Bundle-Version"), "Bundle-Version header must be present");
        }
    }
}
