package com.ma.languagelearning.game;

import com.ma.languagelearning.audio.AudioCapture;
import com.ma.languagelearning.config.MllConfig;
import com.ma.languagelearning.speech.SpeechResult;
import com.ma.languagelearning.speech.SttClient;
import com.ma.languagelearning.speech.TranslationProvider;
import com.ma.languagelearning.speech.WordPair;
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

    private static final int MAX_QUEUE = 8;
    /** Minimum recording length before a silence stop is honoured, in ticks. */
    private static final int MIN_RECORD_TICKS = 20;
    /** How long the PASSED/FAILED result stays on screen, in ticks. */
    private static final int RESULT_HOLD_TICKS = 40;
    /** Delay between the TTS prompt finishing and the drop, in ticks. */
    private static final int DROP_DELAY_TICKS = 10;

    private final LearnedLedger ledger;
    private final ItemNameResolver resolver;
    private final SttClient stt;
    private final TranslationProvider translations;
    private final AudioCapture capture;

    private final Deque<Pending> queue = new ArrayDeque<>();
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
    /** Registry id of the challenge that just failed, kept until the drop runs. */
    private String failedItemKey;

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
        if (queue.size() >= MAX_QUEUE) {
            LOG.debug("[malanguagelearning] challenge queue full, dropping {}", itemKey);
            return;
        }

        int slot = slotOf(stack);
        WordPair pair = resolvePair(word);
        queue.addLast(new Pending(itemKey, word, pair, slot));
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

    /** The most recent resolved outcome. */
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
            case LISTENING -> recordingStarted ? "Listening…" : "Microphone unavailable";
            case EVALUATING -> "Checking…";
            case PASSED -> "Correct!";
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
        int seconds = Math.max(1, cfg.answerSeconds);
        remainingTicks = seconds * 20;
        recordTicks = 0;
        holdTicks = 0;
        dropTicks = 0;
        dropPending = false;
        cancelRequested = false;
        state = State.LISTENING;

        recordingStarted = false;
        if (capture == null) {
            LOG.warn("[malanguagelearning] no audio capture available");
            return;
        }
        try {
            recordingStarted = capture.start();
            if (!recordingStarted) {
                LOG.warn("[malanguagelearning] microphone unavailable: {}", capture.lastError());
            }
        } catch (Exception e) {
            LOG.error("[malanguagelearning] microphone start failed", e);
            recordingStarted = false;
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
                // Nothing to do until the inventory closes; the event drives the transition.
                if (queue.isEmpty() && current == null) {
                    state = State.IDLE;
                }
            }
            case LISTENING -> tickListening();
            case EVALUATING -> {
                // Waiting on the STT worker; the queue drain moves us on.
            }
            case PASSED, FAILED -> tickResult();
        }
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

        // Timer expired, or the capture ended on its own after a real attempt.
        boolean timedOut = remainingTicks <= 0;
        boolean finishedNaturally = !stillRecording && recordTicks >= MIN_RECORD_TICKS;
        if (timedOut || finishedNaturally) {
            byte[] pcm = stopCaptureQuietly();
            if (!recordingStarted) {
                // No microphone at all: nothing to enforce, so do not punish the player.
                outcome = ChallengeOutcome.SKIPPED;
                state = State.PASSED;
                holdTicks = RESULT_HOLD_TICKS;
                current = null;
                return;
            }
            if (pcm == null || pcm.length < 3200) {
                // Under ~100 ms of audio: treat as no answer.
                resolveFailure(-1f);
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
        boolean ok = SpeechMatcher.matches(pending.word(), result.text(), alternatives, cfg.targetLanguage);
        LOG.debug("[malanguagelearning] heard '{}' (conf {}) for '{}' -> {}",
                result.text(), result.confidence(), pending.word(), ok ? "PASS" : "FAIL");

        if (ok) {
            long now = System.currentTimeMillis();
            ledger.markLearned(pending.itemKey(), pending.word(), now);
            outcome = ChallengeOutcome.PASSED;
            state = State.PASSED;
            holdTicks = RESULT_HOLD_TICKS;
            current = null;
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

        if (cfg.speakWordOnFailure) {
            speakAsync(pending.word());
        }

        if (cfg.dropOnFailure) {
            dropPending = true;
            // Let the spoken word finish before the item leaves the inventory, so the
            // punishment is tied to the sound the player just heard.
            dropTicks = DROP_DELAY_TICKS + (cfg.speakWordOnFailure ? 30 : 0);
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
            outcome = ChallengeOutcome.CANCELLED;
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
            if (mc == null || mc.player == null || mc.gameMode == null) {
                return;
            }
            LocalPlayer player = mc.player;
            // Only act when the player is looking at the world, not at another menu.
            if (mc.gui.screen() instanceof AbstractContainerScreen<?>) {
                return;
            }

            MllConfig cfg = MllConfig.get();
            if (!cfg.dropOnFailure) {
                return;
            }

            int menuSlot = findMenuSlot(player, failedItemKey);
            if (menuSlot < 0) {
                LOG.debug("[malanguagelearning] nothing to drop: item no longer carried");
                return;
            }

            MultiPlayerGameMode gameMode = mc.gameMode;
            gameMode.handleContainerInput(InventoryMenu.CONTAINER_ID, menuSlot, 1, ContainerInput.THROW, player);
            LOG.debug("[malanguagelearning] dropped stack from menu slot {}", menuSlot);
        } catch (Exception e) {
            LOG.error("[malanguagelearning] failed to drop item", e);
        }
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
                for (int invSlot = 0; invSlot < 36; invSlot++) {
                    ItemStack candidate = inventory.getItem(invSlot);
                    if (candidate == null || candidate.isEmpty()) {
                        continue;
                    }
                    if (itemKey.equals(resolver.keyFor(candidate))) {
                        return invSlot >= 9 ? invSlot : invSlot + InventoryMenu.USE_ROW_SLOT_START;
                    }
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
                    LOG.debug("[malanguagelearning] no TTS audio for '{}'", word);
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

    private static void playWav(byte[] wav) throws Exception {
        AudioInputStream stream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav));
        AudioFormat format = stream.getFormat();
        Clip clip = AudioSystem.getClip();
        clip.addLineListener(event -> {
            if (event.getType() == LineEvent.Type.STOP) {
                try {
                    clip.close();
                } catch (Exception ignored) {
                }
            }
        });
        clip.open(format, wav, 0, wav.length);
        clip.start();
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
        recordingStarted = false;
        return pcm;
    }

    private void reset() {
        stopCaptureQuietly();
        current = null;
        queue.clear();
        remainingTicks = 0;
        recordTicks = 0;
        holdTicks = 0;
        dropTicks = 0;
        dropPending = false;
        sttInFlight = false;
        cancelRequested = false;
        state = State.IDLE;
    }
}
