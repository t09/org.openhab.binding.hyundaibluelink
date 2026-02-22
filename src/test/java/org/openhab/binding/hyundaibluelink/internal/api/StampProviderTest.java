package org.openhab.binding.hyundaibluelink.internal.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link StampProvider}.
 */
@NonNullByDefault
@SuppressWarnings("null")
class StampProviderTest {

    private static final String STAMP_FILE = "hyundai-1eba27d2-9a5b-4eba-8ec7-97eb6c62fb51.v2.json";
    private static final URI PRIMARY_URI = URI.create("https://example.com/" + STAMP_FILE);
    private static final URI DEFAULT_PRIMARY_URI = URI
            .create("https://raw.githubusercontent.com/neoPix/bluelinky-stamps/master/" + STAMP_FILE);
    private static final URI DEFAULT_FALLBACK_URI = URI
            .create("https://raw.githubusercontent.com/neoPix/bluelinky-stamps/main/" + STAMP_FILE);

    @TempDir
    Path tempDir;

    @Test
    void getStampParsesLatestEntryFromJsonArray() throws Exception {
        Map<URI, String> responses = Map.of(PRIMARY_URI, "[\"latestStamp\",\"olderStamp\"]");
        TestableStampProvider provider = new TestableStampProvider(PRIMARY_URI, tempDir, responses);

        String stamp = provider.getStamp();

        assertEquals("latestStamp", stamp);
        assertIterableEquals(List.of(PRIMARY_URI), provider.getDownloadCalls());
    }

    @Test
    void getStampUsesCachedLegacyFormatWithoutDownload() throws Exception {
        Path cachedFile = tempDir.resolve(STAMP_FILE);
        Files.writeString(cachedFile, "legacyStamp\n", StandardCharsets.UTF_8);
        TestableStampProvider provider = new TestableStampProvider(PRIMARY_URI, tempDir, Map.of());

        String stamp = provider.getStamp();

        assertEquals("legacyStamp", stamp);
        assertEquals(0, provider.getDownloadCount());
    }

    @Test
    void getStampParsesV2FormatAndSelectsStampByTime() throws Exception {
        Instant generated = Instant.now().minus(Duration.ofMinutes(31));
        String body = String.format("{\n" + "  \"stamps\": [\"s0\", \"s1\", \"s2\", \"s3\", \"s4\"],\n"
                + "  \"generated\": \"%s\",\n" + "  \"frequency\": 600000\n" + "}\n", generated.toString());

        Map<URI, String> responses = Map.of(PRIMARY_URI, body);
        TestableStampProvider provider = new TestableStampProvider(PRIMARY_URI, tempDir, responses);

        String stamp = provider.getStamp();

        assertEquals("s3", stamp);
    }

    @Test
    void getStampFallsBackToAlternateRepository() throws Exception {
        Map<URI, String> responses = Map.of(DEFAULT_PRIMARY_URI, TestableStampProvider.FAIL_DOWNLOAD,
                DEFAULT_FALLBACK_URI, "[\"fallbackStamp\"]");
        TestableStampProvider provider = new TestableStampProvider(DEFAULT_PRIMARY_URI, tempDir, responses);

        String stamp = provider.getStamp();

        assertEquals("fallbackStamp", stamp);
        assertIterableEquals(List.of(DEFAULT_PRIMARY_URI, DEFAULT_FALLBACK_URI), provider.getDownloadCalls());
    }

    private static class TestableStampProvider extends StampProvider {

        static final String FAIL_DOWNLOAD = "__FAIL__";

        private final Map<URI, String> responses;
        private final List<URI> downloadCalls = new ArrayList<>();
        private final AtomicInteger downloadCount = new AtomicInteger();

        TestableStampProvider(URI stampUri, Path directory, Map<URI, String> responses) {
            super(stampUri, directory);
            this.responses = responses;
        }

        @Override
        protected void download(Path target, URI source) throws IOException {
            downloadCount.incrementAndGet();
            downloadCalls.add(source);

            String body = Objects.requireNonNull(responses.get(source));

            if (FAIL_DOWNLOAD.equals(body)) {
                throw new IOException("Simulated download failure for " + source);
            }

            Files.writeString(target, body, StandardCharsets.UTF_8);
        }

        int getDownloadCount() {
            return downloadCount.get();
        }

        List<URI> getDownloadCalls() {
            return List.copyOf(downloadCalls);
        }
    }
}
