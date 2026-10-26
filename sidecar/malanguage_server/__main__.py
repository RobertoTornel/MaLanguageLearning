"""Command line entry point: ``python -m malanguage_server``.

The Minecraft mod launches exactly this command through ``uv run``, so the module must
work with nothing but a ``sidecar/`` directory on disk - no installed package, no
``PYTHONPATH`` tweaks from the caller.
"""

from __future__ import annotations

import argparse
import logging
import os
import sys
from pathlib import Path

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8737

logger = logging.getLogger("malanguage_server")


def _ensure_importable() -> None:
    """Make ``malanguage_server`` importable when run from the source tree.

    ``python -m malanguage_server`` already sets this up; the guard matters when the
    mod invokes ``python /path/to/sidecar/malanguage_server/__main__.py`` or when the
    package is copied into a directory that is not on ``sys.path``.
    """
    package_dir = Path(__file__).resolve().parent
    parent = str(package_dir.parent)
    if parent not in sys.path:
        sys.path.insert(0, parent)


def build_parser() -> argparse.ArgumentParser:
    """CLI definition.  Every flag also honours an ``MLL_*`` environment variable."""
    parser = argparse.ArgumentParser(
        prog="malanguage_server",
        description="MaLanguageLearning speech sidecar (STT + TTS + transliteration)",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=int(os.environ.get("MLL_PORT", DEFAULT_PORT)),
        help=f"TCP port to bind (default: {DEFAULT_PORT}, env MLL_PORT)",
    )
    parser.add_argument(
        "--host",
        default=os.environ.get("MLL_HOST", DEFAULT_HOST),
        help=f"interface to bind (default: {DEFAULT_HOST}, env MLL_HOST)",
    )
    parser.add_argument(
        "--device",
        default=os.environ.get("MLL_DEVICE", "auto"),
        choices=("auto", "cpu", "cuda", "gpu"),
        help="inference device: auto|cpu|cuda|gpu ('gpu' is an alias for 'cuda', kept "
        "because the Minecraft mod sends it); degrades to cpu when onnxruntime-gpu is "
        "absent (default: auto, env MLL_DEVICE)",
    )
    parser.add_argument(
        "--models-dir",
        default=os.environ.get("MLL_MODELS_DIR") or "",
        help="model cache directory (default: <user-config>/MaLanguageLearning/models, "
        "env MLL_MODELS_DIR)",
    )
    parser.add_argument(
        "--log-level",
        default=os.environ.get("MLL_LOG_LEVEL", "info"),
        choices=("debug", "info", "warning", "error"),
        help="log verbosity (default: info, env MLL_LOG_LEVEL)",
    )
    parser.add_argument(
        "--preload",
        action="append",
        default=[],
        metavar="LANG",
        help="language code to load eagerly at startup (repeatable)",
    )
    parser.add_argument(
        "--version",
        action="version",
        version=_version_string(),
    )
    return parser


def _version_string() -> str:
    """``--version`` output."""
    from . import __version__  # noqa: PLC0415 - avoids importing the package root early

    return f"malanguage-server {__version__}"


def configure_logging(level: str) -> None:
    """Log to stderr so the mod can merge it with the ``MLL_PROGRESS`` lines."""
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
        datefmt="%H:%M:%S",
        stream=sys.stderr,
        force=True,
    )


def main(argv: list[str] | None = None) -> int:
    """Parse arguments, build the app and serve until interrupted."""
    _ensure_importable()

    parser = build_parser()
    args = parser.parse_args(argv)
    configure_logging(args.log_level)

    from . import __version__  # noqa: PLC0415
    from .app import create_app  # noqa: PLC0415 - heavy imports stay out of --help
    from .model_manager import default_models_dir  # noqa: PLC0415

    models_dir = Path(args.models_dir) if args.models_dir else default_models_dir()
    models_dir.mkdir(parents=True, exist_ok=True)

    logger.info("malanguage-server %s", __version__)
    logger.info("models dir: %s", models_dir)
    logger.info("binding http://%s:%d", args.host, args.port)

    app = create_app(models_dir=models_dir, device=args.device)

    for language in args.preload:
        try:
            app.state.mll.models.preload(language)
        except Exception as exc:  # noqa: BLE001 - preload is best effort
            logger.warning("preload of %s failed: %s", language, exc)

    try:
        import uvicorn  # noqa: PLC0415
    except Exception as exc:  # noqa: BLE001
        print(f"error: uvicorn is not installed ({exc})", file=sys.stderr)
        return 2

    # One worker: models are in-process and guarded by a lock; multiple workers would
    # duplicate several GB of weights.  ``uvicorn.run`` with an app object is
    # single-process by definition, which is exactly what we want here.
    uvicorn.run(
        app,
        host=args.host,
        port=args.port,
        log_level=args.log_level,
        access_log=args.log_level == "debug",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
