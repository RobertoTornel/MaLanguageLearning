"""Model resolution, download and hot-swapping.

Design goals
------------
* **Model agnostic.**  A language maps to a backend *name*; the manager resolves that
  name through :data:`BACKENDS`.  Adding a backend means adding a table entry.
* **Hot swappable.**  At most :data:`MAX_LOADED_MODELS` models stay resident; loading a
  third evicts the least recently used one (``del`` + :func:`gc.collect`).
* **Never crashes on import.**  Every optional dependency (``onnx_asr``,
  ``faster_whisper``, ``huggingface_hub``) is imported lazily inside a function body and
  guarded.  A missing dependency produces a :class:`BackendUnavailable` that the HTTP
  layer turns into a 503 with an actionable message.
* **No network at import.**  Downloads happen only inside :meth:`ModelManager.load`,
  which is called on the first request for a language.
"""

from __future__ import annotations

import gc
import json
import logging
import os
import shutil
import sys
import threading
import time
import urllib.error
import urllib.request
from collections import OrderedDict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, Iterable, Optional

import numpy as np

from . import languages

logger = logging.getLogger("malanguage_server.models")

#: How many models may stay loaded at once (LRU eviction beyond this).
MAX_LOADED_MODELS = 2

#: Sample rate every backend is fed.
TARGET_SAMPLE_RATE = 16_000

#: Emitted on stderr so the Minecraft mod can render a progress bar.
PROGRESS_PREFIX = "MLL_PROGRESS"

HF_BASE = "https://huggingface.co"


# ======================================================================================
# Errors
# ======================================================================================


class ModelManagerError(RuntimeError):
    """Base class for model problems surfaced to the HTTP layer."""


class BackendUnavailable(ModelManagerError):
    """The backend cannot run because an optional package is missing or broken."""

    def __init__(self, message: str, *, language: str, backend: str, hint: str) -> None:
        super().__init__(message)
        self.language = language
        self.backend = backend
        self.hint = hint

    def as_dict(self) -> Dict[str, Any]:
        """JSON body for the 503 response."""
        return {
            "error": "backend_unavailable",
            "message": str(self),
            "language": self.language,
            "backend": self.backend,
            "hint": self.hint,
        }


class ModelDownloadError(ModelManagerError):
    """Downloading or verifying the model files failed."""


# ======================================================================================
# Progress reporting
# ======================================================================================


def report(stage: str, model: str, percent: float, message: str = "") -> None:
    """Print one machine-readable progress line to stderr.

    The Minecraft mod tails stderr and parses ``MLL_PROGRESS {json}``.
    """
    payload: Dict[str, Any] = {
        "stage": stage,
        "model": model,
        "percent": int(max(0.0, min(100.0, round(percent)))),
    }
    if message:
        payload["message"] = message
    try:
        print(f"{PROGRESS_PREFIX} {json.dumps(payload, ensure_ascii=False)}", file=sys.stderr, flush=True)
    except Exception:  # pragma: no cover - stderr closed / encoding issue
        pass


# ======================================================================================
# Backend descriptors
# ======================================================================================


@dataclass(frozen=True)
class FileSpec:
    """One file to fetch from a Hugging Face repository."""

    remote: str
    """Path inside the repository."""

    local: str
    """Path inside the destination directory (may contain subdirectories)."""

    approx_mb: float = 0.0
    """Approximate size, used only for progress weighting."""


@dataclass(frozen=True)
class BackendSpec:
    """Everything needed to materialize one speech-to-text backend."""

    name: str
    """Public backend name reported by ``/health`` and ``/transcribe``."""

    kind: str
    """``onnx-asr`` or ``faster-whisper``."""

    model_id: str
    """Model identifier handed to the runtime."""

    quantizations: tuple[str, ...] = ("int8", "fp32")
    """Quantization preference list, best first.  Empty means "not parameterized"."""

    description: str = ""
    """Human readable note shown by ``/health``."""

    pip_hint: str = ""
    """What to install when the runtime for this backend is missing."""


