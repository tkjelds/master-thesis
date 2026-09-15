from __future__ import annotations

import subprocess
from dataclasses import dataclass


class CopilotClientError(RuntimeError):
    """Raised when the Copilot CLI cannot generate a response."""


def _call_copilot(command: list[str]) -> str:
    try:
        result = subprocess.run(
            command,
            capture_output=True,
            text=True,
            check=False,
        )
    except OSError as error:
        raise CopilotClientError(f"Copilot CLI error: {error}") from error

    if result.returncode != 0:
        details = result.stderr.strip() or result.stdout.strip()
        message = f"Copilot CLI exited with status {result.returncode}"
        if details:
            message += f": {details}"
        raise CopilotClientError(message)

    return result.stdout.strip()


@dataclass(frozen=True)
class CopilotClient:
    """Client for the local GitHub Copilot CLI."""

    model: str
    name: str = "copilot"

    def call(self, prompt: str) -> str:
        return _call_copilot(["copilot", "-p", prompt, "--model", self.model])


@dataclass(frozen=True)
class CopilotClientNoTools(CopilotClient):
    """Copilot client configured to behave like a text-only completion API."""

    name: str = "copilot-no-tools"

    def call(self, prompt: str) -> str:
        return _call_copilot(
            [
                "copilot",
                "-p",
                prompt,
                "--model",
                self.model,
                "--silent",
                "--output-format",
                "text",
                "--stream",
                "off",
                "--available-tools=",
                "--disable-builtin-mcps",
                "--no-custom-instructions",
                "--no-ask-user",
                "--no-remote",
                "--no-remote-export",
                "--no-auto-update",
            ]
        )
