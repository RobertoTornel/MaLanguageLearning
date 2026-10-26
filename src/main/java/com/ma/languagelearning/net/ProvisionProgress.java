package com.ma.languagelearning.net;

/**
 * Immutable progress snapshot of {@link SidecarProvisioner#ensureReady()}.
 *
 * <p>A {@code ProvisionProgress} is the value a UI polls to render the "preparing the speech
 * engine" screen: the {@link Stage} says what is happening, {@code detail} carries a
 * human-readable line (a download percentage, a failing command's tail, …) and
 * {@code percent} is a coarse 0-100 estimate for a progress bar.</p>
 *
 * <p>Instances are value records and therefore safe to hand to any thread.</p>
 *
 * @param stage   current stage of the provisioning chain
 * @param detail  short human-readable description of what is happening; never null
 * @param percent coarse completion estimate in {@code [0,100]}
 */
public record ProvisionProgress(Stage stage, String detail, int percent) {

    /** Ordered stages of the zero-setup provisioning chain. */
    public enum Stage {
        /** Nothing has started yet. */
        IDLE,
        /** Downloading the {@code uv} launcher from GitHub releases. */
        DOWNLOADING_UV,
        /** Unpacking the {@code uv} archive into the runtime directory. */
        EXTRACTING_UV,
        /** Running {@code uv venv} to create the interpreter. */
        CREATING_VENV,
        /** Running {@code uv pip install} for the sidecar's dependencies. */
        INSTALLING_DEPS,
        /** Everything succeeded; {@code startSidecar()} may be called. */
        READY,
        /** Something failed. The mod keeps working with the engine reported as unavailable. */
        FAILED
    }

    /** The state before {@code ensureReady()} is called. */
    public static final ProvisionProgress IDLE_PROGRESS = new ProvisionProgress(Stage.IDLE, "idle", 0);

    public ProvisionProgress {
        stage = stage == null ? Stage.IDLE : stage;
        detail = detail == null ? "" : detail;
        percent = Math.max(0, Math.min(100, percent));
    }

    public static ProvisionProgress of(Stage stage, String detail, int percent) {
        return new ProvisionProgress(stage, detail, percent);
    }

    public static ProvisionProgress idle() {
        return IDLE_PROGRESS;
    }

    /** The chain finished successfully. */
    public boolean isReady() {
        return stage == Stage.READY;
    }

    /** The chain gave up; {@link #detail()} holds the reason. */
    public boolean isFailed() {
        return stage == Stage.FAILED;
    }

    /** True once the chain has stopped, successfully or not. */
    public boolean isTerminal() {
        return stage == Stage.READY || stage == Stage.FAILED;
    }

    /** True while work is still in flight. */
    public boolean isRunning() {
        return stage != Stage.IDLE && !isTerminal();
    }

    @Override
    public String toString() {
        return stage + " " + percent + "% — " + detail;
    }
}
