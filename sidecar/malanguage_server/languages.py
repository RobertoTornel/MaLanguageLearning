"""Language registry for the MaLanguageLearning sidecar.

The registry is the single source of truth that ties together:

* the BCP-47 language code the Minecraft mod sends,
* the speech-to-text backend that serves that language,
* the text-to-speech voice used for the fallback prompt,
* the writing systems available for transliteration.

Backends are *names*, not objects: :mod:`malanguage_server.model_manager` resolves a
name to a concrete, lazily loaded model.  Adding a language therefore never requires
touching the models code.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from typing import Dict, Iterable

# --------------------------------------------------------------------------------------
# Backend identifiers
# --------------------------------------------------------------------------------------

#: NVIDIA Parakeet TDT 0.6B v3 (ONNX int8).  25 European languages, CPU friendly.
BACKEND_PARAKEET_V3 = "parakeet-v3"
#: OpenAI Whisper large-v3-turbo.  Everything parakeet cannot do (ja/zh/ko/ar/hi/...).
BACKEND_WHISPER_TURBO = "whisper-large-v3-turbo"
#: Small Whisper, used when the user picks a "light" model in the config.
BACKEND_WHISPER_SMALL = "whisper-small"

#: Languages parakeet-v3 was trained on (verified from the model card / PLAN.md §4).
PARAKEET_LANGUAGES: tuple[str, ...] = (
    "en",
    "es",
    "fr",
    "de",
    "bg",
    "hr",
    "cs",
    "da",
    "nl",
    "et",
    "fi",
    "el",
    "hu",
    "it",
    "lv",
    "lt",
    "mt",
    "pl",
    "pt",
    "ro",
    "sk",
    "sl",
    "sv",
    "ru",
    "uk",
)


@dataclass(frozen=True)
class Language:
    """One supported language."""

    code: str
    """Short BCP-47 code, e.g. ``ja``."""

    name: str
    """English display name, e.g. ``Japanese``."""

    stt_backend: str
    """Name of the speech-to-text backend from :data:`BACKENDS`."""

    tts_locale: str
    """Piper locale, e.g. ``ja_JP`` (may have no voice at all)."""

    tts_voice: str
    """Piper voice id (``<locale>-<name>-<quality>``) or ``""`` when unsupported."""

    scripts: tuple[str, ...]
    """Writing systems the transliterator can emit for this language."""

    whisper_code: str = ""
    """Language token handed to Whisper, when the backend is a Whisper one."""

    alt_codes: tuple[str, ...] = field(default=())
    """Other codes users may write, e.g. ``zh-CN``."""

    @property
    def tts_available(self) -> bool:
        """Whether a Piper voice exists for this language."""
        return bool(self.tts_voice)


def _para(locale: str, scripts: Iterable[str], voice: str, name: str, code: str) -> Language:
    """Build a parakeet-backed language entry."""
    return Language(
        code=code,
        name=name,
        stt_backend=BACKEND_PARAKEET_V3,
        tts_locale=locale,
        tts_voice=voice,
        scripts=tuple(scripts),
        whisper_code=code,
    )


def _whisper(
    locale: str,
    scripts: Iterable[str],
    voice: str,
    name: str,
    code: str,
    alt_codes: tuple[str, ...] = (),
) -> Language:
    """Build a Whisper-backed language entry."""
    return Language(
        code=code,
        name=name,
        stt_backend=BACKEND_WHISPER_TURBO,
        tts_locale=locale,
        tts_voice=voice,
        scripts=tuple(scripts),
        whisper_code=code,
        alt_codes=alt_codes,
    )


#: Every language the sidecar advertises.  ``tts_voice`` values were checked against the
#: upstream Piper voice list (``rhasspy/piper-voices/voices.json``).
LANGUAGES: Dict[str, Language] = {
    # ---------------------------------------------------------------- parakeet v3 ----
    "en": _para("en_US", ("latin",), "en_US-lessac-high", "English", "en"),
    "es": _para("es_ES", ("latin",), "es_ES-davefx-medium", "Spanish", "es"),
    "fr": _para("fr_FR", ("latin",), "fr_FR-siwis-medium", "French", "fr"),
    "de": _para("de_DE", ("latin",), "de_DE-thorsten-high", "German", "de"),
    "bg": _para("bg_BG", ("cyrillic",), "bg_BG-dimitar-medium", "Bulgarian", "bg"),
    "hr": _para("hr_HR", ("latin",), "", "Croatian", "hr"),  # no Piper voice upstream
    "cs": _para("cs_CZ", ("latin",), "cs_CZ-jirka-medium", "Czech", "cs"),
    "da": _para("da_DK", ("latin",), "da_DK-talesyntese-medium", "Danish", "da"),
    "nl": _para("nl_NL", ("latin",), "nl_NL-pim-medium", "Dutch", "nl"),
    "et": _para("et_EE", ("latin",), "et_EE-news-medium", "Estonian", "et"),
    "fi": _para("fi_FI", ("latin",), "fi_FI-harri-medium", "Finnish", "fi"),
    "el": _para("el_GR", ("greek",), "el_GR-rapunzelina-medium", "Greek", "el"),
    "hu": _para("hu_HU", ("latin",), "hu_HU-imre-medium", "Hungarian", "hu"),
    "it": _para("it_IT", ("latin",), "it_IT-paola-medium", "Italian", "it"),
    "lv": _para("lv_LV", ("latin",), "lv_LV-aivars-medium", "Latvian", "lv"),
    "lt": _para("lt_LT", ("latin",), "lt_LT-ingute-medium", "Lithuanian", "lt"),
    "mt": _para("mt_MT", ("latin",), "", "Maltese", "mt"),  # no Piper voice upstream
    "pl": _para("pl_PL", ("latin",), "pl_PL-gosia-medium", "Polish", "pl"),
    "pt": _para("pt_PT", ("latin",), "pt_BR-faber-medium", "Portuguese", "pt"),
    "ro": _para("ro_RO", ("latin",), "ro_RO-mihai-medium", "Romanian", "ro"),
    "sk": _para("sk_SK", ("latin",), "sk_SK-lili-medium", "Slovak", "sk"),
    "sl": _para("sl_SI", ("latin",), "sl_SI-artur-medium", "Slovene", "sl"),
    "sv": _para("sv_SE", ("latin",), "sv_SE-nst-medium", "Swedish", "sv"),
    "ru": _para("ru_RU", ("cyrillic",), "ru_RU-irina-medium", "Russian", "ru"),
    "uk": _para("uk_UA", ("cyrillic",), "uk_UA-ukrainian_tts-medium", "Ukrainian", "uk"),
    # ------------------------------------------------------------------- Whisper ----
    "ja": _whisper(
        "ja_JP",
        ("hiragana", "katakana", "kanji", "romaji"),
        "ja_JP-hi_fi_captain-medium",
        "Japanese",
        "ja",
    ),
    "zh": _whisper(
        "zh_CN",
        ("han", "pinyin", "latin"),
        "zh_CN-huayan-medium",
        "Chinese (Mandarin)",
        "zh",
        ("zh-CN", "cmn", "zh-Hans", "zh-Hant"),
    ),
    "ko": _whisper("ko_KR", ("hangul", "romaja"), "ko_KR-kss-medium", "Korean", "ko"),
    "ar": _whisper("ar_JO", ("arabic", "latin"), "ar_JO-kareem-medium", "Arabic", "ar"),
    "hi": _whisper(
        "hi_IN", ("devanagari", "latin"), "hi_IN-pratham-medium", "Hindi", "hi"
    ),
    "tr": _whisper("tr_TR", ("latin",), "tr_TR-dfki-medium", "Turkish", "tr"),
    "vi": _whisper("vi_VN", ("latin",), "vi_VN-vais1000-medium", "Vietnamese", "vi"),
    "th": _whisper("th_TH", ("thai", "latin"), "th_TH-tsync2-medium", "Thai", "th"),
    "id": _whisper("id_ID", ("latin",), "id_ID-news_tts-medium", "Indonesian", "id"),
    "he": _whisper("he_IL", ("hebrew", "latin"), "he_IL-saspeech-medium", "Hebrew", "he"),
    "fa": _whisper("fa_IR", ("arabic", "latin"), "fa_IR-amir-medium", "Persian", "fa"),
}

#: Default language used when a request omits a usable code.
DEFAULT_LANGUAGE = "en"

_ALIASES: Dict[str, str] = {}
for _lang in LANGUAGES.values():
    _ALIASES[_lang.code] = _lang.code
    for _alt in _lang.alt_codes:
        _ALIASES[_alt.lower()] = _lang.code


def normalize(code: str | None) -> str:
    """Normalize a BCP-47 tag to a registry code.

    ``ja-JP`` → ``ja``, ``zh_CN`` → ``zh``, ``pt-BR`` → ``pt``.  Unknown codes are
    returned lower-cased with the region stripped so the caller can produce a useful
    "language not supported" message instead of a stack trace.
    """
    if not code:
        return DEFAULT_LANGUAGE
    raw = str(code).strip().lower().replace("_", "-")
    if raw in _ALIASES:
        return _ALIASES[raw]
    base = raw.split("-")[0]
    return _ALIASES.get(base, base)


def get(code: str | None) -> Language | None:
    """Return the registry entry for *code*, or ``None`` if unsupported."""
    return LANGUAGES.get(normalize(code))


def require(code: str | None) -> Language:
    """Return the registry entry for *code* or raise :class:`KeyError`."""
    lang = get(code)
    if lang is None:
        raise KeyError(f"unsupported language: {code!r}")
    return lang


def supported_codes() -> list[str]:
    """All supported language codes, sorted."""
    return sorted(LANGUAGES)


def whisper_backend_override() -> str:
    """Honour ``MLL_WHISPER_BACKEND`` (``onnx`` swaps faster-whisper for ONNX Whisper).

    ``MLL_WHISPER_BACKEND=onnx`` makes CJK/Vietnamese/etc. work with **zero** optional
    extras, at the cost of a smaller Whisper (base) instead of large-v3-turbo.
    """
    value = os.environ.get("MLL_WHISPER_BACKEND", "").strip().lower()
    if value in ("onnx", "onnx-asr", "whisper-onnx"):
        return "onnx"
    if value in ("faster", "faster-whisper", "ct2"):
        return "faster-whisper"
    return "faster-whisper"


def default_whisper_model_override() -> str:
    """Allow the mod config to pick a different Whisper size (``MLL_WHISPER_MODEL``)."""
    return os.environ.get("MLL_WHISPER_MODEL", "").strip()
