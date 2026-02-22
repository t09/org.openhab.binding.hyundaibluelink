package org.openhab.binding.hyundaibluelink.internal.api;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonObject;

/**
 * Downloads the latest stamp definition used for signing the BlueLink login
 * requests. The file is downloaded once and then cached in the temporary
 * directory in order to avoid repeated network calls.
 */
public class StampProvider {

    private static final Logger logger = LoggerFactory.getLogger(StampProvider.class);

    /** Default URL of the stamp repository. */
    private static final String DEFAULT_STAMP_URL =
            "https://raw.githubusercontent.com/neoPix/bluelinky-stamps/master/hyundai-1eba27d2-9a5b-4eba-8ec7-97eb6c62fb51.v2.json";

    /** Fallback URL of the stamp repository. */
    private static final URI FALLBACK_STAMP_URI =
            URI.create(
                    "https://raw.githubusercontent.com/neoPix/bluelinky-stamps/main/hyundai-1eba27d2-9a5b-4eba-8ec7-97eb6c62fb51.v2.json");

    private final URI stampUri;
    private final Path cacheDir;
    private final URI fallbackUri;
    private final String cacheFileName;

    public StampProvider() {
        this(URI.create(System.getProperty("bluelinky.stampUrl", DEFAULT_STAMP_URL)));
    }

    public StampProvider(URI stampUri) {
        this(stampUri, null);
    }

    StampProvider(URI stampUri, Path cacheDir) {
        this.stampUri = stampUri;
        this.cacheDir = cacheDir != null ? cacheDir : getStampDirectory();
        this.cacheFileName = Paths.get(stampUri.getPath()).getFileName().toString();
        this.fallbackUri = DEFAULT_STAMP_URL.equals(stampUri.toString()) ? FALLBACK_STAMP_URI : null;
    }

    public Path getStampDirectory() {
        return Path.of("/tmp/hyundaibluelink/");
    }

    /**
     * Returns the content of the stamp file. When the file is not yet present in
     * the cache directory it will be downloaded from the configured URL first.
     */
    public String getStamp() throws IOException, InterruptedException {
        Files.createDirectories(cacheDir);
        Path file = cacheDir.resolve(cacheFileName);
        ensureStampFile(file);
        return readStamp(file);
    }

    public String refreshStamp() throws IOException, InterruptedException {
        Files.createDirectories(cacheDir);
        Path file = cacheDir.resolve(cacheFileName);
        refreshStampFile(file);
        return readStamp(file);
    }

    protected void ensureStampFile(Path file) throws IOException, InterruptedException {
        if (!Files.exists(file)) {
            logger.trace("Stamp cache miss at {}. Downloading from {}.", file, stampUri);
            downloadStamp(file);
        } else {
            logger.trace("Using cached stamp file at {}.", file);
        }
    }

    protected void refreshStampFile(Path file) throws IOException, InterruptedException {
        Files.deleteIfExists(file);
        logger.info("Refreshing cached stamp at {} from {}", file, stampUri);
        downloadStamp(file);
    }

    protected String readStamp(Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8).trim();
        String stamp = parseStamp(content);
        logger.trace("Loaded stamp with length {} from {}.", stamp.length(), file);
        return stamp;
    }

    protected void downloadStamp(Path target) throws IOException, InterruptedException {
        try {
            download(target, stampUri);
        } catch (IOException primaryException) {
            logger.warn("Primary stamp download from {} failed: {}", stampUri, messageFor(primaryException));
            if (fallbackUri != null) {
                logger.info("Trying fallback stamp download from {}", fallbackUri);
                try {
                    Files.deleteIfExists(target);
                    download(target, fallbackUri);
                    logger.info("Fallback stamp download from {} succeeded", fallbackUri);
                    return;
                } catch (IOException fallbackException) {
                    logger.warn("Fallback stamp download from {} failed: {}", fallbackUri,
                            messageFor(fallbackException));
                    fallbackException.addSuppressed(primaryException);
                    throw fallbackException;
                }
            }
            throw primaryException;
        }
    }

    protected void download(Path target, URI source) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(source).GET().build();
        HttpResponse<Path> resp = client.send(req, HttpResponse.BodyHandlers.ofFile(target));
        if (resp.statusCode() >= 400) {
            throw new IOException("Failed to download stamp: " + resp.statusCode());
        }
    }

    private String messageFor(IOException exception) {
        String message = exception.getMessage();
        return (message == null || message.isBlank()) ? exception.getClass().getSimpleName() : message;
    }

    private String parseStamp(String content) throws IOException {
        if (content.isEmpty()) {
            throw new IOException("Stamp file is empty");
        }

        try {
            JsonElement parsed = JsonParser.parseString(content);
            if (parsed.isJsonObject()) {
                return parseJsonObject(parsed.getAsJsonObject());
            }
            if (parsed.isJsonArray()) {
                return parseJsonArray(parsed.getAsJsonArray());
            }
            if (parsed.isJsonPrimitive()) {
                JsonPrimitive primitive = parsed.getAsJsonPrimitive();
                if (primitive.isString()) {
                    return primitive.getAsString().trim();
                }
            }
        } catch (JsonParseException e) {
            logger.trace("Failed to parse stamp file as JSON: {}", e.getMessage());
            // fall back to legacy format below
        }

        return stripQuotes(content);
    }

    private String stripQuotes(String value) {
        int length = value.length();
        if (length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, length - 1).trim();
        }
        return value;
    }

    private String parseJsonObject(JsonObject object) throws IOException {
        JsonArray stamps = object.getAsJsonArray("stamps");
        if (stamps == null || stamps.size() == 0) {
            throw new IOException("Stamp JSON object does not contain stamps");
        }

        int index = 0;
        if (object.has("generated") && object.has("frequency")) {
            JsonPrimitive generatedElement = object.getAsJsonPrimitive("generated");
            JsonPrimitive frequencyElement = object.getAsJsonPrimitive("frequency");
            if (generatedElement != null && generatedElement.isString() && frequencyElement != null
                    && frequencyElement.isNumber()) {
                try {
                    Instant generated = Instant.parse(generatedElement.getAsString());
                    long frequency = frequencyElement.getAsLong();
                    if (frequency > 0) {
                        long elapsed = Duration.between(generated, Instant.now()).toMillis();
                        if (elapsed < 0) {
                            elapsed = 0;
                        }
                        long position = elapsed / frequency;
                        if (position >= stamps.size()) {
                            index = stamps.size() - 1;
                        } else {
                            index = (int) position;
                        }
                    }
                } catch (DateTimeParseException | ArithmeticException | UnsupportedOperationException e) {
                    logger.trace("Failed to interpret stamp metadata: {}", e.getMessage());
                    // fall back to index 0
                }
            }
        }

        JsonElement selected = stamps.get(index);
        if (!selected.isJsonPrimitive() || !selected.getAsJsonPrimitive().isString()) {
            throw new IOException("Stamp JSON object entry is not a string");
        }
        return selected.getAsString().trim();
    }

    private String parseJsonArray(JsonArray array) throws IOException {
        if (array.isEmpty()) {
            throw new IOException("Stamp JSON array is empty");
        }
        JsonElement first = array.get(0);
        if (!first.isJsonPrimitive()) {
            throw new IOException("Stamp JSON value is not a primitive");
        }
        return first.getAsString().trim();
    }
}

