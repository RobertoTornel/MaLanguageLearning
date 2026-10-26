"""MaLanguageLearning speech sidecar.

A local HTTP server (127.0.0.1) that provides speech-to-text, text-to-speech and
transliteration for the Minecraft mod.  It is launched by the mod through ``uv`` so the
end user never installs Python or runs a command.

Run it directly with::

    python -m malanguage_server --port 8737
"""

from __future__ import annotations

__version__ = "1.0.0"
__all__ = ["__version__", "create_app"]


def create_app(*args, **kwargs):
    """Lazy re-export of :func:`malanguage_server.app.create_app`.

    Kept lazy so ``import malanguage_server`` never pulls FastAPI, onnxruntime or any
    other heavy dependency (the CLI and packaging tools import the package root).
    """
    from .app import create_app as _create_app  # noqa: PLC0415 - deliberately lazy

    return _create_app(*args, **kwargs)
