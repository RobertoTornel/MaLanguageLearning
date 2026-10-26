# MaLanguageLearning

Learn a language by speaking the names of the items you pick up.

When you acquire an item, closing your inventory starts a challenge: you have **15 seconds**
to **say the item's name out loud** in the language you are learning. Get it right and the item
is yours and that word is learned forever. Fail, and the mod speaks the word to you (TTS) and
**drops the item on the floor**.

---

## !! BIG WARNING — READ THIS FIRST !!

**THIS MOD REQUIRES A SPEECH-RECOGNITION MODEL THAT IS DOWNLOADED TO YOUR COMPUTER.**

* **IT IS LARGE.** The int8 Parakeet v3 model is about **671 MB**. The full-precision model is
  about **2.5 GB**. The first run also fetches a small Python runtime (~50 MB) and its packages.
* **IT NEEDS EXTRA RAM.** Expect roughly **1–3 GB of system RAM** used while speech recognition
  is active, on top of Minecraft itself.
* **IT CAN USE YOUR GPU.** GPU mode (CUDA) is available and is **togglable with CPU mode** in the
  settings screen. GPU mode needs a CUDA-capable GPU and **additional VRAM** for the model.
* **IT HAS A NOTICEABLE PERFORMANCE IMPACT.** Speech recognition runs as a separate local
  process and will consume CPU or GPU time. On slower machines this is very noticeable.
* **IT MAY REQUIRE ADDITIONAL DOWNLOADS.** Models are fetched on demand and cached on disk.
  You need a working internet connection the first time you use a language.
* **IT NEEDS A MICROPHONE.**

**Nothing is downloaded and no microphone is touched until you read and accept the in-game
warning screen on first launch.** If you decline, the mod simply does nothing.

Audio is recorded **only** while a word challenge is active, and is sent **only** to a local
process on `127.0.0.1`. **It never leaves your machine.**

---

## How it works

```
Minecraft client  ──localhost HTTP──▶  local sidecar (Python, auto-provisioned)
     │                                      ├─ speech → text (Parakeet v3 / Whisper)
     │                                      ├─ text  → speech (TTS)
     └─ records the mic, shows the popup    └─ transliteration + phonetics
```

The speech engine lives **outside the game**. The mod downloads and launches it for you on
first run — no Python install, no pip commands, no manual model downloads.

### Learning, one item at a time

* Each item must be said **once**. Once you say `cobblestone` correctly, cobblestone is never
  asked for again.
* Derivatives are their own words. `Stone Slab`, `Cobblestone Stairs` and `Cobblestone Wall`
  each still have to be said.
* Progress is stored in `config/malanguagelearning/learned.json`.

### Two translations

Every word is shown in two forms, both configurable:

* **Primary** — the word in the script you choose (for Japanese: hiragana, katakana, kanji,
  romaji or furigana).
* **Secondary** — a companion rendering: another script, the native-language translation, or
  the phonetic transcription (IPA).

Item names come from **Minecraft's own official translations**, so the vocabulary is the real
in-game name in your target language, and it works for every item in the game.

---

## Languages

**Parakeet v3 (25 languages, fast on CPU and GPU):**
English, German, French, Spanish, Italian, Portuguese, Dutch, Polish, Czech, Slovak, Slovenian,
Croatian, Bulgarian, Russian, Ukrainian, Greek, Hungarian, Romanian, Danish, Swedish, Finnish,
Estonian, Latvian, Lithuanian, Maltese.

**Beyond Parakeet (Whisper backend, swappable):**
Japanese, Chinese, Korean, Arabic, Hindi, Turkish, Vietnamese, Thai, Hebrew, Indonesian,
Persian, Serbian.

The engine is **model-agnostic**: backends are declared per language and can be swapped in
`config/malanguagelearning/models.toml` without touching the mod.

---

## Requirements

| | |
|---|---|
| Minecraft | 26.3 |
| Loader | NeoForge 26.3.0.99+ |
| Side | **Client only** (works in singleplayer and on any server) |
| Java | 25 (bundled with Minecraft) |
| Microphone | Required |
| Disk | ~1 GB free for the models |
| RAM | ~1–3 GB extra while active |
| GPU | Optional, CUDA-capable, togglable |

---

## Installation

1. Install NeoForge 26.3 for Minecraft 26.3.
2. Drop the jar into `.minecraft/mods/`.
3. Launch the game. The warning screen appears **once** — read it and accept.
4. Pick a language in **Settings** (default key: `L`).
5. Play. Pick up an item, close your inventory, and say the word.

---

## Configuration

`config/malanguagelearning/config.json`

| Key | Meaning |
|---|---|
| `targetLanguage` | Language you are learning (`ja`, `de`, `es`, …) |
| `nativeLanguage` | Language for the secondary translation |
| `primaryScript` / `secondaryScript` | Scripts for the two translation slots |
| `showPhonetics` | Show the IPA reading |
| `answerSeconds` | Time limit per word (default 15) |
| `dropOnFailure` | Drop the item when you fail |
| `speakWordOnFailure` | Speak the word aloud on failure |
| `inferenceDevice` | `cpu` or `gpu` |
| `sidecarPort` | Local port (default 8737) |

---

## Building from source

```bash
./gradlew build          # jar lands in build/libs/
```

Requires JDK 25. The first build decompiles Minecraft and takes a few minutes.

---

## License

MIT.
