from __future__ import annotations

import re
from typing import Protocol


class ClientTimeoutError(RuntimeError):
    def __init__(self, message: str, *, attempts: int) -> None:
        super().__init__(message)
        self.attempts = attempts


def timeout_failure_source(problem_name: str, attempts: int) -> str:
    if not re.fullmatch(r"[A-Za-z_$][\w$]*", problem_name):
        raise ValueError("Timeout placeholder problem name must be a Java identifier")
    return (
        f"public class {problem_name} {{\n"
        f"    Generation failed after timeout retry exhaustion ({attempts} attempts).\n"
        "}"
    )


def validate_runs(runs: int) -> None:
    if isinstance(runs, bool) or not isinstance(runs, int) or runs < 1:
        raise ValueError("runs must be a positive integer")


def validate_skip_runs(skip_runs: int, runs: int) -> None:
    if isinstance(skip_runs, bool) or not isinstance(skip_runs, int) or skip_runs < 0:
        raise ValueError("skip_runs must be a nonnegative integer")
    if skip_runs >= runs:
        raise ValueError("skip_runs must be less than runs")


class Client(Protocol):
    def call(self, prompt: str) -> str:
        pass
