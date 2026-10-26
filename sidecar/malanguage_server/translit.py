"""Transliteration and phonetics.

The Minecraft mod shows two lines under the prompt: the word in the language's own
script, and a reading/phonetic hint.  This module produces both.

* **Japanese** — ``pykakasi`` gives hiragana / katakana / romaji; a bundled kana→IPA
  table produces a mora-segmented IPA string (``/no.ko.ɡi.ɾi/``).
* **Chinese** — ``pypinyin`` when installed (optional), otherwise the text is returned
  unchanged with an empty phonetic string.
* **Korean** — a self-contained Hangul → Revised Romanization converter plus a
  best-effort jamo IPA rendering.
* **Everything else** — a bundled ordered rule table per language produces a
  best-effort IPA transcription.  Languages without a table get ``""``.

No network, no models, no native dependencies: this module is pure Python so
``/transliterate`` always works, even when every speech model is missing.
"""

from __future__ import annotations

import logging
import re
import unicodedata
from dataclasses import dataclass, field
from typing import Callable, Dict, Iterable, Optional

from . import languages

logger = logging.getLogger("malanguage_server.translit")

# ======================================================================================
# Result type
# ======================================================================================


@dataclass
class Transliteration:
    """One transliteration answer."""

    primary: str
    secondary: str = ""
    phonetic: str = ""
    script: str = ""
    alternatives: Dict[str, str] = field(default_factory=dict)

    def as_dict(self) -> Dict[str, object]:
        """JSON-ready payload matching the HTTP contract."""
        return {
            "primary": self.primary,
            "secondary": self.secondary,
            "phonetic": self.phonetic,
            "script": self.script,
            "alternatives": self.alternatives,
        }


# ======================================================================================
# Script detection
# ======================================================================================

_SCRIPT_RANGES: tuple[tuple[str, int, int], ...] = (
    ("hiragana", 0x3040, 0x309F),
    ("katakana", 0x30A0, 0x30FF),
    ("kanji", 0x4E00, 0x9FFF),
    ("kanji", 0x3400, 0x4DBF),
    ("hangul", 0xAC00, 0xD7AF),
    ("hangul", 0x1100, 0x11FF),
    ("cyrillic", 0x0400, 0x04FF),
    ("greek", 0x0370, 0x03FF),
    ("arabic", 0x0600, 0x06FF),
    ("hebrew", 0x0590, 0x05FF),
    ("devanagari", 0x0900, 0x097F),
    ("thai", 0x0E00, 0x0E7F),
)

_SCRIPT_NAMES = {
    "hiragana": "hiragana",
    "katakana": "katakana",
    "kanji": "kanji",
    "hangul": "hangul",
    "cyrillic": "cyrillic",
    "greek": "greek",
    "arabic": "arabic",
    "hebrew": "hebrew",
    "devanagari": "devanagari",
    "thai": "thai",
}


def _count_scripts(text: str) -> Dict[str, int]:
    """Count characters per writing system."""
    counts: Dict[str, int] = {}
    for char in text:
        code = ord(char)
        for name, low, high in _SCRIPT_RANGES:
            if low <= code <= high:
                counts[name] = counts.get(name, 0) + 1
                break
    return counts


def detect_script(text: str, language_code: str = "") -> str:
    """Return the dominant writing system of *text*.

    Pure Latin text reports either ``latin`` or the language's conventional name
    (``pinyin`` for Chinese input typed in Latin letters, ``romaji`` for Japanese).
    """
    counts = _count_scripts(text)
    if counts:
        dominant = max(counts, key=lambda name: counts[name])
        if dominant == "kanji" and counts.get("hiragana", 0) + counts.get("katakana", 0) > 0:
            return "hiragana" if counts.get("hiragana", 0) >= counts.get("katakana", 0) else "katakana"
        return dominant

    if language_code == "ja":
        return "romaji"
    if language_code == "zh":
        return "pinyin"
    if language_code == "ko":
        return "romaja"
    return "latin"


# ======================================================================================
# Japanese
# ======================================================================================

