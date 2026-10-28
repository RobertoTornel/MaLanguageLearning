package com.ma.languagelearning.game;

import com.ma.languagelearning.audio.AudioCapture;
import com.ma.languagelearning.audio.JavaSoundCapture;
import com.ma.languagelearning.config.MllConfig;
import com.ma.languagelearning.speech.SpeechResult;
import com.ma.languagelearning.speech.SttClient;
import com.ma.languagelearning.speech.TranslationProvider;
import com.ma.languagelearning.speech.WordPair;
import com.ma.languagelearning.ui.ChallengeSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The learning-challenge state machine.
 *
 * <pre>
 * IDLE -> WAITING_INVENTORY_CLOSE -> LISTENING -> EVALUATING -> PASSED / FAILED
 * </pre>
 *
 * <p>A challenge is queued when the acquisition tracker sees a new item. Closing the
 * inventory arms the countdown and starts the microphone. When the timer runs out, or the
 * player stops talking, the recording is transcribed on a daemon thread; the result is
 * handed back to the client thread through a {@link ConcurrentLinkedQueue} and evaluated
 * there, so all state transitions happen on the tick thread and never on a worker.</p>
 *
 * <p>Failing speaks the word through the sidecar's TTS and drops the stack on the floor.</p>
 */
public final class ChallengeManager {

    private static final Logger LOG = LoggerFactory.getLogger("malanguagelearning");

    /** Challenge lifecycle. */
    public enum State {
        IDLE,
        WAITING_INVENTORY_CLOSE,
        LISTENING,
        EVALUATING,
        PASSED,
        FAILED
    }

    /** A queued challenge: which item, which word, and how it is displayed. */
    public record Pending(String itemKey, String word, WordPair pair, int inventorySlot) {
    }

    /** Result of a worker-thread transcription, handed back to the tick thread. */
    private record SttCompletion(Pending pending, SpeechResult result) {
    }

    /** Result of a worker-thread translation, handed back to the tick thread. */
    private record PairCompletion(String word, WordPair pair) {
    }

    /**
     * Hard cap on waiting challenges. Kept small on purpose: a stack pickup can create
     * several new items at once, and a long queue turned a minor event into minutes of
     * interrogation. Anything beyond this arrives on the next pickup instead.
     */
    private static final int MAX_QUEUE = 3;
    /** Minimum recording length before a silence stop is honoured, in ticks. */
    private static final int MIN_RECORD_TICKS = 20;
    /** How long the PASSED/FAILED result stays on screen, in ticks. */
    private static final int RESULT_HOLD_TICKS = 40;

    /** Ticks a pass stays on screen: about 1.2 seconds, long enough to register. */
    private static final int PASS_HOLD_TICKS = 24;
    /** Delay between the TTS prompt finishing and the drop, in ticks. */
    private static final int DROP_DELAY_TICKS = 10;

    /** Extra ticks the failed result stays on screen *after* the drop has run. */
    private static final int RESULT_TAIL_TICKS = 20;

    /** Minimum peak amplitude (16-bit full scale = 32767) below which a capture is treated
     *  as silence rather than a spoken answer. Silence must not drop the item. */
    private static final int SILENCE_PEAK_THRESHOLD = 500;
    /** Minimum capture length (~1 second at 16 kHz 16-bit mono) below which the take is
     *  treated as "no answer" rather than a wrong answer. */
    private static final int MIN_CAPTURE_BYTES = 32_000;

    private final LearnedLedger ledger;
    private final ItemNameResolver resolver;
    private final SttClient stt;
    private final TranslationProvider translations;
    private final AudioCapture capture;

    private final Deque<Pending> queue = new ArrayDeque<>();
    /** Wall-clock millis before which no new challenge may start (global breathing room). */
    private long nextStartAtMillis;
    /** Per-item last-asked time, so one item cannot monopolise the flow. */
    private final Map<String, Long> itemAskedAt = new HashMap<>();
    private final ConcurrentLinkedQueue<SttCompletion> completions = new ConcurrentLinkedQueue<>();
    /** Translations arrive asynchronously; the challenge shows the raw word until then. */
    private final ConcurrentLinkedQueue<PairCompletion> pairCompletions = new ConcurrentLinkedQueue<>();
    /** Words already handed to the translation backend, so the pair is resolved once. */
    private final Map<String, WordPair> pairCache = new HashMap<>();
    /** Words currently being translated, to avoid duplicate in-flight requests. */
    private final Set<String> pairInFlight = new HashSet<>();

    private State state = State.IDLE;
    private ChallengeOutcome outcome = ChallengeOutcome.CANCELLED;
    private Pending current;

