"""Generate data-structure implementations with one or more clients."""

from __future__ import annotations

import argparse
import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

from pipeline.clients.copilot import CopilotClient, CopilotClientNoTools
from pipeline.clients.interface import Client
from pipeline.helpers import find_latest_file, timestamp_now

DEFAULT_PROMPT_DIR = Path("prompts")
DEFAULT_OUTPUT_DIR = Path("Generations")
DEFAULT_MODEL = "gpt-5.6-luna"
PUBLIC_TYPE_PATTERN = re.compile(
    r"\bpublic\s+(?:(?:final|abstract|sealed|non-sealed|strictfp)\s+)*"
    r"(?:class|interface|enum|record|@interface)\s+([A-Za-z_$][\w$]*)"
)


@dataclass(frozen=True)
class GenerationFailure:
    """A prompt/client pair that could not be generated."""

    client_name: str
    datastructure: str
    error: str


def load_prompts(prompt_file: Path | None = None) -> list[dict[str, str]]:
    """Load prompt records from an explicit file or the newest generated file."""
    selected_file = prompt_file or find_latest_file(DEFAULT_PROMPT_DIR, "date:*.json")
    document = json.loads(selected_file.read_text(encoding="utf-8"))
    prompts = document.get("prompts")
    if not isinstance(prompts, list):
        raise ValueError(f"Prompt file does not contain a prompts list: {selected_file}")
    return prompts


def java_filename(response: str, fallback: str) -> str:
    """Return the filename required by a public top-level Java type."""
    public_type = PUBLIC_TYPE_PATTERN.search(response)
    if public_type:
        return f"{public_type.group(1)}.java"
    return f"{fallback}.java"


def run(
    clients: Iterable[Client],
    prompts: Iterable[dict[str, str]],
    output_dir: Path = DEFAULT_OUTPUT_DIR,
) -> list[GenerationFailure]:
    """Run every prompt through every client and continue after failures."""
    failures = []
    prompt_records = list(prompts)
    run_timestamp = timestamp_now()
    for client in clients:
        client_name = getattr(client, "name", client.__class__.__name__.lower())
        for prompt_record in prompt_records:
            datastructure = prompt_record["datastructure"]
            prompt = prompt_record["prompt"]
            try:
                response = client.call(prompt)
                output = (
                    output_dir
                    / run_timestamp
                    / client_name
                    / client.model
                    / java_filename(response, datastructure)
                )
                output.parent.mkdir(parents=True, exist_ok=True)
                output.write_text(response + "\n", encoding="utf-8")
            except (KeyError, OSError, RuntimeError, TypeError, ValueError) as error:
                failures.append(
                    GenerationFailure(client_name, datastructure, str(error))
                )
    return failures


def default_clients() -> list[Client]:
    """Return the initial configured client list."""
    return [CopilotClientNoTools(model=DEFAULT_MODEL)]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--prompt-file",
        type=Path,
        help="Use this prompt JSON file instead of the newest generated file.",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=DEFAULT_OUTPUT_DIR,
        help=f"Root output directory (default: {DEFAULT_OUTPUT_DIR}).",
    )
    args = parser.parse_args(argv)

    prompts = load_prompts(args.prompt_file)
    failures = run(default_clients(), prompts, args.output_dir)
    for failure in failures:
        print(
            f"Failed {failure.client_name}/{failure.datastructure}: "
            f"{failure.error}"
        )
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