#: Kana → (mora, IPA) with the kana written in Hiragana (Katakana is folded first).
_KANA_IPA: Dict[str, str] = {
    # vowels
    "あ": "a", "い": "i", "う": "ɯ", "え": "e", "お": "o",
    # k
    "か": "ka", "き": "ki", "く": "kɯ", "け": "ke", "こ": "ko",
    # g
    "が": "ɡa", "ぎ": "ɡi", "ぐ": "ɡɯ", "げ": "ɡe", "ご": "ɡo",
    # s
    "さ": "sa", "し": "ɕi", "す": "sɯ", "せ": "se", "そ": "so",
    # z
    "ざ": "za", "じ": "d͡ʑi", "ず": "zɯ", "ぜ": "ze", "ぞ": "zo",
    # t
    "た": "ta", "ち": "t͡ɕi", "つ": "t͡sɯ", "て": "te", "と": "to",
    # d
    "だ": "da", "ぢ": "d͡ʑi", "づ": "zɯ", "で": "de", "ど": "do",
    # n
    "な": "na", "に": "ɲi", "ぬ": "nɯ", "ね": "ne", "の": "no",
    # h
    "は": "ha", "ひ": "çi", "ふ": "ɸɯ", "へ": "he", "ほ": "ho",
    # b
    "ば": "ba", "び": "bi", "ぶ": "bɯ", "べ": "be", "ぼ": "bo",
    # p
    "ぱ": "pa", "ぴ": "pi", "ぷ": "pɯ", "ぺ": "pe", "ぽ": "po",
    # m
    "ま": "ma", "み": "mi", "む": "mɯ", "め": "me", "も": "mo",
    # y
    "や": "ja", "ゆ": "jɯ", "よ": "jo",
    # r
    "ら": "ɾa", "り": "ɾi", "る": "ɾɯ", "れ": "ɾe", "ろ": "ɾo",
    # w / v
    "わ": "wa", "ゐ": "i", "ゑ": "e", "を": "o", "ん": "n",
    "ゔ": "vɯ", "ゎ": "wa",
}

#: Youon (contracted) morae: base kana + small ゃゅょ.
_YOON_IPA: Dict[str, str] = {
    "きゃ": "kʲa", "きゅ": "kʲɯ", "きょ": "kʲo",
    "ぎゃ": "ɡʲa", "ぎゅ": "ɡʲɯ", "ぎょ": "ɡʲo",
    "しゃ": "ɕa", "しゅ": "ɕɯ", "しょ": "ɕo",
    "じゃ": "d͡ʑa", "じゅ": "d͡ʑɯ", "じょ": "d͡ʑo",
    "ちゃ": "t͡ɕa", "ちゅ": "t͡ɕɯ", "ちょ": "t͡ɕo",
    "にゃ": "ɲa", "にゅ": "ɲɯ", "にょ": "ɲo",
    "ひゃ": "ça", "ひゅ": "çɯ", "ひょ": "ço",
    "びゃ": "bʲa", "びゅ": "bʲɯ", "びょ": "bʲo",
    "ぴゃ": "pʲa", "ぴゅ": "pʲɯ", "ぴょ": "pʲo",
    "みゃ": "mʲa", "みゅ": "mʲɯ", "みょ": "mʲo",
    "りゃ": "ɾʲa", "りゅ": "ɾʲɯ", "りょ": "ɾʲo",
    "ふぁ": "ɸa", "ふぃ": "ɸi", "ふぇ": "ɸe", "ふぉ": "ɸo",
    "てぃ": "ti", "でぃ": "di", "とぅ": "tɯ", "どぅ": "dɯ",
    "うぃ": "ɰi", "うぇ": "ɰe", "うぉ": "ɰo", "ゔぁ": "va", "ゔぉ": "vo",
}

_KATAKANA_TO_HIRAGANA = {
    code: code - 0x60 for code in range(0x30A1, 0x30F7)
}
_LONG_VOWEL = "ー"


def _fold_katakana(text: str) -> str:
    """Katakana → Hiragana (keeps U+30FC long vowel mark)."""
    return "".join(chr(_KATAKANA_TO_HIRAGANA.get(ord(ch), ord(ch))) for ch in text)


def _kana_to_morae(hiragana: str) -> list[str]:
    """Split Hiragana into IPA morae, resolving ん assimilation and っ gemination."""
    morae: list[str] = []
    index = 0
    length = len(hiragana)
    while index < length:
        # Two-character youon first.
        if index + 1 < length:
            pair = hiragana[index : index + 2]
            if pair in _YOON_IPA:
                morae.append(_YOON_IPA[pair])
                index += 2
                continue

        char = hiragana[index]

        if char == "っ":
            # Geminate: double the following consonant cluster.
            mark = _geminate_mark(hiragana[index + 1 :])
            if mark and morae:
                morae[-1] = morae[-1] + mark
            index += 1
            continue

        if char == _LONG_VOWEL:
            if morae:
                vowel = _final_vowel(morae[-1])
                morae[-1] = morae[-1] + (vowel * 2 if vowel else "ː")
            index += 1
            continue

        if char == "ん":
            morae.append(_syllabic_n(hiragana[index + 1 :]))
            index += 1
            continue

        ipa = _KANA_IPA.get(char)
        if ipa is not None:
            morae.append(ipa)
        elif char.strip():
            morae.append(char)  # unknown kana / kanji we cannot read: pass through
        index += 1
    return morae


