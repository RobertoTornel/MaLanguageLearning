# MaLanguageLearning — Implementation Plan

**Target:** Minecraft 26.3, NeoForge 26.3.0.99, Java 25, Gradle 9.2.1 (wrapper 9.2.1), JDK 25 toolchain.
**Baseline build:** verified green (`mc_gradle build`, 2026-10-26).
**Decompiled sources for API truth:** `build/moddev/artifacts/minecraft-patched-26.3.0.99-sources.jar` + NeoForge sources jar.

## 1. Goal

A client-side language-learning mod. When the player acquires a new item, on closing the
inventory a popup demands they **speak the item's name** in the configured language. They get
**15 seconds**. On failure: TTS speaks the word, then the item is **dropped to the floor**.
Each item is required **once** (cobblestone learned ≠ stone slab learned).

## 2. Locked decisions (from user intake 2026-10-26)

| Decision | Value |
|---|---|
| MC version / loader | **26.3** / NeoForge 26.3.0.99 |
| Distribution | **Client-side mod** (mic capture is client-side) |
| STT engine | **Model-agnostic**, hot-swappable; parakeet v3 default |
| STT location | **Out-of-game local process**, game connects over localhost HTTP |
| Translation system | Local sidecar does transliteration + phonetics, disk-cached |
| Failure behaviour | 15s window → TTS speaks word → item dropped to floor |
| Setup | **Zero manual setup**: mod auto-provisions runtime + models behind a consent screen |

## 3. Architecture

```
Minecraft client (NeoForge 26.3)
  └─ MaLanguageLearning (Java 25)
        ├─ Java Sound API .......... records mic, no native deps
        ├─ HttpClient (JDK) ........ http://127.0.0.1:8737
        └─ TTS ..................... sidecar WAV → client playback
                    │ localhost
  └─ Sidecar (Python, provisioned by mod on first run)
        ├─ uv standalone binary .... downloaded per-OS (16MB)
        ├─ uv-managed CPython 3.12 . no system Python needed
        ├─ venv .................... onnx-asr, onnxruntime(-gpu), piper-tts, pykakasi
        ├─ models .................. downloaded on demand, disk-cached
        └─ HTTP API ................ /health /transcribe /synthesize /transliterate
```

**Zero-setup chain:** mod downloads `uv` → `uv` installs Python → `uv` installs deps →
deps download models on first use. User approves once on the consent screen.

**Hot-swappable models:** `config/malanguagelearning/models.toml` maps a language code to a
backend (`parakeet-v3`, `whisper-large-v3-turbo`, `whisper-small`, …). Selecting a language
selects the model; the sidecar lazily downloads/loads the one needed.

## 4. Speech engine strategy

Parakeet v3 covers **en es fr de bg hr cs da nl et fi el hu it lv lt mt pl pt ro sk sl sv ru uk**
(25 languages, European only — verified from the model card). Japanese/Chinese/Korean need
Whisper. Because the backend is model-agnostic, both are supported and swappable.

## 5. Two-translation system

- **Primary script**: the word written in the target language's script, chosen per language
  (e.g. Japanese: kana / kanji / furigana / romaji).
- **Secondary**: the same word in any of the per-language script options **plus phonetics**
  (IPA), shown under the primary.
- Data resolved by the sidecar (pykakasi-class transliteration + ICU + bundled tables),
  cached to `config/malanguagelearning/cache/`.

## 6. Acquire-once ledger

`config/malanguagelearning/learned.json`, keyed by item registry ID, **not** by translation key
(the prompt is showed + ledger must not treat `stone` and `stone_slab` as one).
Cobblestone learned ⇒ cobblestone never asked again; every derivative is asked separately.

## 7. Failure behaviour

Close inventory → 15s countdown popup → on expiry: sidecar `/synthesize` returns WAV of the word
→ client plays it → stack is removed from inventory and dropped at the player's feet.

## 8. Hardware warning

Int8 parakeet v3 ≈ **671 MB** on disk (encoder 652 MB + joiner 18 MB); fp32 ≈ **2.5 GB**.
Resident RAM roughly 1–3 GB depending on backend/precision; GPU path needs CUDA-capable
runtime plus VRAM for the encoder. CPU mode is togglable. This is stated in ALL CAPS on the
consent screen, README, and Modrinth description.

## 9. Deliverables

1. NeoForge mod jar (client) — builds via `mc_gradle`.
2. Python sidecar (`sidecar/`) — zero-setup, auto-provisioned.
3. GitHub repo `MaLanguageLearning` pushed to `RobertoTornel`.
4. Modrinth project `MaLanguageLearning` published under `Mananite04` with the big warning.
5. `README.md`, `docs/` (API + model swapping), publish scripts.

## 10. Verification gates

- `mc_gradle build` green.
- Sidecar: `/health` 200, `/transcribe` on a synthetic clip, `/transliterate` for a JP word.
- Manual: dev client boots with consent screen, no crash on no-consent path.
