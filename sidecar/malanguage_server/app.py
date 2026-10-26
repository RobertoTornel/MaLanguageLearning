"""FastAPI application: the local HTTP contract the Minecraft mod calls.

Endpoints
---------
``GET  /health``        readiness, device, loaded models
``GET  /languages``     the language table (STT backend + TTS voice + scripts)
``POST /transcribe``    raw WAV bytes + ``X-MLL-Language`` header → JSON text
``POST /synthesize``    JSON text → WAV bytes (404 when no voice exists)
``POST /transliterate`` JSON text → primary/secondary/phonetic/alternatives

The app always starts.  Every heavy dependency is imported lazily by
:mod:`malanguage_server.model_manager` / :mod:`malanguage_server.tts`, and failures
surface as structured 503/404 payloads instead of crashes.
"""

from __future__ import annotations

import io
import logging
import math
import threading
import time
from pathlib import Path
from typing import Any, Dict, Optional

import numpy as np
from fastapi import Body, FastAPI, Header, Request
from fastapi.responses import JSONResponse, Response

from . import __version__, languages, translit
from .model_manager import (
    TARGET_SAMPLE_RATE,
    BackendUnavailable,
    ModelDownloadError,
    ModelManager,
)
from .tts import TextToSpeech, TtsUnavailable, VoiceUnavailable

logger = logging.getLogger("malanguage_server.app")

#: Hard cap on a single upload (a 15 s clip at 16 kHz 16-bit is ~480 KB).
MAX_AUDIO_BYTES = 8 * 1024 * 1024

#: Reject clips longer than this (the mod records 15 s at most).
MAX_AUDIO_SECONDS = 30.0

#: Reject clips shorter than this - nothing to recognize.
MIN_AUDIO_SECONDS = 0.05


# ======================================================================================
# Audio decoding
# ======================================================================================


class AudioError(ValueError):
    """The uploaded audio could not be decoded."""

    def as_dict(self) -> Dict[str, Any]:
        """JSON error body."""
        return {
            "error": "bad_audio",
            "message": str(self),
            "hint": "send 16 kHz mono PCM WAV; 16-bit integer and float32 are both accepted",
        }


def decode_wav(data: bytes) -> tuple[np.ndarray, int]:
    """Decode WAV *data* to ``(float32 mono samples, sample rate)``.

    Accepts 16-bit and 32-bit float PCM (plus 8/24/32-bit integer), mono or multi
    channel.  ``soundfile`` is preferred because it handles float WAVs and odd chunk
    layouts; the stdlib :mod:`wave` module is the fallback so this never hard-fails.
    """
    if not data:
        raise AudioError("empty request body")
    if len(data) > MAX_AUDIO_BYTES:
        raise AudioError(f"audio payload too large ({len(data)} bytes, limit {MAX_AUDIO_BYTES})")

    error: Optional[Exception] = None
    try:
        return _decode_with_soundfile(data)
    except Exception as exc:  # noqa: BLE001 - fall through to the stdlib decoder
        error = exc
        logger.debug("soundfile decode failed (%s); trying stdlib wave", exc)

    try:
        return _decode_with_wave(data)
    except Exception as exc:  # noqa: BLE001
        raise AudioError(
            f"could not decode audio as WAV: {exc} (soundfile: {error})"
        ) from exc


def _decode_with_soundfile(data: bytes) -> tuple[np.ndarray, int]:
    """Decode using libsndfile via :mod:`soundfile`."""
    import soundfile as sf  # noqa: PLC0415 - optional-safe

    samples, rate = sf.read(io.BytesIO(data), dtype="float32", always_2d=True)
    mono = samples.mean(axis=1) if samples.shape[1] > 1 else samples[:, 0]
    return np.ascontiguousarray(mono, dtype=np.float32), int(rate)


def _decode_with_wave(data: bytes) -> tuple[np.ndarray, int]:
    """Decode canonical PCM WAVs with the standard library (16-bit / float32 / 8 / 24 / 32)."""
    import wave  # noqa: PLC0415

    with wave.open(io.BytesIO(data), "rb") as handle:
        channels = handle.getnchannels()
        width = handle.getsampwidth()
        rate = handle.getframerate()
        frames = handle.getnframes()
        raw = handle.readframes(frames)

    if width == 2:
        array = np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768.0
    elif width == 4:
        # WAV stores float32 little-endian for IEEE_FLOAT; try float first, then int32.
        floats = np.frombuffer(raw, dtype="<f4")
        if np.all(np.isfinite(floats)) and floats.size and float(np.max(np.abs(floats))) <= 1.5:
            array = floats.astype(np.float32)
        else:
            array = np.frombuffer(raw, dtype="<i4").astype(np.float32) / 2147483648.0
    elif width == 1:
        array = (np.frombuffer(raw, dtype=np.uint8).astype(np.float32) - 128.0) / 128.0
    elif width == 3:
        bytes_ = np.frombuffer(raw, dtype=np.uint8).reshape(-1, 3).astype(np.int32)
        values = bytes_[:, 0] | (bytes_[:, 1] << 8) | (bytes_[:, 2] << 16)
        values = np.where(values & 0x800000, values - 0x1000000, values)
        array = values.astype(np.float32) / 8388608.0
    else:
        raise AudioError(f"unsupported sample width: {width} bytes")

    if channels > 1:
        array = array.reshape(-1, channels).mean(axis=1)
    return np.ascontiguousarray(array, dtype=np.float32), int(rate)


