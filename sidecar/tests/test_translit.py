"""Tests for the transliteration contract the Minecraft mod depends on.

Run with ``python -m pytest`` from the ``sidecar/`` directory. These need no models and
no network, so they are cheap enough to run on every build.
"""

from __future__ import annotations

from malanguage_server import translit


# --------------------------------------------------------------------------- Japanese


def test_japanese_defaults_to_hiragana_with_romaji() -> None:
    result = translit.transliterate("鋸", "ja")
    assert result.primary == "のこぎり"
    assert result.secondary == "nokogiri"
    assert result.phonetic.startswith("/")


def test_japanese_primary_script_is_honoured() -> None:
    """Every script the settings screen offers must actually change the primary line."""
    expected = {
        "hiragana": "のこぎり",
        "katakana": "ノコギリ",
        "kanji": "鋸",
        "romaji": "nokogiri",
        "furigana": "のこぎり【鋸】",
    }
    for script, want in expected.items():
        result = translit.transliterate("鋸", "ja", script, "ipa")
        assert result.primary == want, f"primary for {script!r} was {result.primary!r}"


def test_japanese_alternatives_contain_every_script() -> None:
    result = translit.transliterate("鋸", "ja")
    for script in ("hiragana", "katakana", "kanji", "romaji", "furigana"):
        assert script in result.alternatives, f"{script} missing from {result.alternatives}"


# --------------------------------------------------------------------------- phonetic


def test_ipa_is_produced_for_rule_based_languages() -> None:
    for code, text in (("de", "Bruchstein"), ("ru", "Камень"), ("en", "Cobblestone")):
        result = translit.transliterate(text, code)
        assert result.phonetic.startswith("/"), f"no IPA for {code}: {result.phonetic!r}"


def test_secondary_script_ipa_selects_the_phonetic_reading() -> None:
    result = translit.transliterate("Bruchstein", "de", "latin", "ipa")
    assert result.primary == "Bruchstein"
    assert result.secondary == result.phonetic


# ------------------------------------------------------------------------- robustness


def test_unknown_script_falls_back_instead_of_emptying() -> None:
    """A bad setting must degrade to a usable word, never to an empty popup."""
    result = translit.transliterate("Bruchstein", "de", "klingon", "also-not-a-script")
    assert result.primary
    assert result.secondary


def test_empty_input_is_safe() -> None:
    result = translit.transliterate("", "ja")
    assert result.primary == ""


def test_unknown_language_is_safe() -> None:
    result = translit.transliterate("stone", "xx")
    assert result.primary == "stone"


def test_native_script_is_left_for_the_caller() -> None:
    result = translit.transliterate("鋸", "ja", "native", "romaji")
    assert result.primary == "のこぎり"
