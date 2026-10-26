# malanguage-server

Local speech sidecar for the **MaLanguageLearning** Minecraft mod (NeoForge 26.3).

It runs **outside the game** as a loopback HTTP server and does the heavy lifting:
speech-to-text, text-to-speech and transliteration/phonetics. The Java mod never
inspects audio or runs a model itself — it records 16 kHz mono WAV, posts it here, and
gets JSON back.

```
Minecraft client ──HTTP 127.0.0.1:8737──> malanguage-server ──> ONNX runtime (STT)
                                                             └─> Piper (TTS)
```

## Zero manual setup

The user installs the mod and nothing else. On first launch the mod:

1. downloads a standalone [`uv`](https://github.com/astral-sh/uv) binary into the game's
   config dir,
2. runs `uv venv` + `uv pip install -r` against the `pyproject.toml` shipped inside the
   mod jar (uv also fetches its own CPython — no system Python needed),
3. starts the sidecar with `uv run python -m malanguage_server`.

Consequences for anything in this repo:

* the package must be importable as a plain module — `python -m malanguage_server`,
  never a `pip install -e` step, never a console-script-only entry point;
* no hardcoded absolute paths, no conda, no system libraries, no manual model download;
* every optional dependency is imported lazily, so a partial install still boots and
  reports `/health` instead of dying at import.

## Requirements

* Python ≥ 3.10 (3.12 recommended; the code avoids >3.10 syntax)
* No GPU required. CPU-only `onnxruntime` is the default; CUDA is used when available.
* Disk: ~671 MB for the int8 parakeet-v3 encoder, plus ~60 MB per Piper voice.
  Whisper (only for ja/zh/ko/ar/hi/tr/vi/th/…) additionally needs the `whisper` extra.

## Install and run

```bash
# what the mod does, manually
uv venv
uv pip install fastapi 'uvicorn[standard]' onnx-asr onnxruntime numpy soundfile pykakasi piper-tts
uv run python -m malanguage_server --port 8737
```

Optional extras:

| extra  | install                                        | gives you                                             |
|--------|------------------------------------------------|-------------------------------------------------------|
| whisper| `uv pip install 'malanguage-server[whisper]'`  | `faster-whisper` — real Whisper for ja/zh/ko/…        |
| zh     | `uv pip install 'malanguage-server[zh]'`       | pinyin via `pypinyin`                                 |
| cuda   | `uv pip install 'malanguage-server[cuda]'`     | `onnxruntime-gpu`                                     |
| ja-tts | `uv pip install 'malanguage-server[ja-tts]'`   | `pyopenjtalk-plus` — Piper's Japanese voice needs it  |
| full   | `uv pip install 'malanguage-server[full]'`     | all of the above except CUDA                          |

**No extra required for CJK speech input:** set `MLL_WHISPER_BACKEND=onnx` and the
sidecar serves ja/zh/ko/ar/hi/tr/vi/th/… with the bundled ONNX Whisper-base model
(≈107 MB) using only `onnx-asr` + `onnxruntime`.

**TTS capability gaps are 404, not errors.** Some Piper voices need an extra phonemizer
(Japanese → `pyopenjtalk-plus`, Chinese → `g2pw`, Thai → `tltk`). Without it the voice
still downloads but cannot phonemize; `/synthesize` answers **404** with the missing
package in `message`, so the mod beeps instead of retrying forever. Croatian and Maltese
have no Piper voice upstream at all and always answer 404.

### CLI

```
python -m malanguage_server [--port 8737] [--host 127.0.0.1]
                            [--device auto|cpu|cuda|gpu] [--models-dir PATH]
                            [--log-level debug|info|warning|error]
                            [--preload LANG ...]
```

Every flag has an environment variable equivalent:

| flag          | env               | default                                        |
|---------------|-------------------|------------------------------------------------|
| `--port`      | `MLL_PORT`        | `8737`                                         |
| `--host`      | `MLL_HOST`        | `127.0.0.1`                                    |
| `--device`    | `MLL_DEVICE`      | `auto`                                         |
| `--models-dir`| `MLL_MODELS_DIR`  | `<user-config>/MaLanguageLearning/models`       |
| `--log-level` | `MLL_LOG_LEVEL`   | `info`                                         |

Extra knobs: `MLL_WHISPER_BACKEND=onnx|faster-whisper`, `MLL_WHISPER_MODEL=small|base|large-v3|large-v3-turbo`.

## HTTP API

All responses are JSON unless noted. The server binds loopback only.

### `GET /health`

```json
{
  "status": "ok",
  "version": "1.0.0",
  "device": "cpu",
  "models_loaded": ["parakeet-v3"],
  "loaded_languages": ["en"],
  "models_dir": "/home/user/.config/MaLanguageLearning/models",
  "tts_voices_loaded": [],
  "device_note": "auto: no CUDA provider, using CPU",
  "backends": ["parakeet-v3", "whisper-large-v3-turbo", "whisper-small"],
  "languages_count": 36,
  "uptime_s": 12.4,
  "translit": {
    "ipa_languages": ["de", "en", "es", "fr", "it", "pt", "ru", "tr"],
    "phonemic_languages": ["bg", "cs", "da", "..."],
    "pykakasi": true,
    "pypinyin": false
  }
}
```

Always `200`, even when every model is missing — that is how the mod decides whether
the sidecar came up.

### `GET /languages`

```json
[
  {
    "code": "ja",
    "name": "Japanese",
    "stt_backend": "whisper-large-v3-turbo",
    "tts_voice": "ja_JP",
    "tts_voice_id": "ja_JP-hi_fi_captain-medium",
    "tts_available": true,
    "scripts": ["hiragana", "katakana", "kanji", "romaji"],
    "aliases": []
  },
  {
    "code": "en",
    "name": "English",
    "stt_backend": "parakeet-v3",
    "tts_voice": "en_US",
    "tts_voice_id": "en_US-amy-medium",
    "tts_available": true,
    "scripts": ["latin"],
    "aliases": []
  }
]
```

`stt_backend` is the *currently resolved* backend, so it reflects
`MLL_WHISPER_BACKEND`/`MLL_WHISPER_MODEL`. `tts_voice` is the empty string when no
Piper voice exists for that language (e.g. Croatian, Maltese).

### `POST /transcribe`

Raw WAV bytes in the body, language in a header:

```bash
curl -X POST http://127.0.0.1:8737/transcribe \
     -H 'X-MLL-Language: ja' \
     -H 'Content-Type: application/octet-stream' \
     --data-binary @word.wav
```

```json
{"text": "のこぎり", "confidence": 0.93, "language": "ja", "model": "whisper-large-v3-turbo", "latency_ms": 812}
```

* input: 16 kHz mono WAV; 16-bit **and** float32 accepted, 8/24/32-bit int too,
  multi-channel is downmixed, a non-16 kHz rate is resampled with numpy.
* limits: 8 MB body, 30 s max, 0.05 s min.
* `confidence` is the mean per-token probability (parakeet), or the mean segment
  probability (Whisper); `0.0` when the backend cannot report one.
* The first request for a language downloads and loads its model — expect that one to be
  slow. Later requests are fast and LRU-cached.

Errors:

| status | when                                                           |
|--------|----------------------------------------------------------------|
| 400    | unsupported language, undecodable/too short/too long audio     |
| 503    | backend needs a package that is not installed (message says which, and for which language) |
| 503    | model download failed                                           |
| 500    | inference itself blew up                                        |

A 503 body is actionable, e.g.:

```json
{
  "error": "backend_unavailable",
  "message": "language 'ja' needs Whisper (large-v3-turbo) because parakeet-v3 only covers 25 European languages, and the 'faster-whisper' package is not installed (ModuleNotFoundError: No module named 'faster_whisper')",
  "language": "ja",
  "backend": "whisper-large-v3-turbo",
  "hint": "uv pip install faster-whisper   (or 'malanguage-server[whisper]')  --  alternatively set MLL_WHISPER_BACKEND=onnx to use the bundled ONNX Whisper base model with no extra download"
}
```

### `POST /synthesize`

```bash
curl -X POST http://127.0.0.1:8737/synthesize \
     -H 'Content-Type: application/json' \
     -d '{"text":"のこぎり","language":"ja"}' -o word.wav
```

Returns `audio/wav` bytes (16-bit mono, voice's native rate — 22.05 kHz for most
Piper voices) with `X-MLL-Voice` and `X-MLL-Language` headers.

| status | when                                                      |
|--------|-----------------------------------------------------------|
| 404    | no TTS voice for that language → **mod falls back to a beep** |
| 502    | voice exists but synthesis failed                          |
| 400    | empty `text`                                               |
| 503    | `piper-tts` missing                                        |

When a voice is used for the first time it is downloaded (~60 MB) before responding.

### `POST /transliterate`

```bash
curl -X POST http://127.0.0.1:8737/transliterate \
     -H 'Content-Type: application/json' \
     -d '{"text":"鋸","language":"ja"}'
```

```json
{
  "primary": "のこぎり",
  "secondary": "nokogiri",
  "phonetic": "/no.ko.ɡi.ɾi/",
  "script": "hiragana",
  "alternatives": {
    "plain": "鋸",
    "katakana": "ノコギリ",
    "kanji": "鋸",
    "romaji": "nokogiri",
    "hiragana": "のこぎり",
    "furigana": "のこぎり【鋸】"
  },
  "language": "ja"
}
```

Non-CJK example (`{"text":"diamond","language":"en"}`):

```json
{
  "primary": "diamond",
  "secondary": "/ˈdaɪəmənd/",
  "phonetic": "/ˈdaɪəmənd/",
  "script": "latin",
  "alternatives": {"plain": "diamond", "latin": "diamond", "ipa": "/ˈdaɪəmənd/"}
}
```

Rules:

* **CJK** — Japanese goes through `pykakasi` (hiragana primary, romaji secondary, mora
  segmented IPA); Korean is Romanized in-process (Hangul → Revised Romanization, plus a
  jamo IPA approximation); Chinese uses `pypinyin` when installed, otherwise returns the
  text with an empty `phonetic`.
* **Everything else** — the word as `primary`, a bundled rule-table IPA in `phonetic`
  for **en, es, fr, de, it, pt, ru, tr**, and an empty string for languages without a
  table. `alternatives` always contains at least `{"plain": word}`.
* No model, no network, no optional package needed — `/transliterate` works even when
  every STT and TTS dependency is missing.

## Models

| language group | backend | notes |
|---|---|---|
| 25 European languages (en, es, fr, de, it, pt, ru, nl, pl, sv, …) | `parakeet-v3` | ONNX int8 from `istupakov/parakeet-tdt-0.6b-v3-onnx`, ≈671 MB, CPU-friendly |
| ja, zh, ko, ar, hi, tr, vi, th, id, he, fa | `whisper-large-v3-turbo` | needs the `whisper` extra, or `MLL_WHISPER_BACKEND=onnx` |

* **Lazy.** A model is downloaded and loaded on the first request for a language.
* **Hot-swappable, LRU of 2.** Loading a third model frees the least recently used one
  (`del` + `gc.collect()`).
* **Device.** `--device auto` probes onnxruntime and uses CUDA when present; `--device
  cuda` (or its alias `gpu`, which the mod sends) degrades to CPU with a logged warning
  if `onnxruntime-gpu` is absent. CUDA is never required.
* **Cache.** `--models-dir` / `MLL_MODELS_DIR`; files are written to `*.part` and
  renamed on completion, so an interrupted download never looks complete.

### Progress protocol

The mod renders a progress bar by reading the sidecar's **stderr**. Lines are:

```
MLL_PROGRESS {"stage":"download","model":"parakeet-v3","percent":42}
```

`stage` ∈ `download` | `load` | `tts` | `unload`; `percent` is 0–100; an optional
`message` field is for humans. Never treat these lines as fatal output — they are
advisory.

## Development

```bash
python3 -m venv .venv && . .venv/bin/activate
pip install fastapi 'uvicorn[standard]' onnx-asr onnxruntime numpy soundfile pykakasi piper-tts
python -m malanguage_server --port 8799 --log-level debug
curl -s http://127.0.0.1:8799/health | python -m json.tool
```

Layout:

```
malanguage_server/
  __main__.py       CLI (argparse) + uvicorn bootstrap
  app.py            FastAPI app, audio decoding/resampling, HTTP errors
  model_manager.py  backend table, downloads, LRU, device probing, MLL_PROGRESS
  translit.py       script detection, kana/Hangul, bundled IPA rule tables
  tts.py            Piper voice download/cache/synthesis
  languages.py      the language registry (backend + voice + scripts per language)
```

## Licence

MIT.
