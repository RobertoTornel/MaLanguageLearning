package com.ma.languagelearning.net;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Thin blocking JSON/HTTP helper over the JDK's built-in {@link HttpClient}.
 *
 * <p>No third-party transport: everything here is {@code java.net.http} plus Google Gson
 * (which Minecraft already ships). The client is created once and shared, on daemon
 * threads, so nothing here can keep the game's JVM alive after the window closes.</p>
 *
 * <p>Every method that talks to a server throws {@link IOException} on any transport
 * problem <em>and</em> on a non-2xx response, with the response body folded into the
 * message so the caller can log something useful.</p>
 */
public final class HttpJson {

    /** Header the sidecar uses to select the speech model / language. */
    public static final String LANGUAGE_HEADER = "X-MLL-Language";

    /** Content type used for every JSON request body. */
    public static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";

    /** Content type the sidecar expects for a raw WAV upload. */
    public static final String AUDIO_CONTENT_TYPE = "audio/wav";

    /** Content type used for opaque binary bodies. */
    public static final String BINARY_CONTENT_TYPE = "application/octet-stream";

    private static final Gson GSON = new Gson();
    private static final int DEFAULT_TIMEOUT_MS = 10_000;

    /** How much of a failing response body is quoted back in the exception message. */
    private static final int MAX_ERROR_BODY = 2_048;

    private static volatile HttpClient client;
    private static volatile ExecutorService executor;

    private HttpJson() {
    }

    // ------------------------------------------------------------------ public API

    /**
     * POST a JSON object and parse a JSON object back.
     *
     * @throws IOException on transport failure or a non-2xx status
     */
    public static JsonObject postJson(String url, JsonObject body, int timeoutMs) throws IOException {
        return parseObject(url, postJsonForBytes(url, body, timeoutMs));
    }

    /**
     * GET a URL and parse a JSON object back.
     *
     * @throws IOException on transport failure or a non-2xx status
     */
    public static JsonObject getJson(String url, int timeoutMs) throws IOException {
        HttpRequest request = base(url, timeoutMs)
                .header("Accept", "application/json")
                .GET()
                .build();
        return parseObject(url, send(url, request, HttpResponse.BodyHandlers.ofByteArray()));
    }

    /**
     * POST a JSON object and return the raw response bytes (used for {@code /synthesize},
     * which answers with a WAV file rather than JSON).
     *
     * @throws IOException on transport failure or a non-2xx status
     */
    public static byte[] postJsonForBytes(String url, JsonObject body, int timeoutMs) throws IOException {
        byte[] payload = encode(body);
        return postBytes(url, payload, JSON_CONTENT_TYPE, null, timeoutMs);
    }

    /**
     * POST opaque bytes and parse a JSON object back (used for {@code /transcribe}, which
     * takes a WAV upload).
     *
     * @param language value for {@link #LANGUAGE_HEADER}, or {@code null} to omit the header
     * @throws IOException on transport failure or a non-2xx status
     */
    public static JsonObject postBytesForJson(String url, byte[] body, String language, int timeoutMs) throws IOException {
        return postBytesForJson(url, body, BINARY_CONTENT_TYPE, language, timeoutMs);
    }

    /**
     * POST opaque bytes with an explicit content type and parse a JSON object back.
     *
     * @throws IOException on transport failure or a non-2xx status
     */
    public static JsonObject postBytesForJson(String url,
                                              byte[] body,
                                              String contentType,
                                              String language,
                                              int timeoutMs) throws IOException {
        byte[] response = postBytes(url, body, contentType, language, timeoutMs);
        return parseObject(url, response);
    }