    /** Countdown, in ticks. */
    private int remainingTicks;
    /** Ticks elapsed in the current LISTENING phase. */
    private int recordTicks;
    /** Ticks left on the result screen before returning to IDLE. */
    private int holdTicks;
    /** Ticks left before the failing stack is dropped. */
    private int dropTicks;

    private float lastConfidence = -1f;
    private boolean recordingStarted;
    private boolean sttInFlight;
    private boolean cancelRequested;
    private boolean dropPending;
    /** Ticks spent waiting on the recogniser, so a slow first load can be explained. */
    private int evaluatingTicks;
    /** True when the last result was a skip rather than a genuine pass. */
    private boolean skipped;
    /** Registry id of the challenge that just failed, kept until the drop runs. */
    private String failedItemKey;

    /** Copy of the stack being challenged, kept so the HUD can draw its icon. */
    private ItemStack currentStack = ItemStack.EMPTY;

    /** Currently playing TTS clip, so rapid speakAsync calls don't overlap or leak lines. */
    private Clip activeTtsClip;
    /** Last microphone error message, shown on the overlay when capture fails. */
    private String micError;

    /** Stack copies keyed by registry id, so the HUD can draw an icon per challenge. */
    private final Map<String, ItemStack> pendingStacks = new HashMap<>();

    public ChallengeManager(LedgerStore ledger,
                            ItemNameResolver resolver,
                            SttClient stt,
                            TranslationProvider translations,
                            AudioCapture capture) {
        this.ledger = ledger;
        this.resolver = resolver;
        this.stt = stt;
        this.translations = translations;
        this.capture = capture;
    }

    // ------------------------------------------------------------------ public API

    /**
     * Queue a newly acquired item as a pending challenge.
     *
     * <p>The display pair is resolved (and cached) here through the injected
     * {@link TranslationProvider}, so callers only need the word and the stack.</p>
     *
     * <p>Only one challenge runs at a time; extras wait in a queue capped at
     * {@value #MAX_QUEUE}. Duplicates and already-learned words are dropped silently.</p>
     */
    public void queue(String itemKey, String word, ItemStack stack) {
        if (itemKey == null || itemKey.isBlank() || word == null || word.isBlank()) {
            return;
        }
        MllConfig cfg = MllConfig.get();
        if (!cfg.enabled) {
            return;
        }
        if (ledger.isLearned(itemKey)) {
            return;
        }
        for (Pending pending : queue) {
            if (pending.itemKey().equals(itemKey)) {
                return;
            }
        }
        if (current != null && current.itemKey().equals(itemKey)) {
            return;
        }
        // Per-item cooldown: an item that was asked very recently is not asked again yet.
        long now = System.currentTimeMillis();
        Long askedAt = itemAskedAt.get(itemKey);
        int itemCooldownMs = Math.max(0, cfg.itemCooldownSeconds) * 1000;
        if (askedAt != null && itemCooldownMs > 0 && now - askedAt < itemCooldownMs) {
            LOG.debug("[malanguagelearning] {} is on cooldown, not queueing", itemKey);
            return;
        }

        if (queue.size() >= MAX_QUEUE) {
            LOG.debug("[malanguagelearning] challenge queue full, dropping {}", itemKey);
            return;
        }

        int slot = slotOf(stack);
        WordPair pair = resolvePair(word);
        queue.addLast(new Pending(itemKey, word, pair, slot));
        if (stack != null && !stack.isEmpty()) {
            pendingStacks.put(itemKey, stack.copy());
        }
        LOG.debug("[malanguagelearning] queued challenge for {} ({} waiting)", itemKey, queue.size());

        if (state == State.IDLE) {
            state = State.WAITING_INVENTORY_CLOSE;
        }
    }

    /**
     * Resolve the two-script rendering without blocking the tick thread.
     *
     * <p>Returns a cached pair when one exists, otherwise kicks off a daemon lookup and
     * returns the plain word; the real pair is swapped in by {@link #drainPairCompletions()}
     * as soon as the backend answers.</p>
     */
    private WordPair resolvePair(String word) {
        if (word == null || word.isBlank()) {
            return WordPair.raw(word == null ? "" : word);
        }
        WordPair cached = pairCache.get(word);
        if (cached != null) {
            return cached;
        }
        if (translations == null || pairInFlight.contains(word)) {
            return WordPair.raw(word);
        }

        pairInFlight.add(word);
        Thread worker = new Thread(() -> {
            MllConfig cfg = MllConfig.get();
            WordPair pair;
            try {
                pair = translations.resolve(word, cfg.targetLanguage, cfg.primaryScript, cfg.secondaryScript);
                if (pair == null || pair.isEmpty()) {
                    pair = WordPair.raw(word);
                }
            } catch (Exception e) {
                LOG.warn("[malanguagelearning] translation failed for '{}', using raw word", word, e);
                pair = WordPair.raw(word);
            }
            pairCompletions.add(new PairCompletion(word, pair));
        }, "MaLanguageLearning-Translate");
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (Exception e) {
            pairInFlight.remove(word);
            LOG.debug("[malanguagelearning] could not start translation worker", e);
        }
        return WordPair.raw(word);
    }