def _geminate_mark(rest: str) -> str:
    """Consonant to double for っ before *rest*."""
    if not rest:
        return ""
    folded = rest[0]
    if folded in ("ち", "ちゃ", "ちゅ", "ちょ"):
        return "t"
    for kana, ipa in (*_YOON_IPA.items(), *_KANA_IPA.items()):
        if folded == kana or rest.startswith(kana):
            match = re.match(r"[^aeiouɯˈˌ]*", ipa)
            return match.group(0) if match else ""
    return ""


def _final_vowel(ipa: str) -> str:
    """Trailing vowel of an IPA mora, used for ー lengthening."""
    for vowel in ("a", "i", "ɯ", "e", "o"):
        if ipa.endswith(vowel):
            return "a" if vowel == "ɯ" else vowel
    return ""


def _syllabic_n(rest: str) -> str:
    """IPA for ん, assimilating to the following consonant."""
    if not rest:
        return "n"
    following = _KANA_IPA.get(rest[0], "")
    if following[:1] in ("p", "b", "m"):
        return "m"
    if following[:1] in ("k", "ɡ"):
        return "ŋ"
    return "n"


def _kana_ipa(hiragana: str) -> str:
    """Kana string → dotted IPA surface form (``no.ko.ɡi.ɾi``)."""
    morae = [mora for mora in _kana_to_morae(_fold_katakana(hiragana)) if mora.strip()]
    return ".".join(morae)


class _JapaneseConverter:
    """``pykakasi`` wrapper, loaded lazily and cached."""

    _instance: Optional["_JapaneseConverter"] = None
    _failure: Optional[str] = None

    def __init__(self) -> None:
        import pykakasi  # noqa: PLC0415 - optional dependency

        self._kakasi = pykakasi.kakasi()

    @classmethod
    def get(cls) -> Optional["_JapaneseConverter"]:
        """Return the shared converter or ``None`` when pykakasi is unavailable."""
        if cls._failure is not None:
            return None
        if cls._instance is None:
            try:
                cls._instance = cls()
            except Exception as exc:  # noqa: BLE001 - never raise from /transliterate
                cls._failure = f"{exc.__class__.__name__}: {exc}"
                logger.warning("pykakasi unavailable (%s); Japanese falls back to IPA only", cls._failure)
                return None
        return cls._instance

    def convert(self, text: str) -> Dict[str, str]:
        """Return ``hiragana`` / ``katakana`` / ``romaji`` renderings of *text*."""
        hiragana: list[str] = []
        katakana: list[str] = []
        romaji: list[str] = []
        for item in self._kakasi.convert(text):
            hiragana.append(item.get("hira") or item.get("orig", ""))
            katakana.append(item.get("kana") or item.get("orig", ""))
            romaji.append(item.get("hepburn") or item.get("orig", ""))
        return {
            "hiragana": "".join(hiragana),
            "katakana": "".join(katakana),
            "romaji": " ".join("".join(romaji).split()),
        }


def _transliterate_japanese(text: str) -> Transliteration:
    """Build the Japanese answer (hiragana primary, romaji secondary, mora IPA)."""
    converter = _JapaneseConverter.get()
    if converter is not None:
        try:
            parts = converter.convert(text)
        except Exception:  # noqa: BLE001
            logger.debug("pykakasi conversion failed for %r", text, exc_info=True)
            parts = {}
    else:
        parts = {}

    hiragana = parts.get("hiragana") or ""
    katakana = parts.get("katakana") or ""
    romaji = parts.get("romaji") or ""

    # Without pykakasi we can still read text that is already kana.
    if not hiragana and _count_scripts(text).get("hiragana", 0) + _count_scripts(text).get("katakana", 0):
        hiragana = _fold_katakana(text)
        katakana = text
    if not romaji and hiragana:
        romaji = _kana_ipa(hiragana)  # last-resort latin hint is the IPA itself

    primary = hiragana or text
    phonetic = ""
    if hiragana:
        ipa = _kana_ipa(hiragana)
        if ipa:
            phonetic = f"/{ipa}/"

    alternatives: Dict[str, str] = {"plain": text}
    for key in ("katakana", "kanji", "romaji", "hiragana"):
        value = {
            "katakana": katakana,
            "kanji": text if _count_scripts(text).get("kanji") else "",
            "romaji": romaji,
            "hiragana": hiragana,
        }[key]
        if value:
            alternatives[key] = value
    if hiragana and _count_scripts(text).get("kanji"):
        alternatives["furigana"] = f"{hiragana}【{text}】"

    return Transliteration(
        primary=primary,
        secondary=romaji or phonetic.strip("/"),
        phonetic=phonetic,
        script=detect_script(primary, "ja"),
        alternatives=alternatives,
    )


