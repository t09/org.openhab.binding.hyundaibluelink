package org.openhab.binding.hyundaibluelink.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration test that validates the final JAR artifact exposes the required OSGi headers.
 */
class BundlePackagingIT {

    @Test
    @DisplayName("Packaged binding exposes mandatory OSGi headers")
    void jarContainsOsgiManifest() throws IOException {
        String projectVersion = System.getProperty("projectVersion");
        assertNotNull(projectVersion, "projectVersion system property was not provided by Maven failsafe plugin");

        Path jarPath = Path.of("target", "org.openhab.binding.hyundaibluelink-" + projectVersion + ".jar");
        assertTrue(Files.exists(jarPath), () -> "Packaged bundle is missing: " + jarPath.toAbsolutePath());

        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            Manifest manifest = jarFile.getManifest();
            assertNotNull(manifest, "Packaged bundle does not contain a META-INF/MANIFEST.MF");

            Attributes main = manifest.getMainAttributes();
            assertEquals("org.openhab.binding.hyundaibluelink", main.getValue("Bundle-SymbolicName"),
                    "Unexpected Bundle-SymbolicName in packaged bundle");
            assertEquals("2", main.getValue("Bundle-ManifestVersion"),
                    "Packaged bundle must declare Bundle-ManifestVersion 2");
        }
    }
}