    /**
     * POST opaque bytes and return the raw response bytes.
     *
     * @param contentType  request content type; {@link #JSON_CONTENT_TYPE} when {@code null}
     * @param language     value for {@link #LANGUAGE_HEADER}, or {@code null} to omit it
     * @throws IOException on transport failure or a non-2xx status
     */
    public static byte[] postBytes(String url,
                                   byte[] raw,
                                   String contentType,
                                   String language,
                                   int timeoutMs) throws IOException {
        byte[] payload = raw == null ? new byte[0] : raw;
        HttpRequest.Builder builder = base(url, timeoutMs)
                .header("Content-Type", contentType == null ? JSON_CONTENT_TYPE : contentType)
                .header("Accept", "application/json, audio/wav, */*")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        if (language != null && !language.isBlank()) {
            builder.header(LANGUAGE_HEADER, language);
        }
        return send(url, builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    /**
     * Parse a UTF-8 JSON object out of a response body. An empty body yields an empty object
     * rather than an exception, because several sidecar endpoints answer {@code 204}.
     */
    public static JsonObject parseObject(byte[] response) throws IOException {
        if (response == null || response.length == 0) {
            return new JsonObject();
        }
        return parseObject("<response>", response);
    }

    /** Shut the shared client down. Safe to call at any time; it is rebuilt on next use. */
    public static void shutdown() {
        ExecutorService pool = executor;
        executor = null;
        client = null;
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ internals

    private static JsonObject parseObject(String url, byte[] response) throws IOException {
        if (response == null || response.length == 0) {
            return new JsonObject();
        }
        String text = new String(response, StandardCharsets.UTF_8);
        try {
            JsonElement element = JsonParser.parseString(text);
            if (element != null && element.isJsonObject()) {
                return element.getAsJsonObject();
            }
            throw new IOException(url + " answered with JSON that is not an object: " + abbreviate(text));
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException(url + " answered with malformed JSON: " + abbreviate(text), e);
        }
    }

    private static byte[] encode(JsonObject body) {
        if (body == null) {
            return new byte[] {'{', '}'};
        }
        return GSON.toJson(body, JsonElement.class).getBytes(StandardCharsets.UTF_8);
    }

    private static HttpRequest.Builder base(String url, int timeoutMs) throws IOException {
        if (url == null || url.isBlank()) {
            throw new IOException("empty request URL");
        }
        int timeout = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        try {
            return HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeout))
                    .header("User-Agent", "malanguagelearning/0.1.0");
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid request URL: " + url, e);
        }
    }

    private static <T> T send(String url, HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
        HttpResponse<T> response;
        try {
            response = client().send(request, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while calling " + url);
        } catch (IOException e) {
            throw new IOException("cannot reach " + url + ": " + describe(e.getMessage()), e);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException(url + " answered HTTP " + status + ": " + describe(response.body()));
        }
        return response.body();
    }

    private static String describe(Object body) {
        if (body == null) {
            return "<empty>";
        }
        if (body instanceof byte[] bytes) {
            return abbreviate(new String(bytes, StandardCharsets.UTF_8));
        }
        return abbreviate(String.valueOf(body));
    }

    /**
     * A transport failure's message. {@code ConnectException} and friends routinely carry a
     * null message, so fall back to the exception type rather than printing "null".
     */
    private static String describeError(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        String message = error.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        Throwable cause = error.getCause();
        if (cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()) {
            return cause.getMessage();
        }
        return error.getClass().getSimpleName();
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "<empty>";
        }
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return "<empty>";
        }
        return trimmed.length() <= MAX_ERROR_BODY ? trimmed : trimmed.substring(0, MAX_ERROR_BODY) + "…";
    }

    private static HttpClient client() {
        HttpClient existing = client;
        if (existing != null) {
            return existing;
        }
        synchronized (HttpJson.class) {
            if (client == null) {
                ThreadFactory factory = runnable -> {
                    Thread thread = new Thread(runnable, "malanguagelearning-http");
                    thread.setDaemon(true);
                    return thread;
                };
                ExecutorService pool = Executors.newCachedThreadPool(factory);
                executor = pool;
                client = HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofSeconds(10))
                        .executor(pool)
                        .build();
            }
            return client;
        }
    }
}