def resample(samples: np.ndarray, source_rate: int, target_rate: int = TARGET_SAMPLE_RATE) -> np.ndarray:
    """Resample mono float32 *samples* with numpy only (no scipy).

    Downsampling applies a short moving-average anti-alias filter first, which is crude
    but well within what a 0.6B speech model tolerates; upsampling interpolates.
    """
    if source_rate == target_rate or samples.size == 0 or source_rate <= 0:
        return samples.astype(np.float32, copy=False)

    ratio = target_rate / float(source_rate)
    if ratio < 1.0:
        window = max(1, int(round(1.0 / ratio)))
        if window > 1:
            kernel = np.ones(window, dtype=np.float32) / window
            samples = np.convolve(samples, kernel, mode="same").astype(np.float32)

    target_length = max(1, int(round(samples.size * ratio)))
    source_positions = np.arange(samples.size, dtype=np.float64)
    target_positions = np.linspace(0.0, (samples.size - 1) if samples.size > 1 else 0.0, target_length)
    resampled = np.interp(target_positions, source_positions, samples.astype(np.float64))
    return rescaled_if_needed(resampled.astype(np.float32))


def rescaled_if_needed(samples: np.ndarray) -> np.ndarray:
    """Normalize integer-scaled audio into ``[-1, 1]`` and strip DC offset."""
    if samples.size == 0:
        return samples
    peak = float(np.max(np.abs(samples)))
    if not math.isfinite(peak) or peak == 0.0:
        return samples
    peak = max(peak, 1e-9)
    if peak > 1.0:
        samples = samples / peak
    dc = float(np.mean(samples))
    if abs(dc) > 1e-4:
        samples = samples - dc
    return np.ascontiguousarray(samples, dtype=np.float32)


def prepare_audio(data: bytes) -> tuple[np.ndarray, int]:
    """Decode + validate + resample to 16 kHz mono float32."""
    samples, rate = decode_wav(data)
    duration = samples.size / float(rate or 1)
    if duration < MIN_AUDIO_SECONDS:
        raise AudioError(f"audio too short ({duration:.3f}s, minimum {MIN_AUDIO_SECONDS}s)")
    if duration > MAX_AUDIO_SECONDS:
        raise AudioError(f"audio too long ({duration:.1f}s, maximum {MAX_AUDIO_SECONDS:.0f}s)")
    return resample(samples, rate, TARGET_SAMPLE_RATE), rate


# ======================================================================================
# Application factory
# ======================================================================================


class ServerState:
    """Process-wide state shared by the request handlers."""

    def __init__(self, models_dir: Path, device: str = "auto") -> None:
        self.started_at = time.time()
        self.models_dir = models_dir
        self.models = ModelManager(models_dir, device=device)
        self.tts = TextToSpeech(models_dir)
        self.lock = threading.RLock()

    def shutdown(self) -> None:
        """Release every native resource held by the sidecar."""
        self.models.unload_all()


