package com.ma.languagelearning.net;

import com.ma.languagelearning.config.MllConfig;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Zero-setup provisioning engine for the Python speech sidecar.
 *
 * <p>The mod ships its Python package as jar resources; on first run this class copies them
 * out, fetches a standalone {@code uv} launcher, has {@code uv} create an isolated Python
 * 3.12 virtualenv and installs the sidecar into it. The user installs nothing — no system
 * Python, no pip, no virtualenv module.</p>
 *
 * <p>Everything here is best-effort by design: any failure leaves the mod fully playable
 * with the speech engine reported as unavailable. Nothing in this class may throw to the
 * caller — {@link #ensureReady()} completes exceptionally at worst, and the game never sees
 * an exception.</p>
 *
 * <p>Layout under {@code <config>/malanguagelearning/}:</p>
 * <pre>
 *   runtime/uv            standalone launcher (uv.exe on Windows)
 *   runtime/venv/         uv-managed Python 3.12 virtualenv
 *   sidecar/              copy of the bundled Python package
 *   models/               model cache the sidecar downloads into
 * </pre>
 */
public final class SidecarProvisioner {

    private static final Logger LOG = LoggerFactory.getLogger("malanguagelearning");

    /** Where {@code uv} publishes its standalone launchers. */
    private static final String UV_RELEASE_BASE =
            "https://github.com/astral-sh/uv/releases/latest/download/";

    /** Python minor version the sidecar is pinned to. */
    private static final String PYTHON_VERSION = "3.12";

    /** Sidecar importable package / module name. */
    private static final String SIDECAR_MODULE = "malanguage_server";

    /** Resource prefix the Python package is packaged under inside the mod jar. */
    private static final String RESOURCE_ROOT = "assets/malanguagelearning/sidecar/";

    /** Embedded listing of every file under {@link #RESOURCE_ROOT}. */
    private static final String MANIFEST = "sidecar-manifest.txt";

    /** Stamp proving a previous successful {@code uv pip install}, so restarts are cheap. */
    private static final String STAMP = ".mll-provisioned";

    private static final int HEALTH_TIMEOUT_MS = 1_500;
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(10);

    /** How long a graceful stop may take before the process is killed outright. */
    private static final int STOP_GRACE_SECONDS = 5;

    private final AtomicReference<ProvisionProgress> progress =
            new AtomicReference<>(ProvisionProgress.idle());

    private final Object readyLock = new Object();
    private CompletableFuture<Void> ready;

    private final Object processLock = new Object();
    private Process sidecar;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    // ------------------------------------------------------------------ public API

    /**
     * Run the whole provisioning chain once, on a background thread.
     *
     * <p>The returned future is created on the first call and cached forever: concurrent and
     * later callers observe the same instance, so the chain never runs twice. A failed run
     * completes exceptionally and leaves {@link #progress()} at
     * {@link ProvisionProgress.Stage#FAILED}; the mod continues without a speech engine.</p>
     */
    public CompletableFuture<Void> ensureReady() {
        synchronized (readyLock) {
            if (ready == null) {
                ready = CompletableFuture.runAsync(this::runChain);
            }
            return ready;
        }
    }

    /** Immutable snapshot of what the provisioner is doing right now. */
    public ProvisionProgress progress() {
        return progress.get();
    }

    /** True once the chain completed successfully. */
    public boolean isReady() {
        return progress.get().isReady();
    }

    // ------------------------------------------------------------------ sidecar process

    /**
     * Launch the sidecar process, or return the one already running.
     *
     * <p>If something already answers {@code /health} on the configured port — a sidecar the
     * user started by hand, or a previous game session that outlived us — this returns
     * {@code null} instead of starting a second copy that would fail to bind.</p>
     *
     * @return the started process, or {@code null} when a sidecar is already listening or
     *         cannot be started
     */
    public Process startSidecar() {
        synchronized (processLock) {
            if (sidecar != null && sidecar.isAlive()) {
                return sidecar;
            }

            MllConfig cfg = MllConfig.get();
            int port = cfg.sidecarPort;

            if (isSidecarHealthy()) {
                // A sidecar left over from a previous run is reused, but only if it is the
                // build we just unpacked. Without this check an old process kept serving
                // stale code and asset choices forever, so updating the mod appeared to do
                // nothing until the machine was rebooted.
                String running = sidecarFingerprint();
                String packaged = packagedFingerprint();
                boolean sameBuild = running == null || packaged == null || running.equals(packaged);
                if (sameBuild) {
                    LOG.info("[{}] a matching sidecar already answers on port {}; reusing it",
                            "malanguagelearning", port);
                    return null;
                }
                LOG.info("[{}] port {} is served by an older sidecar; replacing it",
                        "malanguagelearning", port);
                stopSidecar();
                sleepQuietly(1200);
            }

            try {
                Path python = venvPython();
                if (python == null || !Files.isExecutable(python)) {
                    LOG.warn("[{}] sidecar runtime is not provisioned yet (no {}); engine unavailable",
                            "malanguagelearning", python);
                    return null;
                }

                Path sidecarDir = sidecarDir();
                Path modelsDir = dataDir().resolve("models");
                Files.createDirectories(modelsDir);

                String device = "gpu".equalsIgnoreCase(cfg.inferenceDevice) ? "gpu" : "cpu";

                List<String> command = new ArrayList<>();
                command.add(python.toString());
                command.add("-m");
                command.add(SIDECAR_MODULE);
                command.add("--port");
                command.add(Integer.toString(port));
                command.add("--device");
                command.add(device);
                command.add("--models-dir");
                command.add(modelsDir.toString());

                ProcessBuilder builder = new ProcessBuilder(command);
                if (Files.isDirectory(sidecarDir)) {
                    builder.directory(sidecarDir.toFile());
                }
                builder.redirectErrorStream(true);

                Path logFile = sidecarLogFile();
                Files.createDirectories(logFile.getParent());
                builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));

                Process started = builder.start();
                sidecar = started;
                LOG.info("[{}] sidecar started (pid {}, port {}, device {}), log: {}",
                        "malanguagelearning", started.pid(), port, device, logFile);
                return started;
            } catch (Exception e) {
                LOG.error("[{}] failed to start the sidecar", "malanguagelearning", e);
                return null;
            }
        }
    }

    /** Stop the sidecar: ask politely, then insist. */
    public void stopSidecar() {
        synchronized (processLock) {
            Process running = sidecar;
            sidecar = null;
            if (running == null) {
                return;
            }
            try {
                if (!running.isAlive()) {
                    return;
                }
                LOG.info("[{}] stopping sidecar (pid {})", "malanguagelearning", running.pid());
                running.destroy();
                if (!running.waitFor(STOP_GRACE_SECONDS, TimeUnit.SECONDS)) {
                    LOG.warn("[{}] sidecar ignored a graceful stop; killing it", "malanguagelearning");
                    running.destroyForcibly();
                    running.waitFor(STOP_GRACE_SECONDS, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running.destroyForcibly();
            } catch (Exception e) {
                LOG.warn("[{}] error while stopping the sidecar", "malanguagelearning", e);
            }
        }
    }

    /** True when the sidecar answers {@code GET /health} with a 2xx. */
    /**
     * Fingerprint of the sidecar the running server reports, or null when it does not
     * report one (older builds simply had no such field, so we treat it as matching).
     */
    private String sidecarFingerprint() {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(2)).build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://127.0.0.1:" + MllConfig.get().sidecarPort + "/health"))
                    .timeout(java.time.Duration.ofSeconds(3)).GET().build();
            java.net.http.HttpResponse<String> response =
                    client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            com.google.gson.JsonElement root =
                    com.google.gson.JsonParser.parseString(response.body());
            if (root == null || !root.isJsonObject()) {
                return null;
            }
            com.google.gson.JsonElement build = root.getAsJsonObject().get("build");
            return build == null || build.isJsonNull() ? null : build.getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Fingerprint of the sidecar shipped in this jar. */
    private String packagedFingerprint() {
        try (java.io.InputStream in = getClass().getResourceAsStream(
                "/assets/malanguagelearning/sidecar/malanguage_server/build_id.txt")) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isSidecarHealthy() {
        int port = MllConfig.get().sidecarPort;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                    .timeout(Duration.ofMillis(HEALTH_TIMEOUT_MS))
                    .GET()
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** The process this provisioner launched, or null. */
    public Process sidecarProcess() {
        synchronized (processLock) {
            return sidecar;
        }
    }

    // ------------------------------------------------------------------ the chain

    private void runChain() {
        try {
            report(ProvisionProgress.Stage.EXTRACTING_UV, "copying bundled sidecar sources", 2);
            Path sidecarDir = sidecarDir();
            unpackSidecarResources(sidecarDir);

            Path venvDir = dataDir().resolve("venv");
            Path mark = venvDir.resolve(STAMP);
            if (Files.isRegularFile(mark) && venvPython() != null && Files.exists(venvPython())) {
                LOG.info("[{}] sidecar runtime already provisioned at {}", "malanguagelearning", venvDir);
                report(ProvisionProgress.Stage.READY, "engine ready", 100);
                return;
            }

            report(ProvisionProgress.Stage.DOWNLOADING_UV, "locating the uv launcher", 5);
            Path uv = ensureUv();

            report(ProvisionProgress.Stage.CREATING_VENV, "creating Python " + PYTHON_VERSION + " environment", 55);
            runUv(uv, "venv", venvDir.toString(), "--python", PYTHON_VERSION);

            Path python = venvPython();
            if (python == null || !Files.exists(python)) {
                throw new IOException("venv was created but has no interpreter at " + python);
            }

            report(ProvisionProgress.Stage.INSTALLING_DEPS, "installing speech engine dependencies", 70);
            // Install with the [full] extra. The bare install only covers the 25 European
            // languages parakeet handles; Japanese, Chinese, Korean and the rest need Whisper
            // as a speech engine, and Japanese speech output needs its own phonemizer. Without
            // the extra those languages install "successfully" and then simply cannot listen,
            // which looked like the microphone being broken.
            runUv(uv, "pip", "install", "--python", python.toString(),
                    "-e", sidecarDir + "[full]");

            Files.writeString(mark, "ok\n", StandardCharsets.UTF_8);
            report(ProvisionProgress.Stage.READY, "engine ready", 100);
        } catch (Throwable t) {
            String message = describe(t);
            LOG.error("[{}] sidecar provisioning failed: {}", "malanguagelearning", message, t);
            // The progress detail is rendered on a UI line, so it carries the headline only;
            // the full command output stays in the log and in the exception.
            report(ProvisionProgress.Stage.FAILED, headLine(message, 200), 100);
            throw new IllegalStateException(message, t);
        }
    }

    /**
     * Copy the Python package shipped inside the mod jar out to the sidecar directory.
     *
     * <p>The file list comes from an embedded manifest rather than a classpath directory
     * scan, because enumerating a jar's contents is not portable across classloaders. When
     * the manifest is absent (a development build without the Python source) this logs a
     * warning and skips — the mod then simply has no engine, which is allowed.</p>
     */
    private void unpackSidecarResources(Path sidecarDir) throws IOException {
        ClassLoader loader = SidecarProvisioner.class.getClassLoader();
        List<String> entries;
        try (InputStream manifestStream = loader.getResourceAsStream(MANIFEST)) {
            if (manifestStream == null) {
                LOG.warn("[{}] {} not found on the classpath; no bundled sidecar to unpack",
                        "malanguagelearning", MANIFEST);
                return;
            }
            entries = new ArrayList<>();
            for (String line : new String(manifestStream.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    entries.add(trimmed);
                }
            }
        }

        Files.createDirectories(sidecarDir);
        int copied = 0;
        for (String relative : entries) {
            String normalised = relative.replace('\\', '/');
            if (normalised.contains("..")) {
                LOG.warn("[{}] refusing manifest entry that escapes the sidecar dir: {}",
                        "malanguagelearning", relative);
                continue;
            }
            String resource = RESOURCE_ROOT + (normalised.startsWith("/") ? normalised.substring(1) : normalised);
            try (InputStream in = loader.getResourceAsStream(resource)) {
                if (in == null) {
                    LOG.warn("[{}] manifest lists {} but the resource is missing", "malanguagelearning", resource);
                    continue;
                }
                Path target = sidecarDir.resolve(normalised).normalize();
                if (!target.startsWith(sidecarDir)) {
                    LOG.warn("[{}] refusing to write outside the sidecar dir: {}", "malanguagelearning", target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                copied++;
            }
        }
        LOG.info("[{}] unpacked {} sidecar file(s) into {}", "malanguagelearning", copied, sidecarDir);
    }

    /**
     * Make sure the {@code uv} launcher exists in the runtime directory, downloading it from
     * the official GitHub release on first run.
     */
    private Path ensureUv() throws IOException {
        Path runtimeDir = dataDir().resolve("uv-runtime");
        Files.createDirectories(runtimeDir);

        boolean windows = isWindows();
        String binaryName = windows ? "uv.exe" : "uv";
        Path uvPath = runtimeDir.resolve(binaryName);

        if (Files.isRegularFile(uvPath) && (windows || Files.isExecutable(uvPath))) {
            LOG.info("[{}] using existing uv launcher at {}", "malanguagelearning", uvPath);
            report(ProvisionProgress.Stage.EXTRACTING_UV, "uv already present", 50);
            return uvPath;
        }

        String asset = uvAssetName();
        String url = UV_RELEASE_BASE + asset;
        LOG.info("[{}] downloading uv from {}", "malanguagelearning", url);
        report(ProvisionProgress.Stage.DOWNLOADING_UV, "downloading " + asset, 10);

        Path archive = runtimeDir.resolve(asset);
        download(url, archive);

        report(ProvisionProgress.Stage.EXTRACTING_UV, "unpacking the uv launcher", 50);
        Path extracted;
        if (asset.endsWith(".zip")) {
            extracted = extractZipMember(archive, binaryName, uvPath);
        } else {
            extracted = extractTarMember(archive, binaryName, uvPath);
        }

        if (!windows) {
            makeExecutable(extracted);
        }
        try {
            Files.deleteIfExists(archive);
        } catch (IOException e) {
            LOG.debug("[{}] could not delete the uv archive", "malanguagelearning", e);
        }
        LOG.info("[{}] uv ready at {}", "malanguagelearning", extracted);
        return extracted;
    }

    /** The release asset name for this OS/architecture combination. */
    private static String uvAssetName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean aarch64 = arch.contains("aarch64") || arch.contains("arm64");

        if (os.contains("win")) {
            // uv publishes a single x86_64 Windows build; it runs on ARM too.
            return "uv-x86_64-pc-windows-msvc.zip";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return aarch64
                    ? "uv-aarch64-apple-darwin.tar.gz"
                    : "uv-x86_64-apple-darwin.tar.gz";
        }
        return aarch64
                ? "uv-aarch64-unknown-linux-gnu.tar.gz"
                : "uv-x86_64-unknown-linux-gnu.tar.gz";
    }

    private void download(String url, Path target) throws IOException {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(DOWNLOAD_TIMEOUT)
                    .header("User-Agent", "malanguagelearning/0.1.0")
                    .GET()
                    .build();
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid uv download URL: " + url, e);
        }

        Path partial = target.resolveSibling(target.getFileName() + ".part");
        try (OutputStream out = Files.newOutputStream(partial)) {
            HttpResponse<InputStream> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while downloading uv", e);
            }

            int status = response.statusCode();
            try (InputStream body = response.body()) {
                if (status < 200 || status >= 300) {
                    String text = body == null ? "" : new String(body.readNBytes(2048), StandardCharsets.UTF_8);
                    throw new IOException("uv download answered HTTP " + status + ": " + text.strip());
                }
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                long expected = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                int read;
                int lastPercent = -1;
                while ((read = body.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    total += read;
                    if (expected > 0) {
                        int percent = (int) Math.min(45, 10 + (total * 35 / expected));
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            report(ProvisionProgress.Stage.DOWNLOADING_UV,
                                    "downloading uv (" + (total / 1_048_576) + " MB)", percent);
                        }
                    }
                }
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(partial);
            } catch (IOException ignored) {
                // best effort
            }
            throw e;
        }

        try {
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(partial);
            } catch (IOException ignored) {
                // best effort
            }
            throw new IOException("could not store the downloaded uv archive", e);
        }
    }

    /** Run {@code uv <args...>} and fail with the command's output tail when it exits non-zero. */
    private void runUv(Path uv, String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(uv.toString());
        command.addAll(List.of(args));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        // uv must not try to prompt; it has to run unattended inside the game.
        builder.environment().put("UV_NO_PROGRESS", "1");
        builder.environment().put("UV_LINK_MODE", "copy");
        builder.environment().putIfAbsent("UV_CACHE_DIR",
                dataDir().resolve("uv-cache").toString());

        LOG.info("[{}] running: {}", "malanguagelearning", String.join(" ", command));
        Process process = builder.start();

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (InputStream output = process.getInputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = output.read(buffer)) != -1) {
                captured.write(buffer, 0, read);
            }
        }

        int exit;
        try {
            exit = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("interrupted while running uv", e);
        }

        String text = captured.toString(StandardCharsets.UTF_8);
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                LOG.info("[uv] {}", line);
            }
        }
        if (exit != 0) {
            String output = tail(text, 1_500);
            throw new IOException("uv " + String.join(" ", args) + " exited with " + exit
                    + (output.isEmpty() ? "" : ": " + output));
        }
    }

    /**
     * Pull a single regular file out of a {@code .tar.gz} archive.
     *
     * <p>Hand-rolled on purpose: the mod may not have commons-compress at runtime, and the
     * only thing needed from uv's archive is one binary. Handles the GNU long-name and pax
     * header records so a long member path does not corrupt the stream.</p>
     */
    private static Path extractTarMember(Path archive, String memberName, Path destination) throws IOException {
        try (InputStream raw = Files.newInputStream(archive);
             GZIPInputStream gzip = new GZIPInputStream(raw)) {

            byte[] header = new byte[512];
            String longName = null;

            while (true) {
                if (readFully(gzip, header) < 512) {
                    break;
                }
                if (isZeroBlock(header)) {
                    break;
                }

                String name = readString(header, 0, 100);
                long size = readOctal(header, 124, 12);
                char type = (char) (header[156] & 0xFF);
                String prefix = readString(header, 345, 155);
                if (!prefix.isEmpty()) {
                    name = prefix + "/" + name;
                }

                if (type == 'L') {                       // GNU long name for the next entry
                    longName = new String(readData(gzip, size), StandardCharsets.UTF_8);
                    if (longName.endsWith("\0")) {
                        longName = longName.substring(0, longName.length() - 1);
                    }
                    continue;
                }
                if (type == 'x' || type == 'g') {        // pax extended header
                    readData(gzip, size);
                    continue;
                }
                if (type == '0' || type == '\0' || type == '7') {
                    String effective = longName != null ? longName : name;
                    String base = effective.substring(effective.lastIndexOf('/') + 1);
                    if (base.equals(memberName)) {
                        Files.createDirectories(destination.getParent());
                        try (OutputStream out = Files.newOutputStream(destination)) {
                            copyN(gzip, out, size);
                        }
                        return destination;
                    }
                    skipN(gzip, size);
                    longName = null;
                    continue;
                }
                // Directories, symlinks and other metadata: skip their payload.
                longName = null;
                skipN(gzip, size);
            }
        }
        throw new IOException("archive " + archive.getFileName() + " does not contain " + memberName);
    }

    /** Pull a single regular file out of a {@code .zip} archive (the Windows uv build). */
    private static Path extractZipMember(Path archive, String memberName, Path destination) throws IOException {
        try (InputStream raw = Files.newInputStream(archive);
             ZipInputStream zip = new ZipInputStream(raw)) {

            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().replace('\\', '/');
                String base = name.substring(name.lastIndexOf('/') + 1);
                if (base.equals(memberName)) {
                    Files.createDirectories(destination.getParent());
                    Files.copy(zip, destination, StandardCopyOption.REPLACE_EXISTING);
                    return destination;
                }
            }
        }
        throw new IOException("archive " + archive.getFileName() + " does not contain " + memberName);
    }

    // ------------------------------------------------------------------ helpers

    private void report(ProvisionProgress.Stage stage, String detail, int percent) {
        ProvisionProgress snapshot = new ProvisionProgress(stage, detail, percent);
        progress.set(snapshot);
        if (stage != ProvisionProgress.Stage.DOWNLOADING_UV || percent % 10 == 0) {
            LOG.info("[{}] provisioning: {}", "malanguagelearning", snapshot);
        }
    }

    /** {@code <config>/malanguagelearning} — where every runtime artefact lives. */
    private static Path dataDir() throws IOException {
        try {
            return MllConfig.dataDir();
        } catch (RuntimeException e) {
            throw new IOException("cannot resolve the mod data directory", e);
        }
    }

    private static Path sidecarDir() throws IOException {
        return dataDir().resolve("sidecar");
    }

    /** The venv interpreter: {@code bin/python} on Unix, {@code Scripts/python.exe} on Windows. */
    private static Path venvPython() throws IOException {
        Path venv = dataDir().resolve("venv");
        return isWindows()
                ? venv.resolve("Scripts").resolve("python.exe")
                : venv.resolve("bin").resolve("python");
    }

    private static Path sidecarLogFile() {
        Path gameDir = FMLPaths.GAMEDIR.get();
        return gameDir.resolve("logs").resolve("malanguagelearning-sidecar.log");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void makeExecutable(Path path) {
        try {
            path.toFile().setExecutable(true, false);
            if (!Files.isExecutable(path)) {
                Files.setPosixFilePermissions(path,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
            }
        } catch (Exception e) {
            LOG.warn("[{}] could not mark {} executable", "malanguagelearning", path, e);
        }
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.isBlank()) {
            message = t.getClass().getSimpleName();
        }
        return message;
    }

    private static String tail(String text, int max) {
        if (text == null) {
            return "";
        }
        String stripped = text.strip();
        return stripped.length() <= max ? stripped : "…" + stripped.substring(stripped.length() - max);
    }

    /** The first line of a message, capped, for one-line UI display. */
    private static String headLine(String text, int max) {
        if (text == null) {
            return "";
        }
        String first = text.strip().split("\\R", 2)[0].strip();
        return first.length() <= max ? first : first.substring(0, max) + "…";
    }

    // ---- tar/byte helpers ---------------------------------------------------

    private static int readFully(InputStream in, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int read = in.read(buffer, total, buffer.length - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total;
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String readString(byte[] header, int offset, int length) {
        int end = offset;
        int limit = Math.min(offset + length, header.length);
        while (end < limit && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }

    /** Parse a tar size field: octal ASCII, or GNU base-256 when the high bit is set. */
    private static long readOctal(byte[] header, int offset, int length) {
        if ((header[offset] & 0x80) != 0) {
            long value = 0;
            for (int i = offset + 1; i < offset + length; i++) {
                value = (value << 8) | (header[i] & 0xFF);
            }
            return value;
        }
        String text = readString(header, offset, length).strip();
        if (text.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(text, 8);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static byte[] readData(InputStream in, long size) throws IOException {
        if (size <= 0) {
            return new byte[0];
        }
        if (size > Integer.MAX_VALUE - 8) {
            throw new IOException("tar member too large: " + size);
        }
        byte[] data = in.readNBytes((int) size);
        skipN(in, padOf(size));
        return data;
    }

    private static void copyN(InputStream in, OutputStream out, long size) throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = size;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                break;
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
        skipN(in, padOf(size));
    }

    private static void skipN(InputStream in, long size) throws IOException {
        long remaining = size;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                // skip() may legitimately return 0; fall back to a read to make progress.
                if (in.read() < 0) {
                    return;
                }
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }

    /** Tar pads every member to a 512-byte boundary. */
    private static long padOf(long size) {
        long remainder = size % 512L;
        return remainder == 0 ? 0 : 512L - remainder;
    }
}
