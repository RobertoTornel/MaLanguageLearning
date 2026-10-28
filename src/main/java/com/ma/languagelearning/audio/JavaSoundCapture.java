package com.ma.languagelearning.audio;

import com.ma.languagelearning.MaLanguageLearning;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;

/**
 * Microphone capture built on the JDK's Java Sound API, so it needs no native libraries
 * and works on Windows, macOS and Linux out of the box.
 *
 * <p>Records 16 kHz, mono, 16-bit signed little-endian PCM — exactly what the sidecar
 * expects. Recording runs on its own daemon thread; {@link #stop()} blocks until the
 * buffer is complete.</p>
 */
public final class JavaSoundCapture implements AudioCapture {

    private static final float SAMPLE_RATE = 16_000f;
    private static final int SAMPLE_BITS = 16;
    private static final int CHANNELS = 1;
    private static final int MAX_SECONDS = 20;
    /** Amplitude above which a chunk counts as speech (16-bit full scale is 32767). */
    private static final int SPEECH_THRESHOLD = 900;
    /** Seconds of trailing quiet that end a take. */
    private static final float SILENCE_SECONDS = 1.0f;
    private final AudioFormat format = new AudioFormat(SAMPLE_RATE, SAMPLE_BITS, CHANNELS, true, false);

    private volatile TargetDataLine line;
    private volatile Thread worker;
    private volatile ByteArrayOutputStream buffer;
    private volatile boolean recording;
    private volatile String lastError;
    /** Max recording seconds for the current capture, set by start(). */
    private volatile int maxSeconds = MAX_SECONDS;
    /** Peak amplitude of the last capture (16-bit full scale = 32767). */
    private volatile int peakAmplitude;
    /**
     * Set by the pump thread once it has heard speech and then a run of silence, meaning
     * the player has finished saying the word. ChallengeManager checks this to stop early
     * instead of waiting out the full answer window.
     */
    private volatile boolean speechEnded;

    @Override
    public boolean start(int maxSeconds) {
        if (recording) {
            return true;
        }
        this.maxSeconds = Math.max(1, Math.min(maxSeconds, MAX_SECONDS));
        lastError = null;
        buffer = new ByteArrayOutputStream((int) (SAMPLE_RATE * SAMPLE_BITS / 8 * CHANNELS * this.maxSeconds));
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) {
                // The requested 16 kHz format may not be natively supported by the default
                // mixer (e.g. 48 kHz-only Windows devices, some Linux/PipeWire setups). Try
                // opening anyway: the sidecar resamples to 16 kHz, so any PCM rate works.
                try {
                    TargetDataLine newLine = (TargetDataLine) AudioSystem.getLine(info);
                    newLine.open(format);
                    newLine.start();
                    this.line = newLine;
                    this.recording = true;

                    Thread t = new Thread(this::pump, "MaLanguageLearning-Mic");
                    t.setDaemon(true);
                    this.worker = t;
                    t.start();
                    return true;
                } catch (Exception fallbackFailed) {
                    lastError = fallbackFailed.getClass().getSimpleName() + ": " + fallbackFailed.getMessage();
                    MaLanguageLearning.LOGGER.warn("[{}] microphone unavailable: {}", MaLanguageLearning.MODID, lastError);
                    cleanup();
                    return false;
                }
            }
            TargetDataLine newLine = (TargetDataLine) AudioSystem.getLine(info);
            newLine.open(format);
            newLine.start();
            this.line = newLine;
            this.recording = true;

            Thread t = new Thread(this::pump, "MaLanguageLearning-Mic");
            t.setDaemon(true);
            this.worker = t;
            t.start();
            return true;
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            MaLanguageLearning.LOGGER.warn("[{}] microphone unavailable: {}", MaLanguageLearning.MODID, lastError);
            cleanup();
            return false;
        }
    }

    private void pump() {
        byte[] chunk = new byte[4096];
        int maxBytes = (int) (SAMPLE_RATE * (SAMPLE_BITS / 8) * CHANNELS * maxSeconds);
        // Voice activity: once we've heard speech and then ~1s of quiet, the player has
        // finished saying the word. Stop early so the answer is checked right away instead
        // of waiting out the full answer window.
        boolean heardSpeech = false;
        int quietChunks = 0;
        try {
            TargetDataLine l = this.line;
            while (recording && l != null && l.isOpen()) {
                int read = l.read(chunk, 0, chunk.length);
                if (read <= 0) {
                    continue;
                }
                ByteArrayOutputStream b = this.buffer;
                if (b == null) {
                    break;
                }
                if (b.size() + read >= maxBytes) {
                    int room = maxBytes - b.size();
                    if (room > 0) {
                        b.write(chunk, 0, room);
                    }
                    break;
                }
                b.write(chunk, 0, read);
                int peak = peakOf(chunk, read);
                if (peak > peakAmplitude) {
                    peakAmplitude = peak;
                }
                if (peak > SPEECH_THRESHOLD) {
                    heardSpeech = true;
                    quietChunks = 0;
                } else if (heardSpeech) {
                    quietChunks++;
                    if (quietChunks * chunk.length >= (int) (SAMPLE_RATE * (SAMPLE_BITS / 8) * CHANNELS * SILENCE_SECONDS)) {
                        speechEnded = true;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            if (recording) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        } finally {
            recording = false;
        }
    }

    /** True once speech was heard and then followed by enough silence to stop. */
    public boolean speechEnded() {
        return speechEnded;
    }

    @Override
    public int peakAmplitude() {
        return peakAmplitude;
    }

    @Override
    public boolean isRecording() {
        return recording;
    }

    @Override
    public byte[] stop() {
        // Order matters: join the pump *before* clearing the flag, so it can finish reading
        // the audio the line already buffered. Clearing first made the loop exit instantly
        // and threw away everything after the last chunk boundary, which is why short
        // answers came back as a few hundred bytes and never reached the recogniser.
        Thread t = this.worker;
        try {
            TargetDataLine l = this.line;
            if (l != null) {
                try {
                    l.stop();
                } catch (Exception ignored) {
                }
            }
            if (t != null && t != Thread.currentThread()) {
                t.join(1500);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        recording = false;
        ByteArrayOutputStream b = this.buffer;
        byte[] out = b == null ? new byte[0] : b.toByteArray();
        cleanup();
        return out;
    }

    @Override
    public void cancel() {
        recording = false;
        ByteArrayOutputStream b = this.buffer;
        if (b != null) {
            b.reset();
        }
        cleanup();
    }

    @Override
    public String lastError() {
        return lastError;
    }

    @Override
    public void close() {
        cancel();
    }

    private void cleanup() {
        try {
            TargetDataLine l = this.line;
            if (l != null && l.isOpen()) {
                l.close();
            }
        } catch (Exception ignored) {
        }
        this.line = null;
        this.worker = null;
        this.buffer = null;
    }

    /** True when the system reports at least one usable recording device. */
    public static boolean microphonePresent() {
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class,
                    new AudioFormat(SAMPLE_RATE, SAMPLE_BITS, CHANNELS, true, false));
            return AudioSystem.isLineSupported(info);
        } catch (Exception e) {
            return false;
        }
    }

    /** Peak amplitude of a 16-bit little-endian PCM buffer. */
    private static int peakOf(byte[] data, int length) {
        int peak = 0;
        for (int i = 0; i + 1 < length; i += 2) {
            int sample = (short) ((data[i] & 0xFF) | (data[i + 1] << 8));
            int magnitude = sample < 0 ? -sample : sample;
            if (magnitude > peak) {
                peak = magnitude;
            }
        }
        return peak;
    }
}