# ======================================================================================
# Chinese
# ======================================================================================


def _transliterate_chinese(text: str) -> Transliteration:
    """Pinyin (optional ``pypinyin``) with a plain-text fallback."""
    pinyin: list[str] = []
    toned: list[str] = []
    try:
        from pypinyin import Style, pinyin as _pinyin  # noqa: PLC0415 - optional

        toned = [item[0] for item in _pinyin(text, style=Style.TONE)]
        pinyin = [item[0] for item in _pinyin(text, style=Style.NORMAL)]
    except Exception:  # noqa: BLE001 - package absent or failed
        logger.debug("pypinyin unavailable; Chinese returns plain text")

    alternatives: Dict[str, str] = {"plain": text, "han": text}
    if pinyin:
        alternatives["pinyin"] = " ".join(pinyin)
        alternatives["pinyin_toned"] = " ".join(toned)
    return Transliteration(
        primary=text,
        secondary=" ".join(pinyin) if pinyin else "",
        phonetic="",
        script=detect_script(text, "zh"),
        alternatives=alternatives,
    )


# ======================================================================================
# Korean
# ======================================================================================

_HANGUL_INITIAL = [
    "g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h",
]
_HANGUL_MEDIAL = [
    "a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu",
    "eu", "ui", "i",
]
_HANGUL_FINAL = [
    "", "k", "k", "k", "n", "n", "n", "t", "l", "l", "l", "l", "l", "l", "l", "l", "m", "p", "p", "t", "t", "ng",
    "t", "t", "k", "t", "p", "t",
]

_HANGUL_INITIAL_IPA = [
    "k", "k͈", "n", "t", "t͈", "ɾ", "m", "p", "p͈", "s", "s͈", "", "t͡ɕ", "t͡ɕ͈", "t͡ɕʰ", "kʰ", "tʰ", "pʰ", "h",
]
_HANGUL_MEDIAL_IPA = [
    "a", "ɛ", "ja", "jɛ", "ʌ", "e", "jʌ", "je", "o", "wa", "wɛ", "we", "jo", "u", "wʌ", "we", "wi", "ju",
    "ɯ", "ɰi", "i",
]
_HANGUL_FINAL_IPA = [
    "", "k̚", "k̚", "k̚", "n", "n", "n", "t̚", "l", "l", "l", "l", "l", "l", "l", "l", "m", "p̚", "p̚", "t̚", "t̚",
    "ŋ", "t̚", "t̚", "k̚", "t̚", "p̚", "t̚",
]


def _transliterate_korean(text: str) -> Transliteration:
    """Hangul → Revised Romanization, plus a per-syllable IPA approximation."""
    roman: list[str] = []
    ipa: list[str] = []
    for char in text:
        code = ord(char)
        if 0xAC00 <= code <= 0xD7A3:
            offset = code - 0xAC00
            initial = offset // 588
            medial = (offset % 588) // 28
            final = offset % 28
            roman.append(_HANGUL_INITIAL[initial] + _HANGUL_MEDIAL[medial] + _HANGUL_FINAL[final])
            ipa.append(
                _HANGUL_INITIAL_IPA[initial] + _HANGUL_MEDIAL_IPA[medial] + _HANGUL_FINAL_IPA[final]
            )
        elif char.strip():
            roman.append(char)
            ipa.append(char)

    romaja = "".join(roman)
    ipa_text = ".".join(ipa) if ipa else ""
    return Transliteration(
        primary=text,
        secondary=romaja,
        phonetic=f"/{ipa_text}/" if ipa_text else "",
        script=detect_script(text, "ko"),
        alternatives={"plain": text, "hangul": text, "romaja": romaja} if romaja else {"plain": text},
    )


# ======================================================================================
# Latin / Cyrillic IPA – bundled rule tables
# ======================================================================================

#: Ordered ``(pattern, replacement)`` rules.  Earlier rules win, so put longer and more
#: specific graphemes first.  Every table is applied to lower-cased input.
_IpaRules = list[tuple[str, str]]


def _apply_rules(word: str, rules: _IpaRules) -> str:
    """Apply ordered regex rules to *word*."""
    output = word
    for pattern, replacement in rules:
        output = re.sub(pattern, replacement, output)
    return output


# ---- English -------------------------------------------------------------------------
# English spelling is not rule-derived; a modest exception table plus digraph rules gets
# close enough to be a useful hint, which is all the mod needs.

