from __future__ import annotations

import subprocess


def call_copilot(prompt: str, modelname: str) -> str:
    command = ["copilot", "-p", prompt, "--model", modelname]
    try:
        result = subprocess.run(
            command,
            capture_output=True,
            text=True,
            check=False,
        )
    except OSError as error:
        return f"Copilot CLI error: {error}"

    if result.returncode != 0:
        details = result.stderr.strip() or result.stdout.strip()
        if details:
            return f"Copilot CLI exited with status {result.returncode}: {details}"
        return f"Copilot CLI exited with status {result.returncode}"

    return result.stdout.strip()
