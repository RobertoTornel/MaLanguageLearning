package com.ma.languagelearning.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ma.languagelearning.config.MllConfig;
import com.ma.languagelearning.speech.SpeechResult;
import com.ma.languagelearning.speech.SttClient;
import com.ma.languagelearning.speech.TranslationProvider;
import com.ma.languagelearning.speech.WordPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP client for the local Python speech sidecar.
 *
 * <p>Talks to {@code http://127.0.0.1:<sidecarPort>} and implements both halves of the
 * speech stack: {@link SttClient} (recognition) and {@link TranslationProvider}
 * (transliteration + TTS). Every method here is defensive by contract — the interfaces
 * forbid throwing, so failures are folded into {@link #lastError()} and a neutral
 * fallback value is returned instead. A dead sidecar must never crash the game.</p>
 *
 * <p>Endpoint summary:</p>
 * <ul>
 *   <li>{@code GET  /health} — liveness probe, cached for {@value #HEALTH_TTL_MS} ms</li>
 *   <li>{@code POST /transcribe} — body: WAV, header {@code X-MLL-Language}</li>
 *   <li>{@code POST /transliterate} — JSON {@code {text, language, primary_script, secondary_script}}</li>
 *   <li>{@code POST /synthesize} — JSON {@code {text, language}} → WAV bytes</li>
 * </ul>
 */
public final class SidecarClient implements SttClient, TranslationProvider {

    private static final Logger LOG = LoggerFactory.getLogger("malanguagelearning");

    /** Size cap of the resolve cache; the two-translation lookups are cheap but chatty. */
    private static final int CACHE_CAPACITY = 512;

    /** How long a {@code /health} answer is trusted before the next probe. */
    private static final long HEALTH_TTL_MS = 5_000L;

    private static final int HEALTH_TIMEOUT_MS = 1_500;
    private static final int TRANSCRIBE_TIMEOUT_MS = 60_000;
    private static final int TRANSLITERATE_TIMEOUT_MS = 20_000;
    private static final int SYNTHESIZE_TIMEOUT_MS = 45_000;

    /** The sidecar decodes 16 kHz mono 16-bit PCM; this is what we wrap before upload. */
    private static final int WAV_SAMPLE_RATE = 16_000;
    private static final int WAV_CHANNELS = 1;
    private static final int WAV_BITS = 16;

    private final Map<String, WordPair> cache = lruCache(CACHE_CAPACITY);
    private final String baseUrl;

    private volatile String lastError;
    private volatile long healthCheckedAt;
    private volatile boolean healthCached;

    public SidecarClient() {
        this(MllConfig.get());
    }

    public SidecarClient(MllConfig config) {
        int port = config != null ? config.sidecarPort : 8737;
        this.baseUrl = "http://127.0.0.1:" + port;
    }

    // ------------------------------------------------------------------ SttClient

    @Override
    public SpeechResult transcribe(byte[] pcm16kMono, String language) {
        String lang = language == null ? "en" : language;
        if (pcm16kMono == null || pcm16kMono.length == 0) {
            return fail("no audio captured", SpeechResult.empty(lang));
        }
        try {
            byte[] wav = buildWav(pcm16kMono, WAV_SAMPLE_RATE, WAV_CHANNELS, WAV_BITS);
            JsonObject response = HttpJson.postBytesForJson(
                    baseUrl + "/transcribe",
                    wav,
                    HttpJson.AUDIO_CONTENT_TYPE,
                    lang,
                    TRANSCRIBE_TIMEOUT_MS);

            String text = optString(response, "text", "");
            String model = optString(response, "model", "unknown");
            String usedLanguage = optString(response, "language", lang);
            float confidence = (float) optDouble(response, "confidence", -1d);
            long latency = optLong(response, "latency_ms", 0L);

            lastError = null;
            return new SpeechResult(text, confidence, usedLanguage, model, latency);
        } catch (Exception e) {
            return fail("transcribe failed: " + messageOf(e), SpeechResult.empty(lang));
        }
    }

    @Override
    public String lastError() {
        return lastError;
    }

    @Override
    public boolean isAvailable() {
        long now = System.currentTimeMillis();
        if (healthCheckedAt != 0L && now - healthCheckedAt < HEALTH_TTL_MS) {
            return healthCached;
        }
        boolean healthy;
        try {
            JsonObject health = HttpJson.getJson(baseUrl + "/health", HEALTH_TIMEOUT_MS);
            // Some builds report readiness in the body; anything parseable counts as up.
            String status = optString(health, "status", "ok");
            healthy = !"error".equalsIgnoreCase(status) && !"down".equalsIgnoreCase(status);
        } catch (Exception e) {
            healthy = false;
            lastError = "sidecar unavailable: " + messageOf(e);
        }
        healthCached = healthy;
        healthCheckedAt = now;
        return healthy;
    }

    // ------------------------------------------------------- TranslationProvider

    @Override
    public WordPair resolve(String sourceText, String language, String primaryScript, String secondaryScript) {
        String text = sourceText == null ? "" : sourceText;
        if (text.isBlank()) {
            return WordPair.raw("");
        }
        String lang = language == null ? "en" : language;
        String primary = primaryScript == null ? "plain" : primaryScript;
        String secondary = secondaryScript == null ? "native" : secondaryScript;

        String key = text + '|' + lang + '|' + primary + '|' + secondary;
        WordPair cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        try {
            JsonObject body = new JsonObject();
            body.addProperty("text", text);
            body.addProperty("language", lang);
            body.addProperty("primary_script", primary);
            body.addProperty("secondary_script", secondary);

            JsonObject response = HttpJson.postJson(
                    baseUrl + "/transliterate", body, TRANSLITERATE_TIMEOUT_MS);

            String primaryText = optString(response, "primary", text);
            String secondaryText = optString(response, "secondary", primaryText);
            String phonetic = optString(response, "phonetic", "");
            String script = optString(response, "script", primary);
            String nativeText = optString(response, "native", "");
            Map<String, String> variants = readVariants(response);

            WordPair pair = new WordPair(primaryText, secondaryText, phonetic, script, variants, nativeText);
            cache.put(key, pair);
            lastError = null;
            return pair;
        } catch (Exception e) {
            return fail("transliterate failed: " + messageOf(e), WordPair.raw(text));
        }
    }

    @Override
    public byte[] synthesize(String text, String language) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            JsonObject body = new JsonObject();
            body.addProperty("text", text);
            body.addProperty("language", language == null ? "en" : language);
            byte[] wav = HttpJson.postJsonForBytes(
                    baseUrl + "/synthesize", body, SYNTHESIZE_TIMEOUT_MS);
            if (wav == null || wav.length <= 44) {
                return fail("synthesize returned no audio", null);
            }
            lastError = null;
            return wav;
        } catch (Exception e) {
            return fail("synthesize failed: " + messageOf(e), null);
        }
    }

    // ------------------------------------------------------------------ utilities

    /** Invalidate the cached health answer, e.g. right after launching the sidecar. */
    public void invalidateHealth() {
        healthCheckedAt = 0L;
        healthCached = false;
    }

    /** Drop every memoised transliteration. */
    public void clearCache() {
        cache.clear();
    }

    /** The base URL this client targets, for logging and diagnostics. */
    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public void close() {
        cache.clear();
        HttpJson.shutdown();
    }

    /**
     * Wrap raw little-endian PCM in a canonical 44-byte RIFF/WAVE header.
     *
     * <p>The sidecar's {@code /transcribe} endpoint takes a real WAV file, not a bare PCM
     * blob, so every recording is framed before upload. This is a plain utility with no
     * state and no I/O, hence {@code static}.</p>
     *
     * @param pcm16         raw samples, little-endian
     * @param sampleRate    samples per second, typically 16000
     * @param channels      1 for mono, 2 for stereo
     * @param bitsPerSample 16 for signed 16-bit PCM
     * @return a complete WAV file image
     */
    public static byte[] buildWav(byte[] pcm16, int sampleRate, int channels, int bitsPerSample) {
        byte[] pcm = pcm16 == null ? new byte[0] : pcm16;
        int frames = Math.max(1, channels);
        int bits = Math.max(8, bitsPerSample);
        int rate = Math.max(1, sampleRate);
        int blockAlign = frames * (bits / 8);
        int byteRate = rate * blockAlign;
        // RIFF chunk size = 36 + data length; clamp so a huge buffer cannot overflow the
        // unsigned 32-bit field and produce an unparseable header.
        long riffSize = 36L + pcm.length;

        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + pcm.length);
        writeAscii(out, "RIFF");
        writeIntLE(out, (int) Math.min(0xFFFFFFFFL, riffSize));
        writeAscii(out, "WAVE");
        writeAscii(out, "fmt ");
        writeIntLE(out, 16);              // PCM fmt chunk size
        writeShortLE(out, 1);             // format tag: 1 = PCM
        writeShortLE(out, frames);
        writeIntLE(out, rate);
        writeIntLE(out, byteRate);
        writeShortLE(out, blockAlign);
        writeShortLE(out, bits);
        writeAscii(out, "data");
        writeIntLE(out, pcm.length);
        out.write(pcm, 0, pcm.length);
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ internals

    private <T> T fail(String message, T fallback) {
        lastError = message;
        LOG.debug("[{}] {}", "malanguagelearning", message);
        return fallback;
    }

    private static String messageOf(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }

    private static Map<String, String> readVariants(JsonObject response) {
        JsonElement element = response.get("alternatives");
        if (element == null || !element.isJsonObject()) {
            return Map.of();
        }
        Map<String, String> variants = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            JsonElement value = entry.getValue();
            if (value != null && value.isJsonPrimitive()) {
                variants.put(entry.getKey(), value.getAsString());
            }
        }
        return variants;
    }

    private static String optString(JsonObject object, String member, String fallback) {
        JsonElement element = object.get(member);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            String value = element.getAsString();
            return value == null || value.isEmpty() ? fallback : value;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static double optDouble(JsonObject object, String member, double fallback) {
        JsonElement element = object.get(member);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsDouble();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static long optLong(JsonObject object, String member, long fallback) {
        JsonElement element = object.get(member);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsLong();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static void writeAscii(ByteArrayOutputStream out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        out.write(bytes, 0, bytes.length);
    }

    private static void writeIntLE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    private static void writeShortLE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    /**
     * A bounded insertion-ordered map used as an LRU. Access-ordered
     * {@link LinkedHashMap} removes the eldest entry once the capacity is exceeded; every
     * method is synchronised so the instance is safe across the game and HTTP threads.
     */
    private static Map<String, WordPair> lruCache(int capacity) {
        final int max = Math.max(1, capacity);
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, WordPair> eldest) {
                return size() > max;
            }

            @Override
            public WordPair get(Object key) {
                synchronized (this) {
                    return super.get(key);
                }
            }

            @Override
            public WordPair put(String key, WordPair value) {
                synchronized (this) {
                    return super.put(key, value);
                }
            }

            @Override
            public void clear() {
                synchronized (this) {
                    super.clear();
                }
            }
        };
    }
}