_EN_EXCEPTIONS: Dict[str, str] = {
    "a": "ə", "the": "ðə", "and": "ænd", "of": "əv", "to": "tuː", "is": "ɪz",
    "stone": "stoʊn", "cobblestone": "ˈkɒbəlstoʊn", "dirt": "dɜːt", "grass": "ɡɹɑːs",
    "wood": "wʊd", "log": "lɒɡ", "plank": "plæŋk", "sapling": "ˈsæplɪŋ",
    "iron": "ˈaɪəɹn", "gold": "ɡoʊld", "diamond": "ˈdaɪəmənd", "emerald": "ˈɛməɹəld",
    "coal": "koʊl", "copper": "ˈkɒpəɹ", "sand": "sænd", "gravel": "ˈɡɹævəl",
    "water": "ˈwɔːtəɹ", "lava": "ˈlɑːvə", "apple": "ˈæpəl", "bread": "bɹɛd",
    "sword": "sɔːɹd", "pickaxe": "ˈpɪkæks", "axe": "æks", "shovel": "ˈʃʌvəl",
    "hoe": "hoʊ", "bucket": "ˈbʌkɪt", "bow": "boʊ", "arrow": "ˈæɹoʊ",
    "torch": "tɔːɹtʃ", "chest": "tʃɛst", "door": "dɔːɹ", "bed": "bɛd",
    "boat": "boʊt", "minecart": "ˈmaɪnkɑːɹt", "rail": "ɹeɪl", "saw": "sɔː",
    "hammer": "ˈhæməɹ", "book": "bʊk", "paper": "ˈpeɪpəɹ", "glass": "ɡlɑːs",
    "brick": "bɹɪk", "string": "stɹɪŋ", "feather": "ˈfɛðəɹ", "flint": "flɪnt",
    "bone": "boʊn", "egg": "ɛɡ", "wheat": "wiːt", "seed": "siːd", "carrot": "ˈkæɹət",
    "potato": "pəˈteɪtoʊ", "sugar": "ˈʃʊɡəɹ", "cake": "keɪk", "milk": "mɪlk",
    "leather": "ˈlɛðəɹ", "wool": "wʊl", "clay": "kleɪ", "obsidian": "əbˈsɪdiən",
    "netherite": "ˈnɛðəɹaɪt", "quartz": "kwɔːɹts", "amethyst": "ˈæməθɪst",
}

_EN_RULES: _IpaRules = [
    (r"ough", "ʌf"),
    (r"augh", "ɔː"),
    (r"tion", "ʃən"),
    (r"sion", "ʒən"),
    (r"ture", "tʃəɹ"),
    (r"cial", "ʃəl"),
    (r"tial", "ʃəl"),
    (r"ck", "k"),
    (r"ph", "f"),
    (r"sh", "ʃ"),
    (r"ch", "tʃ"),
    (r"th", "θ"),
    (r"wh", "w"),
    (r"ng", "ŋ"),
    (r"qu", "kw"),
    (r"ee", "iː"),
    (r"ea", "iː"),
    (r"oo", "uː"),
    (r"ou", "aʊ"),
    (r"ow", "oʊ"),
    (r"oa", "oʊ"),
    (r"oi", "ɔɪ"),
    (r"oy", "ɔɪ"),
    (r"ai", "eɪ"),
    (r"ay", "eɪ"),
    (r"au", "ɔː"),
    (r"aw", "ɔː"),
    (r"ie", "iː"),
    (r"ei", "eɪ"),
    (r"ey", "iː"),
    (r"ue", "uː"),
    (r"ui", "uː"),
    (r"^y", "j"),
    (r"y$", "i"),
    (r"y", "ɪ"),
    (r"^c(?=[ei])", "s"),
    (r"c(?=[ei])", "s"),
    (r"^c", "k"),
    (r"c", "k"),
    (r"g(?=[ei])", "dʒ"),
    (r"x", "ks"),
    (r"j", "dʒ"),
    (r"e$", ""),
    (r"(?<=[^aeiou])er$", "əɹ"),
    (r"a", "æ"),
    (r"i", "ɪ"),
    (r"o", "ɒ"),
    (r"u", "ʌ"),
    (r"e", "ɛ"),
]


def _ipa_english(word: str) -> str:
    """Best-effort IPA for an English word."""
    lower = word.lower()
    if lower in _EN_EXCEPTIONS:
        return _EN_EXCEPTIONS[lower]
    return _apply_rules(lower, _EN_RULES)


# ---- Spanish -------------------------------------------------------------------------