    /**
     * Swap freshly translated pairs into the running or queued challenge.
     *
     * <p>Only the display form changes; the word that is evaluated was fixed when the
     * challenge was queued.</p>
     */
    private void drainPairCompletions() {
        PairCompletion completion;
        while ((completion = pairCompletions.poll()) != null) {
            String word = completion.word();
            pairInFlight.remove(word);
            pairCache.put(word, completion.pair());

            if (current != null && current.word().equals(word)) {
                current = new Pending(current.itemKey(), current.word(), completion.pair(), current.inventorySlot());
            }
            if (!queue.isEmpty()) {
                List<Pending> updated = new ArrayList<>(queue.size());
                boolean changed = false;
                for (Pending pending : queue) {
                    if (pending.word().equals(word)) {
                        updated.add(new Pending(pending.itemKey(), pending.word(),
                                completion.pair(), pending.inventorySlot()));
                        changed = true;
                    } else {
                        updated.add(pending);
                    }
                }
                if (changed) {
                    queue.clear();
                    queue.addAll(updated);
                }
            }
        }
    }

    /**
     * The inventory index of a stack, used only for diagnostics and for the fallback when
     * the failing item can no longer be located by registry id.
     */
    /** Adopt the queued stack copy for {@code pending} so the HUD can draw its icon. */
    private void takeIconFor(Pending pending) {
        ItemStack copy = pendingStacks.remove(pending.itemKey());
        currentStack = copy == null ? ItemStack.EMPTY : copy;
    }

