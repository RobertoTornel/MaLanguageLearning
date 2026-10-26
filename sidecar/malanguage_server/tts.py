"""Text-to-speech through Piper.

Piper is used because it is small, offline after the first download, ships ONNX
voices for ~40 languages, and pulls no system dependencies on Windows/macOS/Linux.

Everything here is optional-safe: ``piper`` is imported lazily, and a missing voice
answers 404 (so the mod can beep) rather than a 500.
"""

from __future__ import annotations

import io
import logging
import threading
import time
import wave
from collections import OrderedDict
from pathlib import Path
from typing import Any, Optional

from . import languages
from .model_manager import report

logger = logging.getLogger("malanguage_server.tts")

#: How many voices to keep resident (a voice is ~60 MB of ONNX weights).
MAX_LOADED_VOICES = 2

HF_VOICES_BASE = "https://huggingface.co/rhasspy/piper-voices/resolve/main"


class TtsUnavailable(RuntimeError):
    """Piper itself cannot run (package missing or broken)."""

    def as_dict(self) -> dict[str, object]:
        """JSON error body for the 503 response."""
        return {
            "error": "tts_unavailable",
            "message": str(self),
            "hint": "uv pip install piper-tts",
        }


class VoiceUnavailable(RuntimeError):
    """No usable voice for the requested language, or synthesis could not run.

    ``status`` is the HTTP status the client should see:

    * ``404`` — the language has no usable voice (no voice upstream, or its phonemizer
      package is missing).  The mod falls back to a beep; this is a *capability* answer,
      not an error condition to retry.
    * ``502`` — a voice exists and is loadable but synthesis itself failed.
    """

    def __init__(
        self,
        message: str,
        *,
        language: str,
        voice: str = "",
        status: int = 404,
    ) -> None:
        super().__init__(message)
        self.language = language
        self.voice = voice
        self.status = status

    def as_dict(self) -> dict[str, object]:
        """JSON error body for the response."""
        return {
            "error": "no_voice" if self.status == 404 else "synthesis_failed",
            "message": str(self),
            "language": self.language,
            "voice": self.voice,
            "hint": "the client should fall back to a beep"
            if self.status == 404
            else "retry, or check the server log",
        }


class _Voice:
    """One loaded Piper voice."""

    def __init__(self, engine: Any, sample_rate: int, channels: int) -> None:
        self.engine = engine
        self.sample_rate = sample_rate
        self.channels = channels