_ES_RULES: _IpaRules = [
    (r"ch", "tʃ"),
    (r"ll", "ʝ"),
    (r"rr", "r"),
    (r"qu(?=[ei])", "k"),
    (r"gu(?=[ei])", "ɡ"),
    (r"gü", "ɡw"),
    (r"ñ", "ɲ"),
    (r"c(?=[ei])", "θ"),
    (r"z", "θ"),
    (r"j", "x"),
    (r"g(?=[ei])", "x"),
    (r"h", ""),
    (r"y", "ʝ"),
    # Lenition only between vowels: /b d g/ become [β ð ɣ] word-internally.
    (r"(?<=[aeiouáéíóú])b(?=[aeiouáéíóú])", "β"),
    (r"(?<=[aeiouáéíóú])d(?=[aeiouáéíóú])", "ð"),
    (r"(?<=[aeiouáéíóú])g(?=[aeiouáéíóú])", "ɣ"),
    (r"v", "b"),
    (r"r", "ɾ"),
    (r"c", "k"),
    (r"g", "ɡ"),
]

# ---- French --------------------------------------------------------------------------

_FR_RULES: _IpaRules = [
    (r"eaux?", "o"),
    (r"eux?", "ø"),
    (r"œu", "ø"),
    (r"ain|ein|in|yn|un|aim|eim", "ɛ̃"),
    (r"an|am|en|em", "ɑ̃"),
    (r"on|om", "ɔ̃"),
    (r"ill", "ij"),
    (r"gn", "ɲ"),
    (r"ch", "ʃ"),
    (r"ph", "f"),
    (r"qu", "k"),
    (r"oi", "wa"),
    (r"ou", "u"),
    (r"au", "o"),
    (r"ai|ei", "ɛ"),
    (r"eu", "ø"),
    (r"ç", "s"),
    (r"c(?=[eiéèê])", "s"),
    (r"g(?=[eiéèê])", "ʒ"),
    (r"j", "ʒ"),
    (r"(?<=[aeiouɛœɔ])s(?=[aeiouɛœɔ])", "z"),
    (r"é", "e"),
    (r"[èê]", "ɛ"),
    (r"[àâ]", "a"),
    (r"[îï]", "i"),
    (r"[ô]", "o"),
    (r"[ûù]", "y"),
    (r"u", "y"),
    (r"h", ""),
    (r"(?<=[^aeiouɛœɔ])[stdxzp]$", ""),
    (r"e$", ""),
    (r"[tdsz]$", ""),
    # French /r/ is uvular; keep it last so the elision rules above see the raw letter.
    (r"r", "ʁ"),
    (r"(?<=[^aeiouɛœɔʁ])[tdsz]$", ""),
]

# ---- German --------------------------------------------------------------------------

_DE_RULES: _IpaRules = [
    (r"tsch", "tʃ"),
    (r"sch", "ʃ"),
    (r"^sp", "ʃp"),
    (r"^st", "ʃt"),
    (r"chs", "ks"),
    (r"ch", "x"),
    (r"pf", "p͡f"),
    (r"qu", "kv"),
    (r"ei|ai", "aɪ"),
    (r"ie", "iː"),
    (r"eu|äu", "ɔʏ"),
    (r"au", "aʊ"),
    (r"ß", "s"),
    (r"z", "t͡s"),
    (r"s(?=[aeiouäöü])", "z"),
    (r"v", "f"),
    (r"w", "v"),
    (r"j", "j"),
    (r"ng", "ŋ"),
    (r"r", "ʁ"),
    (r"ä", "ɛ"),
    (r"ö", "œ"),
    (r"ü", "y"),
    (r"y", "y"),
    (r"b$", "p"),
    (r"d$", "t"),
    (r"g$", "k"),
    (r"ig$", "ɪç"),
    (r"h(?=[aeiouäöü])", "h"),
    (r"h$", ""),
]

# ---- Italian -------------------------------------------------------------------------

_IT_RULES: _IpaRules = [
    (r"gli", "ʎ"),
    (r"gn", "ɲ"),
    (r"sc(?=[ei])", "ʃ"),
    (r"ch", "k"),
    (r"gh", "ɡ"),
    (r"c(?=[ei])", "tʃ"),
    (r"g(?=[ei])", "dʒ"),
    (r"zz", "t͡s"),
    (r"z", "t͡s"),
    (r"qu", "kw"),
    (r"h", ""),
    (r"(.)\1", r"\1"),
    (r"c", "k"),
    (r"g", "ɡ"),
    (r"e$", "e"),
]

# ---- Portuguese ----------------------------------------------------------------------