def create_app(models_dir: Path, device: str = "auto") -> FastAPI:
    """Build the FastAPI app bound to *models_dir* and a resolved *device*."""
    state = ServerState(models_dir, device)
    app = FastAPI(
        title="MaLanguageLearning sidecar",
        version=__version__,
        docs_url="/docs",
        openapi_url="/openapi.json",
    )
    app.state.mll = state

    # ---------------------------------------------------------------------- health --

    @app.get("/health")
    def health() -> Dict[str, Any]:
        """Liveness + capability report.  Always answers 200."""
        return {
            "status": "ok",
            "version": __version__,
            "device": state.models.device,
            "models_loaded": state.models.loaded_models(),
            "loaded_languages": state.models.loaded_languages(),
            "models_dir": str(state.models_dir),
            "tts_voices_loaded": state.tts.loaded_voices(),
            "device_note": state.models.device_note,
            "backends": sorted({languages.LANGUAGES[c].stt_backend for c in languages.LANGUAGES}),
            "languages_count": len(languages.LANGUAGES),
            "uptime_s": round(time.time() - state.started_at, 1),
            "translit": translit.describe(),
        }

    @app.get("/")
    def root() -> Dict[str, Any]:
        """Tiny index so a browser hitting the port shows something useful."""
        return {
            "name": "malanguage-server",
            "version": __version__,
            "endpoints": ["/health", "/languages", "/transcribe", "/synthesize", "/transliterate"],
        }

    # ------------------------------------------------------------------- languages --

    @app.get("/languages")
    def get_languages() -> list[Dict[str, Any]]:
        """The language table exactly as the mod's config screen expects it."""
        result: list[Dict[str, Any]] = []
        for code in languages.supported_codes():
            entry = languages.LANGUAGES[code]
            result.append(
                {
                    "code": entry.code,
                    "name": entry.name,
                    "stt_backend": state.models.backend_name_for(code),
                    "tts_voice": entry.tts_locale if entry.tts_voice else "",
                    "tts_voice_id": entry.tts_voice,
                    "tts_available": entry.tts_available,
                    "scripts": list(entry.scripts),
                    "aliases": list(entry.alt_codes),
                }
            )
        return result

    # ------------------------------------------------------------------ transcribe --

    @app.post("/transcribe")
    def transcribe(
        request: Request,
        body: bytes = Body(..., media_type="application/octet-stream"),
        language: Optional[str] = Header(default=None, alias="X-MLL-Language"),
    ) -> Response:
        """Speech-to-text for one utterance in the requested language."""
        started = time.perf_counter()
        code = languages.normalize(language)
        if languages.get(code) is None:
            return JSONResponse(
                status_code=400,
                content={
                    "error": "unsupported_language",
                    "message": f"language {language!r} is not supported",
                    "language": code,
                    "supported": languages.supported_codes(),
                },
            )

        try:
            samples, _source_rate = prepare_audio(body)
        except AudioError as exc:
            return JSONResponse(status_code=400, content=exc.as_dict())

        try:
            model = state.models.get(code)
        except BackendUnavailable as exc:
            logger.warning("backend unavailable for %s: %s", code, exc)
            return JSONResponse(status_code=503, content=exc.as_dict())
        except ModelDownloadError as exc:
            return JSONResponse(
                status_code=503,
                content={
                    "error": "model_download_failed",
                    "message": str(exc),
                    "language": code,
                    "hint": "check connectivity to huggingface.co or pre-seed MLL_MODELS_DIR",
                },
            )

        report_started = time.perf_counter()
        try:
            # ONNX sessions are not safe for concurrent Run() calls from many threads.
            with state.models.inference_lock:
                text, confidence = model.transcribe(samples, code)
        except Exception as exc:  # noqa: BLE001 - never leak a 500 for a bad clip
            logger.exception("transcription failed for %s", code)
            return JSONResponse(
                status_code=500,
                content={
                    "error": "transcription_failed",
                    "message": f"{exc.__class__.__name__}: {exc}",
                    "language": code,
                },
            )

        latency_ms = int(round((time.perf_counter() - started) * 1000))
        logger.info(
            "transcribe %s -> %r (conf %.2f) in %d ms [inference %d ms]",
            code,
            text,
            confidence,
            latency_ms,
            int(round((time.perf_counter() - report_started) * 1000)),
        )
        return JSONResponse(
            content={
                "text": text,
                "confidence": round(float(confidence), 4),
                "language": code,
                "model": model.backend,
                "latency_ms": latency_ms,
            }
        )

    # ------------------------------------------------------------------ synthesize --

    @app.post("/synthesize")
    def synthesize(payload: Dict[str, Any] = Body(...)) -> Response:
        """Text-to-speech.  Returns ``audio/wav`` or 404 when no voice exists."""
        text = str(payload.get("text", ""))
        code = languages.normalize(payload.get("language"))
        if not text.strip():
            return JSONResponse(
                status_code=400,
                content={"error": "empty_text", "message": "field 'text' is required"},
            )

        try:
            audio = state.tts.synthesize(text, code)
        except VoiceUnavailable as exc:
            logger.info("synthesize %s -> %d (%s)", code, exc.status, exc)
            return JSONResponse(status_code=exc.status, content=exc.as_dict())
        except TtsUnavailable as exc:
            return JSONResponse(status_code=503, content=exc.as_dict())

        return Response(
            content=audio,
            media_type="audio/wav",
            headers={
                "X-MLL-Voice": state.tts.voice_name_for(code),
                "X-MLL-Language": code,
            },
        )

    # --------------------------------------------------------------- transliterate --

    @app.post("/transliterate")
    def transliterate(payload: Dict[str, Any] = Body(...)) -> Response:
        """Script conversion + phonetics.  Never requires a model."""
        text = str(payload.get("text", ""))
        code = languages.normalize(payload.get("language"))
        primary_script = str(payload.get("primary_script", "") or "")
        secondary_script = str(payload.get("secondary_script", "") or "")
        result = translit.transliterate(text, code, primary_script, secondary_script)
        body = result.as_dict()
        body["language"] = code
        return JSONResponse(content=body)

    @app.on_event("shutdown")
    def _shutdown() -> None:
        """Free model memory on the way out."""
        state.shutdown()

    return app
