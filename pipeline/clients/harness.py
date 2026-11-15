from __future__ import annotations

import tempfile
from dataclasses import dataclass
from pathlib import Path

from pipeline.clients.copilot import CopilotClient, _call_copilot

AVAILABLE_TOOLS = (
    "bash", "read_bash", "stop_bash", "list_bash",
    "view", "rg", "glob", "apply_patch", "web_fetch",
)


@dataclass(frozen=True)
class HarnessClient(CopilotClient):
    name: str = "copilot-tools"

    def call(self, prompt: str) -> str:
        with tempfile.TemporaryDirectory(prefix="copilot-tools-") as directory:
            return _call_copilot(
                [
                    "copilot",
                    "-p",
                    prompt,
                    "--model",
                    self.model,
                    "--silent",
                    "--output-format",
                    "json",
                    "--stream",
                    "off",
                    f"--available-tools={','.join(AVAILABLE_TOOLS)}",
                    "--allow-all-tools",
                    "--allow-all-urls",
                    "--disallow-temp-dir",
                    "--disable-builtin-mcps",
                    "--no-custom-instructions",
                    "--no-ask-user",
                    "--sync-session=off",
                    "--no-auto-update",
                ],
                json_output=True,
                cwd=Path(directory),
                final_only=True,
            )