_PT_RULES: _IpaRules = [
    (r"lh", "ʎ"),
    (r"nh", "ɲ"),
    (r"ch", "ʃ"),
    (r"rr", "ʁ"),
    (r"ss", "s"),
    (r"ç", "s"),
    (r"qu", "k"),
    (r"gu", "ɡ"),
    (r"ão", "ɐ̃w̃"),
    (r"ã", "ɐ̃"),
    (r"õ", "õ"),
    (r"x", "ʃ"),
    (r"j", "ʒ"),
    (r"g(?=[ei])", "ʒ"),
    (r"c(?=[ei])", "s"),
    (r"s(?=[aeiouáéíóú])", "z"),
    (r"^r", "ʁ"),
    (r"r$", "ɾ"),
    (r"r", "ɾ"),
    (r"^c", "k"),
    (r"c", "k"),
    (r"g", "ɡ"),
]

# ---- Russian -------------------------------------------------------------------------

_RU_RULES: _IpaRules = [
    (r"сч", "ɕː"),
    (r"зч", "ɕː"),
    (r"ться", "t͡sə"),
    (r"тся", "t͡sə"),
    (r"ого$", "ovə"),
    (r"его$", "jevə"),
    (r"ж", "ʐ"),
    (r"ш", "ʂ"),
    (r"щ", "ɕː"),
    (r"ц", "t͡s"),
    (r"ч", "t͡ɕ"),
    (r"х", "x"),
    (r"г", "ɡ"),
    (r"л", "ɫ"),
    (r"ы", "ɨ"),
    (r"ъ", ""),
    (r"ь", "ʲ"),
    (r"е", "je"),
    (r"ё", "jo"),
    (r"ю", "ju"),
    (r"я", "ja"),
    (r"й", "j"),
    (r"б", "b"),
    (r"в", "v"),
    (r"д", "d"),
    (r"з", "z"),
    (r"к", "k"),
    (r"м", "m"),
    (r"н", "n"),
    (r"п", "p"),
    (r"р", "r"),
    (r"с", "s"),
    (r"т", "t"),
    (r"ф", "f"),
    (r"а", "ɐ"),
    (r"о", "o"),
    (r"у", "u"),
    (r"э", "ɛ"),
    (r"и", "i"),
]

# ---- Turkish -------------------------------------------------------------------------
# Turkish orthography is almost phonemic, so a short table is genuinely accurate.

_TR_RULES: _IpaRules = [
    (r"c(?=[eiöü])", "d͡ʒ"),
    (r"ç", "tʃ"),
    (r"ş", "ʃ"),
    (r"ğ", "ː"),
    (r"j", "ʒ"),
    (r"y", "j"),
    (r"ı", "ɯ"),
    (r"ö", "œ"),
    (r"ü", "y"),
    (r"c", "d͡ʒ"),
]

#: Language code → (ordered IPA rules, per-word exception dictionary).
_IPA_TABLES: Dict[str, tuple[_IpaRules, Dict[str, str]]] = {
    "en": (_EN_RULES, _EN_EXCEPTIONS),
    "es": (_ES_RULES, {}),
    "fr": (_FR_RULES, {}),
    "de": (_DE_RULES, {}),
    "it": (_IT_RULES, {}),
    "pt": (_PT_RULES, {}),
    "ru": (_RU_RULES, {}),
    "tr": (_TR_RULES, {}),
}

#: Orthographies close enough to phonemic that the spelling *is* a usable hint.  They
#: deliberately report an empty ``phonetic`` field: the mod shows the word itself.
_PHONEMIC_LANGUAGES = {
    "hr", "cs", "sk", "sv", "da", "nl", "et", "fi", "hu", "lv", "lt", "sl", "ro",
    "bg", "el", "id", "vi", "pl",
}


def _word_ipa(word: str, language_code: str) -> str:
    """IPA for one word in *language_code* (empty when unavailable)."""
    table = _IPA_TABLES.get(language_code)
    if table is None:
        return ""
    rules, exceptions = table
    lower = word.lower()
    if lower in exceptions:
        return exceptions[lower]
    if language_code == "en":
        return _ipa_english(lower)
    return _apply_rules(lower, rules)


def _ipa_phrase(text: str, language_code: str) -> str:
    """IPA for a whole phrase, one dotted group per word."""
    words = [w for w in re.split(r"\s+", text.strip()) if w]
    rendered = [_word_ipa(word, language_code) for word in words]
    rendered = [r for r in rendered if r]
    if not rendered:
        return ""
    return "/" + " ".join(rendered) + "/"


# ======================================================================================
# Public entry point
# ======================================================================================