    private static int slotOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return -1;
        }
        try {
            var player = Minecraft.getInstance();
            if (player == null || player.player == null) {
                return -1;
            }
            var inventory = player.player.getInventory();
            String key = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            for (int i = 0; i < 36; i++) {
                ItemStack candidate = inventory.getItem(i);
                if (candidate == null || candidate.isEmpty()) {
                    continue;
                }
                if (BuiltInRegistries.ITEM.getKey(candidate.getItem()).toString().equals(key)) {
                    return i;
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /**
     * The inventory was closed: start the countdown and, if the player is still in a world,
     * begin recording.
     */
    public void onInventoryClosed() {
        try {
            MllConfig cfg = MllConfig.get();
            if (!cfg.enabled) {
                return;
            }
            if (state == State.PASSED || state == State.FAILED) {
                // The result is still on screen; the next challenge waits its turn.
                return;
            }
            if (state == State.LISTENING || state == State.EVALUATING) {
                return;
            }
            if (queue.isEmpty() && current == null) {
                state = State.IDLE;
                return;
            }
            if (current == null) {
                current = queue.pollFirst();
            }
            if (current == null) {
                state = State.IDLE;
                return;
            }
            // Global cooldown: a short quiet gap so a burst of pickups does not turn into a
            // wall of back-to-back prompts.
            MllConfig cfg2 = MllConfig.get();
            long nowMillis = System.currentTimeMillis();
            if (nextStartAtMillis > nowMillis) {
                // Re-check on the next tick instead of blocking the thread.
                state = State.WAITING_INVENTORY_CLOSE;
                return;
            }
            itemAskedAt.put(current.itemKey(), nowMillis);
            nextStartAtMillis = nowMillis + Math.max(0, cfg2.globalCooldownSeconds) * 1000L;

            takeIconFor(current);
            beginListening();
        } catch (Exception e) {
            LOG.error("[malanguagelearning] failed to start challenge", e);
            reset();
        }
    }

    /**
     * Drives timers and consumes worker results. Called once per client tick.
     *
     * <p>Never throws: any failure cancels the challenge rather than breaking the tick loop.</p>
     */
    public void tick() {
        try {
            drainCompletions();
            drainPairCompletions();
            advance();
        } catch (Exception e) {
            LOG.error("[malanguagelearning] challenge tick failed", e);
            reset();
        }
    }

    /**
     * Restart the current challenge from scratch: re-arm the timer and start a fresh
     * recording. No-op when nothing is pending.
     */
    public void retry() {
        try {
            if (current == null) {
                if (queue.isEmpty()) {
                    return;
                }
                current = queue.pollFirst();
            }
            if (sttInFlight) {
                return;
            }
            MllConfig cfg = MllConfig.get();
            if (!cfg.enabled) {
                return;
            }
            LOG.debug("[malanguagelearning] retrying challenge for {}", current.itemKey());
            stopCaptureQuietly();
            cancelRequested = false;
            lastConfidence = -1f;
            beginListening();
        } catch (Exception e) {
            LOG.error("[malanguagelearning] retry failed", e);
            reset();
        }
    }

    /** True while a challenge is pending, running, or showing a result. */
    public boolean isActive() {
        return state != State.IDLE;
    }

    /** True when no challenges are queued. Used by the retry keybind so idle players
     *  don't accidentally launch a new challenge. */
    public boolean queueIsEmpty() {
        return queue.isEmpty() && current == null;
    }

    /** The most recent resolved outcome. */
    /** True when the last result was a skip rather than a real pass (no microphone). */
    public boolean isSkipped() {
        return skipped;
    }

    public ChallengeOutcome currentOutcome() {
        return outcome;
    }

    /** Whole seconds left on the countdown, never negative. */
    public int secondsRemaining() {
        if (remainingTicks <= 0) {
            return 0;
        }
        return (remainingTicks + 19) / 20;
    }

    /** The word the player must say, or null when idle. */
    public String currentWord() {
        return current == null ? null : current.word();
    }

    /** The two-script rendering shown in the popup, or null when idle. */
    public WordPair currentPair() {
        return current == null ? null : current.pair();
    }

    /** STT confidence of the last evaluation, or -1 when unknown. */
    public float lastConfidence() {
        return lastConfidence;
    }

    /** The registry id of the challenge in progress, or null. */
    public String currentItemKey() {
        return current == null ? null : current.itemKey();
    }

    /**
     * A copy of the stack being asked about, for the HUD icon, or an empty stack.
     *
     * <p>This is a copy on purpose: the live stack can be consumed or merged while the
     * popup is on screen (that is the whole point of the drop), and drawing from a copy
     * keeps the icon stable for the life of the challenge.</p>
     */
    public ItemStack currentStack() {
        return currentStack == null ? ItemStack.EMPTY : currentStack;
    }

    /** Current state, for the HUD and for tests. */
    public State state() {
        return state;
    }

    /**
     * Short human-readable live status for the HUD, e.g. {@code "Listening…"} /
     * {@code "Checking…"} / {@code "Say it again"}. Never null; empty when idle.
     */
    public String statusText() {
        return switch (state) {
            case IDLE -> "";
            case WAITING_INVENTORY_CLOSE -> "Close your inventory to answer";
            case LISTENING -> recordingStarted ? "Listening…"
                    : micError != null ? "Microphone unavailable: " + micError
                    : "Microphone unavailable";
            case EVALUATING -> evaluatingTicks > 120
                    // First use of a language loads its model; Japanese/Chinese/Korean need
                    // Whisper, which is a ~1.5 GB download on the very first attempt. Saying
                    // so beats leaving the player thinking the mod has frozen.
                    ? "Checking… (first run downloads a model — this can take minutes)"
                    : "Checking…";
            case PASSED -> skipped ? "No microphone — skipped" : "Correct!";
            case FAILED -> "Say it again";
        };
    }

    /** Abandon the current challenge and everything queued behind it. */
    public void cancel() {
        LOG.debug("[malanguagelearning] challenge cancelled");
        outcome = ChallengeOutcome.CANCELLED;
        reset();
    }

    // ------------------------------------------------------------------ internals

    private void beginListening() {
        MllConfig cfg = MllConfig.get();
        LOG.debug("[malanguagelearning] begin challenge for {} (mic={})",
                current == null ? "?" : current.itemKey(),
                capture == null ? "none" : "present");
        int seconds = Math.max(1, cfg.answerSeconds);
        skipped = false;
        remainingTicks = seconds * 20;
        recordTicks = 0;
        evaluatingTicks = 0;
        holdTicks = 0;
        dropTicks = 0;
        dropPending = false;
        cancelRequested = false;
        state = State.LISTENING;

        micError = null;
        recordingStarted = false;
        if (capture == null) {
            LOG.warn("[malanguagelearning] no audio capture available");
            micError = "No audio capture device available";
            return;
        }
        try {
            recordingStarted = capture.start(seconds);
            if (!recordingStarted) {
                LOG.warn("[malanguagelearning] microphone unavailable: {}", capture.lastError());
                micError = capture.lastError() == null ? "Microphone unavailable"
                        : capture.lastError();
            }
        } catch (Exception e) {
            LOG.error("[malanguagelearning] microphone start failed", e);
            recordingStarted = false;
            micError = e.getClass().getSimpleName() + ": " + e.getMessage();
        }

        // Announce the word up front when the player asked for it.
        if (cfg.announceNewWord) {
            speakAsync(current == null ? null : current.word());
        }
    }

    private void advance() {
        switch (state) {
            case IDLE -> {
                if (!queue.isEmpty()) {
                    state = State.WAITING_INVENTORY_CLOSE;
                }
            }
            case WAITING_INVENTORY_CLOSE -> {
                if (queue.isEmpty() && current == null) {
                    state = State.IDLE;
                    return;
                }
                // The name says "wait for the inventory", but that is only the *historical*
                // trigger. An item picked straight up off the ground never involves a
                // container, so waiting for one left those pickups silently stuck here.
                // Start as soon as the screen is clear of menus.
                Minecraft mc = Minecraft.getInstance();
                boolean menuOpen = mc != null && mc.gui != null
                        && mc.gui.screen() instanceof AbstractContainerScreen<?>;
                if (menuOpen || mc == null || mc.player == null) {
                    return;
                }
                // Retry when there is anything at all to do — a queued item *or* a
                // `current` that was held back by the global cooldown. Checking only the
                // queue left that cooldown-retry case stalled forever.
                if (state == State.WAITING_INVENTORY_CLOSE && (!queue.isEmpty() || current != null)) {
                    onInventoryClosed();
                }
            }
            case LISTENING -> tickListening();
            case EVALUATING -> tickEvaluating();
            case PASSED, FAILED -> tickResult();
        }
    }

    private void tickEvaluating() {
        evaluatingTicks++;
    }

    private void tickListening() {
        if (cancelRequested) {
            reset();
            return;
        }

        recordTicks++;
        if (remainingTicks > 0) {
            remainingTicks--;
        }

        boolean stillRecording = false;
        if (recordingStarted && capture != null) {
            try {
                stillRecording = capture.isRecording();
            } catch (Exception e) {
                stillRecording = false;
            }
        }

        // End the take when the timer runs out, OR when the microphone hears speech and
        // then a run of silence — meaning the player has finished saying the word. This way
        // a one-second answer doesn't force the player to wait out the full window, and
        // silence isn't punished as a wrong answer.
        boolean timedOut = remainingTicks <= 0;
        boolean speechFinished = recordingStarted && capture != null && capture.speechEnded();
        if (timedOut || speechFinished) {
            // Read the flag BEFORE stopping: stopCaptureQuietly used to clear it, which made
            // every completed answer look like "no microphone" and skipped the challenge
            // silently. That is why nothing ever turned green or dropped an item.
            boolean hadMicrophone = recordingStarted;
            byte[] pcm = stopCaptureQuietly();
            if (!hadMicrophone) {
                // No microphone at all: nothing to enforce, so do not punish the player.
                // The item is deliberately NOT marked learned — they never got to answer.
                // This used to be completely silent, which looked exactly like the mod
                // being broken: the challenge began, nothing drew, nothing happened.
                LOG.warn("[malanguagelearning] cannot listen for {}: {}",
                        pendingKeyForLog(),
                        capture == null ? "no capture object" : String.valueOf(capture.lastError()));
                outcome = ChallengeOutcome.SKIPPED;
                state = State.PASSED;
                skipped = true;
                holdTicks = RESULT_HOLD_TICKS;
                current = null;
                return;
            }

            int peak = 0;
            if (capture != null) {
                try {
                    peak = capture.peakAmplitude();
                } catch (Exception ignored) {
                }
            }
            LOG.debug("[malanguagelearning] capture ended for {}: {} bytes peak={}",
                    pendingKeyForLog(), pcm == null ? -1 : pcm.length, peak);

            // Silence or near-silence is NOT a wrong answer — the player said nothing,
            // so there is nothing to mark wrong. Treat it as a skip: no drop, no failure
            // mark, item stays in inventory and can be challenged again later.
            boolean isSilent = peak <= SILENCE_PEAK_THRESHOLD;
            boolean tooShort = pcm == null || pcm.length < MIN_CAPTURE_BYTES;
            if (isSilent || tooShort) {
                LOG.debug("[malanguagelearning] no usable audio for {} (peak={}, bytes={}) — skipping, not failing",
                        pendingKeyForLog(), peak, pcm == null ? -1 : pcm.length);
                outcome = ChallengeOutcome.SKIPPED;
                state = State.PASSED;
                skipped = true;
                holdTicks = RESULT_HOLD_TICKS;
                current = null;
                return;
            }
            submitTranscription(pcm);
        }
    }

    private void submitTranscription(byte[] pcm) {
        Pending pending = current;
        state = State.EVALUATING;
        sttInFlight = true;
        MllConfig cfg = MllConfig.get();
        String language = cfg.targetLanguage;

        Thread worker = new Thread(() -> {
            SpeechResult result;
            try {
                result = stt == null ? SpeechResult.empty(language) : stt.transcribe(pcm, language);
                if (result == null) {
                    result = SpeechResult.empty(language);
                }
            } catch (Exception e) {
                LOG.error("[malanguagelearning] transcription failed", e);
                result = SpeechResult.empty(language);
            }
            completions.add(new SttCompletion(pending, result));
        }, "MaLanguageLearning-STT");
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (Exception e) {
            LOG.error("[malanguagelearning] could not start transcription worker", e);
            sttInFlight = false;
            resolveFailure(-1f);
        }
    }

    private void drainCompletions() {
        SttCompletion completion;
        while ((completion = completions.poll()) != null) {
            sttInFlight = false;
            if (state != State.EVALUATING) {
                // A cancel or retry superseded this result.
                continue;
            }
            if (current == null || !current.itemKey().equals(completion.pending().itemKey())) {
                continue;
            }
            evaluate(completion.result());
        }
    }

    private void evaluate(SpeechResult result) {
        MllConfig cfg = MllConfig.get();
        Pending pending = current;
        if (pending == null) {
            reset();
            return;
        }

        if (result == null || result.isEmpty()) {
            LOG.debug("[malanguagelearning] empty transcription for {}", pending.itemKey());
            resolveFailure(-1f);
            return;
        }

        lastConfidence = result.confidence();

        List<String> alternatives = buildAlternatives(pending);
        boolean textMatches = SpeechMatcher.matches(
                pending.word(), result.text(), alternatives, cfg.targetLanguage);

        // Confidence is a coarse second gate: the exact word match (Levenshtein tolerance +
        // alternatives) does the real discrimination. Confidence scales are not comparable
        // across backends (parakeet logprob vs Whisper avg_logprob), so Whisper-backed
        // results get a lower threshold.
        float minConfidence = cfg.minConfidence;
        boolean confidentEnough;
        if (minConfidence < 0f) {
            // Gate disabled: text match alone decides.
            confidentEnough = true;
        } else {
            minConfidence = Math.max(0f, Math.min(1f, minConfidence));
            float threshold = result.model() != null && result.model().contains("whisper")
                    ? minConfidence * 0.7f
                    : minConfidence;
            // Only bypass for unreported confidence (< 0), not for a reported zero.
            confidentEnough = result.confidence() < 0f || result.confidence() >= threshold;
        }
        boolean ok = textMatches && confidentEnough;

        LOG.debug("[malanguagelearning] heard '{}' (conf {}) for '{}' -> {} (text={}, confOk={})",
                result.text(), result.confidence(), pending.word(),
                ok ? "PASS" : "FAIL", textMatches, confidentEnough);

        if (ok) {
            long now = System.currentTimeMillis();
            ledger.markLearned(pending.itemKey(), pending.word(), now);
            outcome = ChallengeOutcome.PASSED;
            state = State.PASSED;
            // Hold long enough for the green flash to actually be seen as a state rather
            // than a blink: ~1.2 s at 20 ticks per second.
            holdTicks = PASS_HOLD_TICKS;
            // Cancel anything a previous failure left queued. Without this, a drop pending
            // from an earlier wrong answer fired here and threw the item away on a correct
            // answer, which looked exactly like the mod punishing success.
            dropPending = false;
            dropTicks = 0;
            failedItemKey = null;
            current = null;
            ChallengeSounds.passed();
        } else {
            resolveFailure(result.confidence());
        }
    }

    /** Every other accepted rendering of the word: secondary slot, variants, native. */
    private static List<String> buildAlternatives(Pending pending) {
        List<String> alternatives = new ArrayList<>(4);
        WordPair pair = pending.pair();
        if (pair != null) {
            addIfUsable(alternatives, pair.secondary());
            addIfUsable(alternatives, pair.native_());
            if (pair.variants() != null) {
                for (String variant : pair.variants().values()) {
                    addIfUsable(alternatives, variant);
                }
            }
        }
        return alternatives;
    }

    private static void addIfUsable(List<String> into, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!into.contains(value)) {
            into.add(value);
        }
    }

    private void resolveFailure(float confidence) {
        MllConfig cfg = MllConfig.get();
        Pending pending = current;
        outcome = ChallengeOutcome.FAILED;
        state = State.FAILED;
        holdTicks = RESULT_HOLD_TICKS;
        lastConfidence = confidence;
        failedItemKey = pending == null ? null : pending.itemKey();

        if (pending == null) {
            current = null;
            return;
        }

        ChallengeSounds.failed();

        if (cfg.speakWordOnFailure) {
            speakAsync(pending.word());
        }

        if (cfg.dropOnFailure) {
            dropPending = true;
            // Let the spoken word finish before the item leaves the inventory, so the
            // punishment is tied to the sound the player just heard.
            dropTicks = DROP_DELAY_TICKS + (cfg.speakWordOnFailure ? 30 : 0);
            // The hold must outlast the drop, or tickResult() returns the machine to IDLE
            // in the same tick the drop was due and the item is silently never thrown.
            holdTicks = dropTicks + RESULT_TAIL_TICKS;
        }
        current = null;
    }

    private void tickResult() {
        if (holdTicks > 0) {
            holdTicks--;
        }
        if (dropPending) {
            if (dropTicks > 0) {
                dropTicks--;
            }
            if (dropTicks <= 0) {
                dropPending = false;
                dropHeldItem();
                failedItemKey = null;
            }
        }
        if (holdTicks <= 0) {
            state = State.IDLE;
            // Preserve the real outcome (PASSED/FAILED) so callers can read it after the
            // result screen expires. Only default to CANCELLED when nothing was decided.
            if (outcome != ChallengeOutcome.PASSED && outcome != ChallengeOutcome.FAILED) {
                outcome = ChallengeOutcome.CANCELLED;
            }
            if (!queue.isEmpty()) {
                state = State.WAITING_INVENTORY_CLOSE;
            } else if (current != null) {
                state = State.WAITING_INVENTORY_CLOSE;
            }
        }
    }

    /**
     * Drop the failing stack.
     *
     * <p>Client-side drops must go through the container-input path to reach the server;
     * container id 0 is the player's own inventory, button 1 means "the whole stack" and
     * {@link ContainerInput#THROW} is a drop. The inventory slot the item was found in is
     * mapped onto the menu layout, where the hotbar sits after the main grid.</p>
     */
    private void dropHeldItem() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || mc.gameMode == null || mc.level == null) {
                return;
            }
            LocalPlayer player = mc.player;
            // A container being open must not swallow the drop: the player is allowed to
            // open their inventory while the result is on screen, and the consequence is
            // owed either way. Dropping through the open inventory menu is the safer of
            // the two paths, because container id 0 is the same menu in both cases.
            // mc.level != null gates drops to in-world play: no consequence in menus.

            MllConfig cfg = MllConfig.get();
            if (!cfg.dropOnFailure) {
                return;
            }

            // Prefer the exact challenged stack's slot (captured at queue time). If that
            // slot no longer holds the item (split/merge/move), fall back to scanning.
            int menuSlot = slotOfCurrent(player);
            if (menuSlot < 0) {
                menuSlot = findMenuSlot(player, failedItemKey);
            }
            if (menuSlot < 0) {
                LOG.info("[malanguagelearning] nothing to drop: {} is no longer carried", failedItemKey);
                return;
            }

            MultiPlayerGameMode gameMode = mc.gameMode;
            gameMode.handleContainerInput(InventoryMenu.CONTAINER_ID, menuSlot, 1, ContainerInput.THROW, player);
            LOG.info("[malanguagelearning] dropped {} from inventory slot {}",
                    failedItemKey, menuSlot);
        } catch (Exception e) {
            LOG.error("[malanguagelearning] failed to drop item", e);
        }
    }

    /**
     * Try the slot recorded when the challenge was queued. Returns the menu slot if that
     * inventory slot still holds a stack with the challenged item key, otherwise -1.
     */
    private int slotOfCurrent(LocalPlayer player) {
        if (current == null || current.inventorySlot() < 0) {
            return -1;
        }
        try {
            var inventory = player.getInventory();
            ItemStack candidate = inventory.getItem(current.inventorySlot());
            if (candidate == null || candidate.isEmpty()) {
                return -1;
            }
            if (failedItemKey != null && failedItemKey.equals(resolver.keyFor(candidate))) {
                return current.inventorySlot() >= 9
                        ? current.inventorySlot()
                        : current.inventorySlot() + InventoryMenu.USE_ROW_SLOT_START;
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /**
     * Locate the stack for {@code itemKey} in the inventory and translate its index into the
     * {@link InventoryMenu} slot layout.
     *
     * <p>Menu slots 9-35 mirror inventory slots 9-35 (the main grid), while menu slots 36-44
     * are the hotbar, i.e. inventory slots 0-8. When the exact item is gone, fall back to the
     * currently held hotbar slot.</p>
     */
    private int findMenuSlot(LocalPlayer player, String itemKey) {
        try {
            var inventory = player.getInventory();

            if (itemKey != null) {
                // Main grid + hotbar (inventory slots 0-35).
                for (int invSlot = 0; invSlot < 36; invSlot++) {
                    ItemStack candidate = inventory.getItem(invSlot);
                    if (candidate == null || candidate.isEmpty()) {
                        continue;
                    }
                    if (itemKey.equals(resolver.keyFor(candidate))) {
                        return invSlot >= 9 ? invSlot : invSlot + InventoryMenu.USE_ROW_SLOT_START;
                    }
                }
                // Armor slots (inventory 5-8 map to menu slots 5-8).
                for (int invSlot = 5; invSlot <= 8; invSlot++) {
                    ItemStack candidate = inventory.getItem(invSlot);
                    if (candidate == null || candidate.isEmpty()) {
                        continue;
                    }
                    if (itemKey.equals(resolver.keyFor(candidate))) {
                        return invSlot;
                    }
                }
                // Offhand (inventory slot 45 maps to menu slot 45).
                ItemStack offhand = inventory.getItem(45);
                if (offhand != null && !offhand.isEmpty()
                        && itemKey.equals(resolver.keyFor(offhand))) {
                    return 45;
                }
            }

            int selected = inventory.getSelectedSlot();
            if (selected < 0 || selected > 8) {
                return -1;
            }
            ItemStack held = inventory.getItem(selected);
            if (held == null || held.isEmpty()) {
                return -1;
            }
            return InventoryMenu.USE_ROW_SLOT_START + selected;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Play the word through the system audio line; never blocks the caller. */
    private void speakAsync(String word) {
        if (word == null || word.isBlank() || translations == null) {
            return;
        }
        MllConfig cfg = MllConfig.get();
        String language = cfg.targetLanguage;
        Thread worker = new Thread(() -> {
            try {
                byte[] wav = translations.synthesize(word, language);
                if (wav == null || wav.length == 0) {
                    // TTS unavailable (e.g. no voice for this language): play a beep so the
                    // player still hears the failure prompt instead of silence.
                    LOG.debug("[malanguagelearning] no TTS audio for '{}', playing beep", word);
                    ChallengeSounds.beep();
                    return;
                }
                playWav(wav);
            } catch (Exception e) {
                LOG.warn("[malanguagelearning] TTS playback failed for '{}'", word, e);
            }
        }, "MaLanguageLearning-TTS");
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (Exception e) {
            LOG.debug("[malanguagelearning] could not start TTS worker", e);
        }
    }

    private void playWav(byte[] wav) throws Exception {
        // Stop and close any previously playing TTS clip so rapid speakAsync calls don't
        // overlap or exhaust mixer lines.
        Clip previous = activeTtsClip;
        if (previous != null) {
            try {
                previous.stop();
                previous.close();
            } catch (Exception ignored) {
            }
            activeTtsClip = null;
        }

        AudioInputStream stream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav));
        AudioFormat format = stream.getFormat();
        Clip clip = AudioSystem.getClip();
        clip.addLineListener(event -> {
            if (event.getType() == LineEvent.Type.STOP) {
                try {
                    clip.close();
                } catch (Exception ignored) {
                }
                if (activeTtsClip == clip) {
                    activeTtsClip = null;
                }
            }
        });
        activeTtsClip = clip;
        clip.open(format, wav, 0, wav.length);
        clip.start();
    }

    /** Current challenge key, for log lines that run after {@code current} was cleared. */
    private String pendingKeyForLog() {
        return current == null ? "?" : current.itemKey();
    }

    private byte[] stopCaptureQuietly() {
        byte[] pcm = new byte[0];
        if (capture == null) {
            return pcm;
        }
        try {
            pcm = capture.stop();
        } catch (Exception e) {
            LOG.debug("[malanguagelearning] microphone stop failed", e);
            try {
                capture.cancel();
            } catch (Exception ignored) {
            }
        }
        return pcm;
    }

    public void reset() {
        stopCaptureQuietly();
        current = null;
        queue.clear();
        remainingTicks = 0;
        recordTicks = 0;
        evaluatingTicks = 0;
        holdTicks = 0;
        // A pending drop is deliberately left alone here. reset() runs on cancel, on retry
        // and after an error, and the consequence of a failed word (the item hitting the
        // floor) must survive all three; otherwise a player can dodge it by tapping the
        // retry key or reopening a container. tickResult() clears failedItemKey once the
        // drop has fired.
        if (!dropPending) {
            dropTicks = 0;
            failedItemKey = null;
        }
        sttInFlight = false;
        cancelRequested = false;
        skipped = false;
        micError = null;
        state = State.IDLE;
    }
}
