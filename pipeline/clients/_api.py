from __future__ import annotations

import math
import os
from pathlib import Path
from urllib.parse import quote

from dotenv import dotenv_values

from pipeline.clients.interface import validate_runs, validate_skip_runs

DOTENV_PATH = Path(__file__).resolve().parents[2] / ".env"


def validate_configuration(
    model: str, runs: int, max_output_tokens: int, timeout: float, max_retries: int,
    skip_runs: int = 0,
) -> None:
    validate_runs(runs)
    validate_skip_runs(skip_runs, runs)
    if not isinstance(model, str) or not model.strip():
        raise ValueError("model must be a nonblank string")
    for name, value, minimum in (
        ("max_output_tokens", max_output_tokens, 1),
        ("max_retries", max_retries, 0),
    ):
        if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
            raise ValueError(f"{name} must be an integer >= {minimum}")
    if (
        isinstance(timeout, bool)
        or not isinstance(timeout, (int, float))
        or not math.isfinite(timeout)
        or timeout <= 0
    ):
        raise ValueError("timeout must be a finite positive number")


def api_key(variable: str) -> str:
    if variable in os.environ:
        value = os.environ[variable]
    else:
        try:
            value = dotenv_values(DOTENV_PATH).get(variable)
        except (OSError, UnicodeError):
            raise RuntimeError(f"Could not read {variable} from .env") from None
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{variable} must be set in the environment or repository .env")
    return value.strip()


def output_model_name(model: str) -> str:
    encoded = quote(model, safe="-_.")
    return encoded.replace(".", "%2E") if encoded in {".", ".."} else encoded


def sdk_error_detail(error: Exception) -> str:
    status = getattr(error, "status_code", None)
    if isinstance(status, int) and not isinstance(status, bool):
        return f"{type(error).__name__} (HTTP {status})"
    return type(error).__name__