def transliterate(
    text: str,
    language_code: str,
    primary_script: str = "",
    secondary_script: str = "",
) -> Transliteration:
    """Transliterate *text* into the forms the mod displays.

    *primary_script* and *secondary_script* are the names chosen in the mod's settings
    (``hiragana``, ``kanji``, ``romaji``, ``ipa``, ``native`` …).  When they are empty or
    unknown the natural default for the language is used, so this stays safe to call with
    plain ``transliterate(text, lang)``.

    Always returns a result: unknown languages fall back to plain text with an empty
    phonetic string, never an exception.
    """
    raw = (text or "").strip()
    code = languages.normalize(language_code)
    if not raw:
        return Transliteration(primary="", script="")

    result = _transliterate_default(raw, code)
    return _apply_script_choice(result, raw, code, primary_script, secondary_script)


def _transliterate_default(raw: str, code: str) -> Transliteration:
    """The language's natural answer, before the user's script preference is applied."""

    if code == "ja":
        return _transliterate_japanese(raw)
    if code == "zh":
        return _transliterate_chinese(raw)
    if code == "ko":
        return _transliterate_korean(raw)

    phonetic = _ipa_phrase(raw, code)
    alternatives: Dict[str, str] = {"plain": raw}
    entry = languages.get(code)
    if entry is not None and entry.scripts:
        alternatives[entry.scripts[0]] = raw
    if phonetic:
        alternatives["ipa"] = phonetic

    secondary = phonetic or raw
    return Transliteration(
        primary=raw,
        secondary=secondary,
        phonetic=phonetic,
        script=detect_script(raw, code),
        alternatives=alternatives,
    )


def supported_phonetic_languages() -> list[str]:
    """Languages with a bundled IPA rule table."""
    return sorted(_IPA_TABLES)


def describe() -> Dict[str, object]:
    """Diagnostics for ``/health``: which optional pieces are actually importable."""
    import importlib.util

    def has(module: str) -> bool:
        try:
            return importlib.util.find_spec(module) is not None
        except Exception:  # noqa: BLE001 - broken installs raise here
            return False

    return {
        "ipa_languages": supported_phonetic_languages(),
        "phonemic_languages": sorted(_PHONEMIC_LANGUAGES),
        "pykakasi": has("pykakasi"),
        "pypinyin": has("pypinyin"),
    }


# ======================================================================================
# Script preference
# ======================================================================================

#: Scripts that mean "the phonetic reading" rather than a separate orthography.
_PHONETIC_SCRIPTS = {"ipa", "phonetic", "phonemic"}

#: Scripts that mean "the player's own language" — resolved by the caller, never here.
_NATIVE_SCRIPTS = {"native", "translation"}


def _script_value(result: Transliteration, script: str, raw: str, code: str) -> str:
    """Look one script up in a transliteration, or return "" when it does not apply."""
    if not script:
        return ""
    name = script.strip().lower()

    if name in _NATIVE_SCRIPTS:
        return ""
    if name in _PHONETIC_SCRIPTS:
        return result.phonetic or ""
    if name == "plain":
        return raw

    # An explicit alternative always wins — it is the most specific answer.
    if name in result.alternatives:
        return result.alternatives[name]

    # ``latin`` is the sidecar's name for the romanised form in non-Latin languages.
    if name in {"latin", "romaja", "romanized", "romaji"}:
        for alias in ("romaji", "romaja", "pinyin", "latin", "romanized"):
            value = result.alternatives.get(alias, "")
            if value:
                return value

    # The script the language naturally renders in.
    if name == (result.script or "").lower():
        return result.primary

    entry = languages.get(code)
    if entry is not None and name in entry.scripts:
        # The language advertises this script but nothing produced it; the primary is
        # the closest honest answer.
        return result.primary
    return ""


def _apply_script_choice(
    result: Transliteration,
    raw: str,
    code: str,
    primary_script: str,
    secondary_script: str,
) -> Transliteration:
    """Rewrite *result* so its primary/secondary slots honour the user's choice.

    Unknown or unavailable scripts leave the natural rendering untouched, so a bad
    setting degrades to a sensible word rather than an empty popup.
    """
    primary = _script_value(result, primary_script, raw, code)
    if not primary:
        primary = result.primary

    secondary = _script_value(result, secondary_script, raw, code)
    if not secondary or secondary == primary:
        # Fall back to the natural second form, then to the phonetic reading.
        secondary = result.secondary or result.phonetic
    if secondary == primary:
        # Never echo the word in both slots: the mod would render it twice.
        secondary = ""

    script = detect_script(primary, code) if primary else result.script

    alternatives = dict(result.alternatives)
    alternatives.setdefault("plain", raw)
    if result.phonetic:
        alternatives.setdefault("ipa", result.phonetic)
    if primary:
        alternatives.setdefault(script or "primary", primary)
    alternatives.pop("secondary", None)

    return Transliteration(
        primary=primary,
        secondary=secondary,
        phonetic=result.phonetic,
        script=script or result.script,
        alternatives=alternatives,
    )