class TextToSpeech:
    """Lazily downloads and caches Piper voices, then synthesizes WAV bytes."""

    def __init__(self, models_dir: Path) -> None:
        self._dir = models_dir / "tts"
        self._dir.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self._voices: "OrderedDict[str, _Voice]" = OrderedDict()
        self._failure: Optional[str] = None

    # ------------------------------------------------------------------ introspection --

    def voice_name_for(self, language_code: str) -> str:
        """Piper voice id for a language, or ``""`` when there is none."""
        entry = languages.get(language_code)
        return entry.tts_voice if entry is not None else ""

    def available(self, language_code: str) -> bool:
        """Whether ``/synthesize`` can serve this language."""
        return bool(self.voice_name_for(language_code))

    def loaded_voices(self) -> list[str]:
        """Voice ids currently resident."""
        with self._lock:
            return list(self._voices.keys())

    # -------------------------------------------------------------------- synthesis --

    def synthesize(self, text: str, language_code: str) -> bytes:
        """Render *text* to 16-bit mono WAV bytes.

        Raises:
            VoiceUnavailable: no voice for the language (HTTP 404).
            TtsUnavailable: Piper is missing or broken (HTTP 503).
        """
        code = languages.normalize(language_code)
        voice_id = self.voice_name_for(code)
        if not voice_id:
            raise VoiceUnavailable(
                f"no text-to-speech voice is available for language {code!r}",
                language=code,
            )

        cleaned = (text or "").strip()
        if not cleaned:
            raise VoiceUnavailable(
                "empty text", language=code, voice=voice_id
            )

        voice = self._get_voice(voice_id, code)

        report("tts", voice_id, 50, "synthesizing")
        started = time.time()
        try:
            with self._lock:
                payload = self._render_wav(voice, cleaned)
        except VoiceUnavailable:
            raise
        except Exception as exc:  # noqa: BLE001
            missing_phonemizer = getattr(exc, "name", "") in _PHONEMIZER_HINTS or any(
                module in str(exc) for module in _PHONEMIZER_HINTS
            )
            raise VoiceUnavailable(
                f"synthesis failed for voice {voice_id}: {_explain(exc)}",
                language=code,
                voice=voice_id,
                # A missing phonemizer is a capability gap: let the mod beep instead of
                # retrying a request that can never succeed.
                status=404 if missing_phonemizer else 502,
            ) from exc

        logger.info(
            "synthesized %d bytes for %r (%s) in %.2fs",
            len(payload),
            cleaned[:40],
            voice_id,
            time.time() - started,
        )
        report("tts", voice_id, 100, "ready")
        return payload

    def _render_wav(self, voice: _Voice, text: str) -> bytes:
        """Render *text* and serialize the chunks as one 16-bit mono WAV.

        Chunks are collected with :meth:`PiperVoice.synthesize` rather than
        ``synthesize_wav`` so that phonemizer errors surface as themselves instead of a
        misleading :mod:`wave` "channels not specified" error, and so the whole
        utterance is joined into a single WAV (``synthesize_wav`` already does this, but
        it writes through a file object we would have to reparse).
        """
        chunks = list(voice.engine.synthesize(text))
        if not chunks:
            raise VoiceUnavailable(
                f"the voice produced no audio for {text!r}", language="", voice=""
            )

        sample_rate = int(chunks[0].sample_rate or voice.sample_rate)
        channels = int(getattr(chunks[0], "sample_channels", 1) or 1)
        width = int(getattr(chunks[0], "sample_width", 2) or 2)

        frames = b"".join(chunk.audio_int16_bytes for chunk in chunks)
        buffer = io.BytesIO()
        with wave.open(buffer, "wb") as handle:
            handle.setnchannels(channels)
            handle.setsampwidth(width)
            handle.setframerate(sample_rate)
            handle.writeframes(frames)
        return buffer.getvalue()

    # ----------------------------------------------------------------- voice loading --

    def _get_voice(self, voice_id: str, language_code: str) -> _Voice:
        """Return a loaded voice, downloading it on first use."""
        with self._lock:
            cached = self._voices.get(voice_id)
            if cached is not None:
                self._voices.move_to_end(voice_id)
                return cached

        model_path, config_path = self._ensure_voice_files(voice_id, language_code)

        engine = self._load_engine(model_path, config_path, voice_id, language_code)
        sample_rate, channels = _probe_wav_format(engine)
        voice = _Voice(engine, sample_rate, channels)

        with self._lock:
            self._voices[voice_id] = voice
            self._voices.move_to_end(voice_id)
            while len(self._voices) > MAX_LOADED_VOICES:
                evicted, _ = self._voices.popitem(last=False)
                logger.info("evicting TTS voice %s", evicted)
        return voice

    def _import_piper(self) -> Any:
        """Import :mod:`piper`, translating failures into :class:`TtsUnavailable`."""
        if self._failure is not None:
            raise TtsUnavailable(self._failure)
        try:
            import piper  # noqa: PLC0415 - optional dependency

            return piper
        except Exception as exc:  # noqa: BLE001
            self._failure = f"piper-tts is not importable ({exc.__class__.__name__}: {exc})"
            raise TtsUnavailable(self._failure) from exc

    def _load_engine(self, model_path: Path, config_path: Path, voice_id: str, code: str) -> Any:
        """Load a Piper voice from disk."""
        piper = self._import_piper()
        report("load", voice_id, 50, "loading voice")
        try:
            engine = piper.PiperVoice.load(str(model_path), str(config_path))
        except Exception as exc:  # noqa: BLE001
            raise VoiceUnavailable(
                f"could not load Piper voice {voice_id}: {exc.__class__.__name__}: {exc}",
                language=code,
                voice=voice_id,
            ) from exc
        logger.info("piper voice %s loaded", voice_id)
        return engine

    def _ensure_voice_files(self, voice_id: str, language_code: str) -> tuple[Path, Path]:
        """Download the ``.onnx`` + ``.onnx.json`` for *voice_id* when missing."""
        model_path = self._dir / f"{voice_id}.onnx"
        config_path = self._dir / f"{voice_id}.onnx.json"
        if model_path.exists() and model_path.stat().st_size > 0 and config_path.exists():
            return model_path, config_path

        report("download", voice_id, 5, "fetching voice")
        try:
            from piper.download_voices import download_voice  # noqa: PLC0415

            download_voice(voice_id, self._dir)
        except Exception as exc:  # noqa: BLE001 - fall back to a direct HTTP fetch
            logger.warning("piper.download_voices failed for %s (%s); using direct HTTP", voice_id, exc)
            try:
                self._download_direct(voice_id, model_path, config_path)
            except Exception as direct_exc:  # noqa: BLE001
                raise VoiceUnavailable(
                    f"could not download TTS voice {voice_id}: {direct_exc}",
                    language=language_code,
                    voice=voice_id,
                ) from direct_exc

        if not model_path.exists():
            raise VoiceUnavailable(
                f"TTS voice {voice_id} downloaded but {model_path.name} is missing",
                language=language_code,
                voice=voice_id,
            )
        report("download", voice_id, 100, "voice ready")
        return model_path, config_path

    def _download_direct(self, voice_id: str, model_path: Path, config_path: Path) -> None:
        """Fetch a voice from the Piper HF repo without piper's own loader."""
        import urllib.request

        # en_US-amy-medium → en/en_US/amy/medium/en_US-amy-medium.onnx
        try:
            locale, name, quality = voice_id.split("-", 2)
            family = locale.split("_")[0]
        except ValueError as exc:
            raise VoiceUnavailable(
                f"malformed Piper voice id {voice_id!r}", language="", voice=voice_id
            ) from exc

        relative = f"{family}/{locale}/{name}/{quality}/{voice_id}"
        for remote, destination in (
            (f"{relative}.onnx", model_path),
            (f"{relative}.onnx.json", config_path),
        ):
            url = f"{HF_VOICES_BASE}/{remote}"
            logger.info("downloading %s", url)
            request = urllib.request.Request(url, headers={"User-Agent": "malanguage-server/1.0"})
            with urllib.request.urlopen(request, timeout=60) as response:
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(response.read())