#: Name → spec.  Downloads are driven from here, so a new model is one entry.
BACKENDS: Dict[str, BackendSpec] = {
    "parakeet-v3": BackendSpec(
        name="parakeet-v3",
        kind="onnx-asr",
        model_id="nemo-parakeet-tdt-0.6b-v3",
        quantizations=("int8",),
        description="NVIDIA Parakeet TDT 0.6B v3 (ONNX int8, 25 European languages)",
        pip_hint="drop the models dir entry and re-run; onnx-asr + onnxruntime are required",
    ),
    "whisper-large-v3-turbo": BackendSpec(
        name="whisper-large-v3-turbo",
        kind="faster-whisper",
        model_id="large-v3-turbo",
        description="OpenAI Whisper large-v3-turbo (CTranslate2) for CJK and the rest",
        pip_hint="uv pip install 'malanguage-server[whisper]'  (or set MLL_WHISPER_BACKEND=onnx)",
    ),
    "whisper-small": BackendSpec(
        name="whisper-small",
        kind="faster-whisper",
        model_id="small",
        description="OpenAI Whisper small (CTranslate2) - lower RAM fallback",
        pip_hint="uv pip install 'malanguage-server[whisper]'",
    ),
}

#: ONNX Whisper used when ``MLL_WHISPER_BACKEND=onnx``: no CTranslate2, no extra install.
BACKENDS["whisper-onnx"] = BackendSpec(
    name="whisper-onnx",
    kind="onnx-asr",
    model_id="whisper-base",
    quantizations=("int8",),
    description="Whisper base (ONNX, bundled with onnx-asr) - zero extra install",
    pip_hint="onnx-asr + onnxruntime are required",
)


def backend_for(language_code: str) -> BackendSpec:
    """Resolve the backend for a normalized language code.

    Honours ``MLL_WHISPER_BACKEND=onnx`` (swap CTranslate2 Whisper for ONNX Whisper) and
    ``MLL_WHISPER_MODEL`` (``small``/``base``/``large-v3``/``large-v3-turbo``).
    """
    lang = languages.require(language_code)
    spec = BACKENDS[lang.stt_backend]

    if spec.kind == "faster-whisper":
        override = languages.whisper_backend_override()
        if override == "onnx":
            return BACKENDS["whisper-onnx"]
        model_override = languages.default_whisper_model_override()
        if model_override and model_override != spec.model_id:
            base = BACKENDS["whisper-small"] if "small" in model_override else spec
            return BackendSpec(
                name=f"whisper-{model_override}",
                kind="faster-whisper",
                model_id=model_override,
                description=f"OpenAI Whisper {model_override} (CTranslate2)",
                pip_hint=spec.pip_hint,
            )
        return spec
    return spec


# ======================================================================================
# Device selection
# ======================================================================================


def resolve_device(requested: str | None) -> tuple[str, list[str], str]:
    """Map a requested device to ``(device, onnx_providers, note)``.

    ``auto`` probes onnxruntime; ``cuda`` degrades to CPU with a warning when the GPU
    wheel is absent.  CUDA is never required.
    """
    want = (requested or os.environ.get("MLL_DEVICE") or "auto").strip().lower()
    available: list[str] = []
    try:
        import onnxruntime as ort  # noqa: PLC0415 - optional, guarded

        available = [p for p in ort.get_available_providers() if p != "AzureExecutionProvider"]
    except Exception:  # pragma: no cover - onnxruntime missing entirely
        available = []

    has_cuda = "CUDAExecutionProvider" in available

    if want in ("cuda", "gpu"):
        if has_cuda:
            return "cuda", ["CUDAExecutionProvider", "CPUExecutionProvider"], "CUDA requested and available"
        return (
            "cpu",
            ["CPUExecutionProvider"],
            "CUDA requested but onnxruntime has no CUDA provider "
            "(install onnxruntime-gpu); degraded to CPU",
        )
    if want in ("cpu",):
        return "cpu", ["CPUExecutionProvider"], "CPU pinned by configuration"
    if has_cuda:
        return "cuda", ["CUDAExecutionProvider", "CPUExecutionProvider"], "auto-detected CUDA"
    return "cpu", ["CPUExecutionProvider"], "auto: no CUDA provider, using CPU"


