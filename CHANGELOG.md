# Changelog

## 0.1.0 — first release

Initial release of **MaLanguageLearning** for Minecraft 26.3 (NeoForge).

### Added
- Speak-the-item-name challenge: acquire an item, close your inventory, and you have
  15 seconds to say its name in the language you are learning.
- Each item must be learned once; derivatives (slabs, stairs, walls) are separate words.
- Failure behaviour: the word is spoken aloud via TTS, then the item is dropped on the floor.
- Two-translation system: primary script plus a secondary rendering (another script, the
  native-language translation, or IPA phonetics).
- Per-language script options, including hiragana / katakana / kanji / romaji / furigana
  for Japanese and pinyin / simplified / traditional for Chinese.
- Speech recognition runs **outside the game** as a local sidecar process; the mod connects
  over `127.0.0.1` and never sends audio anywhere else.
- Zero-setup provisioning: the mod downloads a `uv` runtime, a Python interpreter, its
  dependencies and the speech models on first run, all behind an explicit consent screen.
- Model-agnostic engine: Parakeet v3 covers 25 European languages, Whisper covers the rest,
  and backends are swappable per language.
- CPU / GPU inference toggle.
- In-game settings screen (default key `L`).

### Warnings
- The int8 Parakeet v3 model is ~671 MB on disk; full precision is ~2.5 GB.
- Expect ~1–3 GB of extra system RAM while recognition is active.
- GPU mode requires a CUDA-capable GPU and additional VRAM.
- The mod has a noticeable performance impact.