def _probe_wav_format(engine: Any) -> tuple[int, int]:
    """Determine the sample rate/channels a voice emits (defaults 22050/mono)."""
    sample_rate = getattr(engine, "config", None)
    rate = getattr(sample_rate, "sample_rate", None) if sample_rate is not None else None
    try:
        return int(rate or 22050), 1
    except (TypeError, ValueError):
        return 22050, 1


#: Piper voices that need an extra phonemizer package, and what to install.
_PHONEMIZER_HINTS: dict[str, str] = {
    "pyopenjtalk": "uv pip install pyopenjtalk-plus   (needed by Piper's Japanese voice)",
    "pyopenjtalk-plus": "uv pip install pyopenjtalk-plus   (needed by Piper's Japanese voice)",
    "g2pW": "uv pip install 'piper-tts[zh]'   (needed by Piper's Chinese voice)",
    "tltk": "uv pip install 'piper-tts[th]'   (needed by Piper's Thai voice)",
}


def _explain(exc: BaseException) -> str:
    """Turn a Piper failure into an actionable sentence."""
    text = f"{exc.__class__.__name__}: {exc}"
    missing = getattr(exc, "name", None) or ""
    hint = _PHONEMIZER_HINTS.get(missing)
    if hint is None:
        for module, module_hint in _PHONEMIZER_HINTS.items():
            if module in str(exc):
                hint = module_hint
                break
    if hint:
        return f"{text} -- {hint}"
    return text