def physical_core_count() -> int:
    """Best-effort physical core count (thread count is a poor default for ONNX)."""
    cpu_count = os.cpu_count() or 1
    return max(1, cpu_count // 2)


# ======================================================================================
# Downloading
# ======================================================================================


class _Downloader:
    """HTTP downloader with progress callbacks and atomic writes."""

    def __init__(self, backend: str, timeout: float = 30.0) -> None:
        self._backend = backend
        self._timeout = timeout

    def fetch(self, url: str, dest: Path, *, weight: float, progress) -> None:
        """Download *url* to *dest*, streaming progress into *progress*."""
        dest.parent.mkdir(parents=True, exist_ok=True)
        tmp = dest.with_suffix(dest.suffix + ".part")
        request = urllib.request.Request(url, headers={"User-Agent": "malanguage-server/1.0"})
        try:
            with urllib.request.urlopen(request, timeout=self._timeout) as response:
                total = int(response.headers.get("Content-Length") or 0)
                done = 0
                with open(tmp, "wb") as handle:
                    while True:
                        chunk = response.read(1 << 20)
                        if not chunk:
                            break
                        handle.write(chunk)
                        done += len(chunk)
                        if total > 0:
                            progress(weight * done / total)
                        else:
                            progress(weight * 0.5)
        except urllib.error.HTTPError as exc:
            tmp.unlink(missing_ok=True)
            raise ModelDownloadError(
                f"HTTP {exc.code} while downloading {url} for {self._backend}"
            ) from exc
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            tmp.unlink(missing_ok=True)
            raise ModelDownloadError(f"network error while downloading {url}: {exc}") from exc

        tmp.replace(dest)


class _Payload:
    """A downloaded backend payload on disk."""

    def __init__(self, root: Path, quantization: Optional[str]) -> None:
        self.root = root
        self.quantization = quantization


def _parakeet_spec(quantization: str) -> BackendSpec:
    """Parakeet is served from ``istupakov/parakeet-tdt-0.6b-v3-onnx``."""
    return BackendSpec(
        name="parakeet-v3",
        kind="hf-files",
        model_id="istupakov/parakeet-tdt-0.6b-v3-onnx",
        quantizations=(quantization,),
    )


def _parakeet_files(repo: str, quantization: str) -> list[FileSpec]:
    """File list for parakeet v3, verified against the HF repository."""
    suffix = ".int8" if quantization == "int8" else ""
    files = [
        FileSpec("config.json", "config.json", 0.001),
        FileSpec("nemo128.onnx", "nemo128.onnx", 0.1),
        FileSpec("vocab.txt", "vocab.txt", 0.1),
        FileSpec(f"encoder-model{suffix}.onnx", f"encoder-model{suffix}.onnx", 652.0 if suffix else 41.8),
        FileSpec(
            f"decoder_joint-model{suffix}.onnx",
            f"decoder_joint-model{suffix}.onnx",
            18.2 if suffix else 72.5,
        ),
    ]
    if not suffix:
        # fp32 encoder weights live in an external data file.
        files.append(FileSpec("encoder-model.onnx.data", "encoder-model.onnx.data", 2435.4))
    return files


def _whisper_onnx_files(repo: str, quantization: str) -> list[FileSpec]:
    """File list for ONNX Whisper repos (``istupakov/whisper-base-onnx``)."""
    suffix = ".int8" if quantization == "int8" else ""
    return [
        FileSpec("config.json", "config.json", 0.001),
        FileSpec("vocab.json", "vocab.json", 1.0),
        FileSpec("added_tokens.json", "added_tokens.json", 0.1),
        FileSpec("merges.txt", "merges.txt", 0.5),
        FileSpec(
            f"whisper-base_beamsearch{suffix}.onnx",
            f"whisper-base_beamsearch{suffix}.onnx",
            107.2 if suffix else 398.0,
        ),
    ]


#: repo → file list builder, keyed by ``BackendSpec.model_id`` for ``hf-files`` backends.
FILE_BUILDERS = {
    "istupakov/parakeet-tdt-0.6b-v3-onnx": _parakeet_files,
    "istupakov/whisper-base-onnx": _whisper_onnx_files,
}


# ======================================================================================
# Loaded model wrappers
# ======================================================================================


class LoadedModel:
    """A loaded, ready-to-infer backend bound to one language."""

    backend: str = "unknown"

    def transcribe(self, samples: np.ndarray, language_code: str) -> tuple[str, float]:
        """Return ``(text, confidence)`` for 16 kHz mono float32 *samples*."""
        raise NotImplementedError

    def close(self) -> None:
        """Release native resources."""


class OnnxAsrModel(LoadedModel):
    """A model loaded through :mod:`onnx_asr` (parakeet, ONNX Whisper)."""

    def __init__(self, model: Any, backend: str, language_code: str) -> None:
        self._model = model
        self.backend = backend
        self._language = language_code

    def transcribe(self, samples: np.ndarray, language_code: str) -> tuple[str, float]:
        kwargs: Dict[str, Any] = {}
        if self.backend.startswith("whisper"):
            # onnx-asr expects Whisper language tokens such as ``ja`` / ``zh``.
            kwargs["language"] = languages.normalize(language_code)

        try:
            timed = self._model.with_timestamps()
            result = timed.recognize(
                samples.astype(np.float32, copy=False),
                sample_rate=TARGET_SAMPLE_RATE,
                **kwargs,
            )
        except Exception:
            logger.debug("timestamp adapter failed, falling back to plain text", exc_info=True)
            text = self._model.recognize(
                samples.astype(np.float32, copy=False),
                sample_rate=TARGET_SAMPLE_RATE,
                **kwargs,
            )
            return _clean_text(str(text)), 0.0

        text = _clean_text(getattr(result, "text", "") or "")
        return text, _confidence_from_logprobs(getattr(result, "logprobs", None))

    def close(self) -> None:
        self._model = None


class FasterWhisperModel(LoadedModel):
    """A model loaded through :mod:`faster_whisper` (CTranslate2)."""

    backend = "whisper"

    def __init__(self, model: Any) -> None:
        self._model = model

    def transcribe(self, samples: np.ndarray, language_code: str) -> tuple[str, float]:
        segments, info = self._model.transcribe(
            samples.astype(np.float32, copy=False),
            language=languages.normalize(language_code),
            beam_size=1,
            vad_filter=False,
            condition_on_previous_text=False,
            without_timestamps=True,
        )
        parts: list[str] = []
        probs: list[float] = []
        for segment in segments:
            parts.append(segment.text)
            if getattr(segment, "avg_logprob", None) is not None:
                probs.append(float(np.exp(segment.avg_logprob)))
        text = _clean_text("".join(parts))
        confidence = float(np.mean(probs)) if probs else 0.0
        return text, max(0.0, min(1.0, confidence))

    def close(self) -> None:
        self._model = None


def _clean_text(text: str) -> str:
    """Normalize whitespace produced by the decoders."""
    return " ".join(text.replace("\u3000", " ").split()).strip()


def _confidence_from_logprobs(logprobs: Optional[Iterable[float]]) -> float:
    """Average per-token log probability → probability in ``[0, 1]``."""
    if not logprobs:
        return 0.0
    values = [float(value) for value in logprobs]
    if not values:
        return 0.0
    mean = float(np.mean(values))
    return float(max(0.0, min(1.0, float(np.exp(mean)))))


# ======================================================================================
# Model manager
# ======================================================================================


@dataclass
class LoadedEntry:
    """LRU bookkeeping for one loaded model."""

    key: str
    model: LoadedModel
    language: str
    loaded_at: float = field(default_factory=time.time)


class ModelManager:
    """Owns the model cache, the loaded-model LRU and the inference lock."""

    def __init__(self, models_dir: Path, device: str = "auto") -> None:
        self.models_dir = models_dir
        self.device, self.providers, self.device_note = resolve_device(device)
        self._lock = threading.RLock()
        self._loaded: "OrderedDict[str, LoadedEntry]" = OrderedDict()
        self._loading: Dict[str, threading.Lock] = {}
        self._unavailable: Dict[str, BackendUnavailable] = {}
        self.models_dir.mkdir(parents=True, exist_ok=True)
        logger.info(
            "models dir = %s | device = %s (%s) | providers = %s",
            self.models_dir,
            self.device,
            self.device_note,
            ",".join(self.providers) or "none",
        )

    # ---------------------------------------------------------------- introspection --

    @property
    def inference_lock(self) -> threading.RLock:
        """Lock held around every ``transcribe`` call (ONNX sessions are not reentrant)."""
        return self._lock

    def loaded_models(self) -> list[str]:
        """Backend names that are currently resident, most recent first."""
        with self._lock:
            return [entry.model.backend for entry in reversed(self._loaded.values())]

    def loaded_languages(self) -> list[str]:
        """Language codes that are currently resident."""
        with self._lock:
            return [entry.language for entry in reversed(self._loaded.values())]

    def backend_name_for(self, language_code: str) -> str:
        """Which backend would serve *language_code* right now."""
        try:
            return backend_for(language_code).name
        except KeyError:
            return "unsupported"

    # --------------------------------------------------------------------- loading --

    def get(self, language_code: str) -> LoadedModel:
        """Return a loaded model for *language_code*, loading it on first use."""
        code = languages.normalize(language_code)
        lang = languages.get(code)
        if lang is None:
            raise BackendUnavailable(
                f"language {language_code!r} is not supported by this sidecar",
                language=code,
                backend="none",
                hint=f"supported languages: {', '.join(languages.supported_codes())}",
            ) from None

        spec = backend_for(code)
        key = f"{spec.name}:{spec.model_id}:{self.device}"

        with self._lock:
            if key in self._unavailable:
                raise self._unavailable[key]
            entry = self._loaded.get(key)
            if entry is not None:
                self._loaded.move_to_end(key)
                return entry.model

        # Serialize loads per key so two concurrent first-requests don't download twice.
        with self._lock:
            guard = self._loading.setdefault(key, threading.Lock())
        with guard:
            with self._lock:
                entry = self._loaded.get(key)
                if entry is not None:
                    self._loaded.move_to_end(key)
                    return entry.model

            try:
                model = self._load_uncached(spec, code)
            except BackendUnavailable as exc:
                with self._lock:
                    self._unavailable[key] = exc
                raise
            except ModelDownloadError:
                raise
            except Exception as exc:  # noqa: BLE001 - surface anything as a 503
                wrapped = BackendUnavailable(
                    f"failed to load backend {spec.name!r} for {code}: {exc}",
                    language=code,
                    backend=spec.name,
                    hint="check the server log; the model files may be incomplete",
                )
                with self._lock:
                    self._unavailable[key] = wrapped
                raise wrapped from exc

            with self._lock:
                self._loaded[key] = LoadedEntry(key=key, model=model, language=code)
                self._loaded.move_to_end(key)
                self._evict_locked()
            return model

    def _evict_locked(self) -> None:
        """Drop the least recently used models until the LRU fits the budget."""
        while len(self._loaded) > MAX_LOADED_MODELS:
            key, entry = self._loaded.popitem(last=False)
            logger.info("evicting model %s (%s) - LRU limit is %d", key, entry.language, MAX_LOADED_MODELS)
            report("unload", entry.model.backend, 100, "evicted from cache")
            try:
                entry.model.close()
            except Exception:  # pragma: no cover - best effort
                logger.debug("error while closing %s", key, exc_info=True)
            del entry
            gc.collect()

    def preload(self, language_code: str) -> None:
        """Load a language eagerly (used by ``--preload`` and the mod's warm-up)."""
        self.get(language_code)

    def unload_all(self) -> None:
        """Drop every loaded model (used on shutdown)."""
        with self._lock:
            for entry in self._loaded.values():
                try:
                    entry.model.close()
                except Exception:  # pragma: no cover
                    pass
            self._loaded.clear()
            gc.collect()

    # ---------------------------------------------------------------- backend load --

    def _load_uncached(self, spec: BackendSpec, language_code: str) -> LoadedModel:
        """Materialize and load a backend.  Runs outside the LRU lock."""
        if spec.kind == "faster-whisper":
            return self._load_faster_whisper(spec, language_code)
        if spec.kind == "hf-files":
            payload = self._ensure_payload(spec, language_code)
            return self._load_onnx_from_dir(payload.root, payload.quantization, spec.name, language_code)
        # onnx-asr built-in model ids (parakeet-v3 = nemo-parakeet-tdt-0.6b-v3).
        return self._load_onnx_builtin(spec, language_code)

    # -- onnx-asr -------------------------------------------------------------------

    def _import_onnx_asr(self, language_code: str, backend: str) -> Any:
        """Import :mod:`onnx_asr`, raising :class:`BackendUnavailable` when absent."""
        try:
            import onnx_asr  # noqa: PLC0415 - optional dependency, lazy

            return onnx_asr
        except Exception as exc:  # noqa: BLE001
            raise BackendUnavailable(
                f"speech-to-text backend {backend!r} needs the 'onnx-asr' package, "
                f"which is not importable ({exc.__class__.__name__}: {exc})",
                language=language_code,
                backend=backend,
                hint="uv pip install onnx-asr onnxruntime",
            ) from exc

    def _session_options(self) -> Any:
        """Build ``onnxruntime.SessionOptions`` sized for this machine."""
        import onnxruntime as ort  # noqa: PLC0415 - guarded by caller

        options = ort.SessionOptions()
        options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        options.intra_op_num_threads = physical_core_count()
        options.inter_op_num_threads = 1
        options.log_severity_level = 3
        return options

    def _load_onnx_builtin(self, spec: BackendSpec, language_code: str) -> LoadedModel:
        """Load a model that ``onnx-asr`` knows by name (``nemo-parakeet-tdt-0.6b-v3``)."""
        onnx_asr = self._import_onnx_asr(language_code, spec.name)

        local_dir = self.models_dir / spec.model_id
        quantization = spec.quantizations[0] if spec.quantizations else None

        report("load", spec.name, 0, "resolving model")
        if _looks_complete(local_dir):
            # Files already cached by a previous run: load straight from disk, no network.
            logger.info("loading %s from cache %s", spec.name, local_dir)
            return self._load_onnx_from_dir(local_dir, quantization, spec.name, language_code)

        self._download_snapshot_repo(_repo_for_builtin(spec.model_id), local_dir, spec, quantization)

        return self._load_onnx_from_dir(local_dir, quantization, spec.name, language_code)

    def _load_onnx_from_dir(
        self,
        directory: Path,
        quantization: Optional[str],
        backend: str,
        language_code: str,
    ) -> LoadedModel:
        """Load an onnx-asr model from an explicit directory (no hub lookup)."""
        onnx_asr = self._import_onnx_asr(language_code, backend)
        report("load", backend, 60, "creating inference session")
        started = time.time()
        try:
            model = onnx_asr.load_model(
                None,
                path=directory,
                quantization=quantization,
                sess_options=self._session_options(),
                providers=self.providers,
            )
        except TypeError:
            # Newer/older onnx-asr revisions may want the model id explicitly.
            model = onnx_asr.load_model(
                backend_model_id(backend),
                path=directory,
                quantization=quantization,
                sess_options=self._session_options(),
                providers=self.providers,
            )
        logger.info("%s loaded from %s in %.1fs", backend, directory, time.time() - started)
        report("load", backend, 100, "ready")
        return OnnxAsrModel(model, backend, language_code)

    # -- faster-whisper -------------------------------------------------------------

    def _load_faster_whisper(self, spec: BackendSpec, language_code: str) -> LoadedModel:
        """Load Whisper through CTranslate2, or explain precisely why we cannot."""
        try:
            from faster_whisper import WhisperModel  # noqa: PLC0415 - optional dependency
        except Exception as exc:  # noqa: BLE001
            raise BackendUnavailable(
                f"language {language_code!r} needs Whisper ({spec.model_id}) because parakeet-v3 "
                f"only covers 25 European languages, and the 'faster-whisper' package is not "
                f"installed ({exc.__class__.__name__}: {exc})",
                language=language_code,
                backend=spec.name,
                hint=(
                    "uv pip install faster-whisper   (or 'malanguage-server[whisper]')"
                    "  --  alternatively set MLL_WHISPER_BACKEND=onnx to use the bundled"
                    " ONNX Whisper base model with no extra download"
                ),
            ) from exc

        download_root = self.models_dir / "faster-whisper"
        download_root.mkdir(parents=True, exist_ok=True)
        report("download", spec.name, 0, "resolving Whisper weights")
        device = "cuda" if self.device == "cuda" else "cpu"
        compute_type = "float16" if device == "cuda" else "int8"

        try:
            model = WhisperModel(
                spec.model_id,
                device=device,
                compute_type=compute_type,
                download_root=str(download_root),
                cpu_threads=physical_core_count(),
                num_workers=1,
            )
        except Exception as exc:  # noqa: BLE001
            if device == "cuda":
                logger.warning("CUDA Whisper load failed (%s); retrying on CPU", exc)
                model = WhisperModel(
                    spec.model_id,
                    device="cpu",
                    compute_type="int8",
                    download_root=str(download_root),
                    cpu_threads=physical_core_count(),
                    num_workers=1,
                )
                self.device = "cpu"
            else:
                raise
        report("load", spec.name, 100, "ready")
        return FasterWhisperModel(model)

    # ------------------------------------------------------------------- downloads --

    def _ensure_payload(self, spec: BackendSpec, language_code: str) -> _Payload:
        """Make sure the files for a ``hf-files`` backend exist locally."""
        repo = spec.model_id
        builder = FILE_BUILDERS.get(repo)
        if builder is None:
            raise ModelDownloadError(f"no file list registered for repository {repo}")

        root = self.models_dir / repo.replace("/", "__")
        last_error: Optional[Exception] = None
        for quantization in spec.quantizations:
            target = root / (quantization or "fp32")
            files = builder(repo, quantization)
            if _files_present(target, files):
                logger.info("payload for %s already cached in %s", spec.name, target)
                return _Payload(target, quantization)
            try:
                self._download_files(repo, files, target, spec.name)
                return _Payload(target, quantization)
            except ModelDownloadError as exc:
                last_error = exc
                logger.warning("quantization %s failed for %s: %s", quantization, spec.name, exc)
                report("download", spec.name, 0, f"{quantization} unavailable, trying next")
        raise ModelDownloadError(
            f"could not fetch any variant of {spec.name}: {last_error}"
        ) from last_error

    def _download_files(
        self, repo: str, files: list[FileSpec], target: Path, backend: str
    ) -> None:
        """Download *files* from *repo* into *target*, reporting progress."""
        total_weight = sum(f.approx_mb for f in files) or float(len(files))
        fetched = 0.0
        downloader = _Downloader(backend)
        target.mkdir(parents=True, exist_ok=True)

        for spec_file in files:
            destination = target / spec_file.local
            weight = spec_file.approx_mb or 1.0
            if destination.exists() and destination.stat().st_size > 0:
                fetched += weight
                report("download", backend, 100 * fetched / total_weight, f"cached {spec_file.local}")
                continue
            url = f"{HF_BASE}/{repo}/resolve/main/{spec_file.remote}"
            logger.info("downloading %s -> %s", url, destination)
            base = 100 * fetched / total_weight
            span = 100 * weight / total_weight

            def progress(fraction: float, _base=base, _span=span, name=spec_file.local) -> None:
                report("download", backend, _base + _span * fraction, name)

            downloader.fetch(url, destination, weight=1.0, progress=progress)
            fetched += weight
            report("download", backend, 100 * fetched / total_weight, f"done {spec_file.local}")

        report("download", backend, 100, "download complete")

    def _download_snapshot_repo(
        self, repo: str, target: Path, spec: BackendSpec, quantization: Optional[str]
    ) -> None:
        """Download a built-in onnx-asr model by delegating file selection to onnx-asr.

        ``onnx-asr`` knows exactly which files each model type needs; we drive
        :meth:`Resolver.resolve_model` only when ``huggingface_hub`` is available, and
        otherwise fall back to a static file list.
        """
        builder = FILE_BUILDERS.get(repo)
        if builder is not None:
            files = builder(repo, quantization or "fp32")
            self._download_files(repo, files, target, spec.name)
            return

        try:
            from huggingface_hub import snapshot_download  # noqa: PLC0415 - optional
        except Exception as exc:  # noqa: BLE001
            raise BackendUnavailable(
                f"downloading {spec.name} needs huggingface_hub, which is not installed "
                f"({exc.__class__.__name__}: {exc})",
                language="",
                backend=spec.name,
                hint="uv pip install huggingface_hub",
            ) from exc

        report("download", spec.name, 5, "contacting huggingface")
        try:
            snapshot_download(repo_id=repo, local_dir=str(target))
        except Exception as exc:  # noqa: BLE001
            raise ModelDownloadError(f"snapshot_download failed for {repo}: {exc}") from exc
        report("download", spec.name, 100, "download complete")


# ======================================================================================
# Helpers
# ======================================================================================


#: ``onnx-asr`` built-in id → Hugging Face repository.
_BUILTIN_REPOS = {
    "nemo-parakeet-tdt-0.6b-v3": "istupakov/parakeet-tdt-0.6b-v3-onnx",
    "whisper-base": "istupakov/whisper-base-onnx",
}


def _repo_for_builtin(model_id: str) -> str:
    """Hugging Face repository backing an ``onnx-asr`` built-in model id."""
    return _BUILTIN_REPOS.get(model_id, model_id)


def backend_model_id(backend: str) -> str:
    """``onnx-asr`` model id used as a hint when loading from a directory."""
    spec = BACKENDS.get(backend)
    if spec is not None:
        return spec.model_id
    return "nemo-parakeet-tdt-0.6b-v3"


def _files_present(target: Path, files: list[FileSpec]) -> bool:
    """True when every file exists and is non-empty."""
    return all((target / f.local).exists() and (target / f.local).stat().st_size > 0 for f in files)


def _looks_complete(directory: Path) -> bool:
    """Cheap completeness probe for a cached model directory."""
    if not directory.exists() or not (directory / "config.json").exists():
        return False
    return any(directory.glob("*.onnx"))


def default_models_dir() -> Path:
    """``<user-config>/MaLanguageLearning/models`` honouring ``MLL_MODELS_DIR``.

    Uses the platform config location so the sidecar never writes next to the game jar:

    * Windows  ``%APPDATA%/MaLanguageLearning/models``
    * macOS    ``~/Library/Application Support/MaLanguageLearning/models``
    * Linux    ``$XDG_CONFIG_HOME/MaLanguageLearning/models`` (``~/.config`` fallback)
    """
    override = os.environ.get("MLL_MODELS_DIR")
    if override:
        return Path(override).expanduser()
    if sys.platform == "win32":
        base = Path(os.environ.get("APPDATA") or Path.home() / "AppData" / "Roaming")
    elif sys.platform == "darwin":
        base = Path.home() / "Library" / "Application Support"
    else:
        base = Path(os.environ.get("XDG_CONFIG_HOME") or Path.home() / ".config")
    return base / "MaLanguageLearning" / "models"


def free_disk_gb(path: Path) -> float:
    """Free space at *path* in GB (0.0 when it cannot be determined)."""
    try:
        target = path
        while not target.exists() and target != target.parent:
            target = target.parent
        return shutil.disk_usage(target).free / (1024**3)
    except Exception:  # pragma: no cover
        return 0.0
