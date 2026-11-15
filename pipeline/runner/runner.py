from __future__ import annotations

import argparse
import json
import os
import re
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

from pipeline.clients.copilot import CopilotClient, CopilotClientNoTools
from pipeline.clients.harness import HarnessClient
from pipeline.clients.interface import (
    Client,
    ClientTimeoutError,
    timeout_failure_source,
    validate_runs,
    validate_skip_runs,
)
from pipeline.clients.lm_studio import LMStudioClient
from pipeline.clients.openai import OpenAIClient
from pipeline.clients.anthropic import AnthropicClient
from pipeline.helpers import PROMPT_CATEGORIES, find_latest_file, timestamp_now

DEFAULT_PROMPT_DIR = Path("prompts")
DEFAULT_OUTPUT_DIR = Path("Generations")
DEFAULT_LM_STUDIO_MODEL = "qwen/qwen3.5-9b"
MANIFEST_FILENAME = "manifest.json"
PUBLIC_TYPE_PATTERN = re.compile(
    r"\bpublic\s+(?:(?:final|abstract|sealed|non-sealed|strictfp)\s+)*"
    r"(?:class|interface|enum|record|@interface)\s+([A-Za-z_$][\w$]*)"
)
TOTAL_RUNS = 3


@dataclass(frozen=True)
class GenerationFailure:
    client_name: str
    datastructure: str
    error: str
    category: str = "datastructures"
    run_name: str = "run-1"


def load_prompts(prompt_file: Path | None = None) -> list[dict[str, str]]:
    selected_file = prompt_file or find_latest_file(DEFAULT_PROMPT_DIR, "date:*.json")
    document = json.loads(selected_file.read_text(encoding="utf-8"))
    prompts = document.get("prompts") if isinstance(document, dict) else None
    if not isinstance(prompts, list) or not all(
        isinstance(record, dict)
        and isinstance(record.get("datastructure"), str)
        and isinstance(record.get("prompt"), str)
        for record in prompts
    ):
        raise ValueError(f"Prompt file does not contain a prompts list: {selected_file}")
    return [
        {**record, "prompt_file": str(selected_file.resolve())}
        for record in prompts
    ]


def java_filename(response: str, fallback: str) -> str:
    public_type = PUBLIC_TYPE_PATTERN.search(response)
    if public_type:
        return f"{public_type.group(1)}.java"
    return f"{fallback}.java"


def _relative_link(path: Path, directory: Path) -> str:
    return Path(os.path.relpath(path.resolve(), directory.resolve())).as_posix()


def _write_manifests(
    root: Path,
    run_dir: Path,
    generations: list[dict[str, str]],
) -> None:
    run_manifest = run_dir / "results" / MANIFEST_FILENAME
    run_entries = []
    run_parts = run_dir.relative_to(root).parts
    for entry in generations:
        if Path(entry["java_file"]).parts[:len(run_parts)] != run_parts:
            continue
        run_entry = dict(entry)
        for key in ("java_file", "prompt_file", "metadata_file"):
            if key in entry:
                run_entry[key] = _relative_link(root / entry[key], run_manifest.parent)
        run_entries.append(run_entry)
    documents = {
        root / MANIFEST_FILENAME: generations,
        run_manifest: run_entries,
    }
    with tempfile.TemporaryDirectory(dir=root, prefix=".manifest-") as directory:
        staged = []
        for index, (path, entries) in enumerate(documents.items()):
            path.parent.mkdir(parents=True, exist_ok=True)
            temporary = Path(directory) / f"{index}.json"
            temporary.write_text(
                json.dumps({"generations": entries}, indent=2, ensure_ascii=False) + "\n",
                encoding="utf-8",
            )
            backup = None
            if path.exists():
                backup = Path(directory) / f"{index}.previous"
                backup.write_bytes(path.read_bytes())
            staged.append((path, temporary, backup))
        committed = []
        try:
            for path, temporary, backup in staged:
                temporary.replace(path)
                committed.append((path, backup))
        except OSError:
            for path, backup in reversed(committed):
                if backup is None:
                    path.unlink()
                else:
                    backup.replace(path)
            raise


def _write_program(
    output: Path,
    response: str,
    root: Path,
    generations: list[dict[str, str]],
) -> None:
    created = False
    try:
        with output.open("x", encoding="utf-8") as stream:
            created = True
            stream.write(response + "\n")
        _write_manifests(root, output.parent.parent, generations)
    except (OSError, UnicodeError):
        if created:
            output.unlink()
        raise


def run(
    clients: Iterable[Client],
    prompts: Iterable[dict[str, str]],
    output_dir: Path = DEFAULT_OUTPUT_DIR,
    *,
    generation_dir: Path | None = None,
) -> list[GenerationFailure]:
    clients = list(clients)
    for client in clients:
        runs = getattr(client, "runs", 1)
        validate_runs(runs)
        validate_skip_runs(getattr(client, "skip_runs", 0), runs)
    failures = []
    prompt_records = []
    names = set()
    for prompt_record in prompts:
        category = prompt_record.get("category", "datastructures")
        if category not in PROMPT_CATEGORIES:
            raise ValueError(f"Unknown prompt category: {category!r}")
        name = prompt_record.get("name", f"{category}/{prompt_record['datastructure']}")
        if not isinstance(name, str) or not name.strip():
            raise ValueError(f"Invalid prompt name: {name!r}")
        if name in names:
            raise ValueError(f"Duplicate prompt name: {name!r}")
        names.add(name)
        prompt_records.append({**prompt_record, "name": name, "category": category})
    run_directory = (
        generation_dir if generation_dir is not None else output_dir / timestamp_now()
    )
    manifest_path = run_directory / MANIFEST_FILENAME
    generations = []
    if manifest_path.exists():
        document = json.loads(manifest_path.read_text(encoding="utf-8"))
        required_fields = {
            "prompt_name", "problem", "category", "client", "model",
            "java_file",
        }
        if (
            not isinstance(document, dict)
            or not isinstance(document.get("generations"), list)
            or not all(
                isinstance(entry, dict)
                and required_fields <= entry.keys()
                and ("prompt_file" in entry or "metadata_file" in entry)
                and all(isinstance(value, str) for value in entry.values())
                for entry in document["generations"]
            )
        ):
            raise ValueError(f"Invalid generation manifest: {manifest_path}")
        generations = document["generations"]
    snapshot_file = None
    for client, run_number in (
        (client, number)
        for client in clients
        for number in range(
            getattr(client, "skip_runs", 0) + 1,
            getattr(client, "runs", 1) + 1,
        )
    ):
        run_name = f"run-{run_number}"
        client_name = getattr(client, "name", client.__class__.__name__.lower())
        skip_chats = getattr(client, "skip_chats", 0)
        for index, prompt_record in enumerate(prompt_records, start=1):
            datastructure = prompt_record["datastructure"]
            prompt = prompt_record["prompt"]
            category = prompt_record.get("category", "datastructures")
            name = prompt_record["name"]
            if index <= skip_chats:
                print(
                    f"Skipping {client_name}/{client.model}/{run_name}/{name} "
                    f"({index}/{len(prompt_records)}): skip_chats={skip_chats}",
                    flush=True,
                )
                continue
            print(
                f"Generating {client_name}/{client.model}/{run_name}/{name} "
                f"({index}/{len(prompt_records)})...",
                flush=True,
            )
            try:
                if "prompt_file" in prompt_record:
                    prompt_file = Path(prompt_record["prompt_file"])
                else:
                    if snapshot_file is None:
                        prompt_dir = run_directory / "prompts"
                        prompt_dir.mkdir(parents=True, exist_ok=True)
                        with tempfile.NamedTemporaryFile(
                            mode="w", encoding="utf-8", dir=prompt_dir,
                            prefix="inputs-", suffix=".json", delete=False,
                        ) as snapshot:
                            snapshot_file = Path(snapshot.name)
                            json.dump(
                                {"prompts": prompt_records}, snapshot,
                                indent=2, ensure_ascii=False,
                            )
                            snapshot.write("\n")
                    prompt_file = snapshot_file
                originals = [
                    record for record in load_prompts(prompt_file)
                    if record.get(
                        "name",
                        f"{record.get('category', 'datastructures')}/{record['datastructure']}",
                    ) == name
                ]
                if len(originals) != 1 or any(
                    originals[0].get(key, default) != prompt_record[key]
                    for key, default in (
                        ("datastructure", ""), ("category", "datastructures"), ("prompt", ""),
                    )
                ):
                    raise ValueError(f"Prompt {name!r} does not match its source: {prompt_file}")
                timeout_error = None
                try:
                    response = client.call(prompt)
                except ClientTimeoutError as error:
                    timeout_error = error
                    response = timeout_failure_source(datastructure, error.attempts)
                output_model_name = getattr(
                    client, "output_model_name", client.model
                )
                output = (
                    run_directory
                    / client_name
                    / output_model_name
                    / run_name
                    / category
                    / java_filename(response, datastructure)
                )
                output.parent.mkdir(parents=True, exist_ok=True)
                manifest_entry = {
                    "prompt_name": name,
                    "problem": datastructure,
                    "category": category,
                    "client": client_name,
                    "model": client.model,
                    "run": run_name,
                    "java_file": output.relative_to(run_directory).as_posix(),
                    "prompt_file": _relative_link(prompt_file, run_directory),
                }
                _write_program(output, response, run_directory, [*generations, manifest_entry])
                generations.append(manifest_entry)
                if timeout_error is not None:
                    print(f"Saved timeout placeholder {output}", flush=True)
                    raise timeout_error
                print(f"Saved {output}", flush=True)
            except (KeyError, OSError, RuntimeError, TypeError, ValueError) as error:
                failures.append(
                    GenerationFailure(
                        client_name, datastructure, str(error), category, run_name,
                    )
                )
                print(
                    f"Failed {client_name}/{client.model}/{run_name}/{name}: {error}",
                    flush=True,
                )
    return failures


def default_clients() -> list[Client]:
    return [
        # LMStudioClient(model="qwen/qwen3.5-9b", runs=TOTAL_RUNS, reasoning='off'),
        # LMStudioClient(model="google/gemma-4-12b", runs=TOTAL_RUNS, reasoning='off'),
        # LMStudioClient(model="qwen2.5-coder-7b-instruct-mlx", runs=TOTAL_RUNS, reasoning='off'),
        # HarnessClient(model="gpt-6.1-sol", runs=TOTAL_RUNS),
        # HarnessClient(model="claude-opus-5.5", runs=TOTAL_RUNS),
        # LMStudioClient(model="qwen2.5-coder-7b-instruct-mlx", runs=TOTAL_RUNS),
        # LMStudioClient(model="google/gemma-4-e4b", runs=TOTAL_RUNS, reasoning='off'),
        AnthropicClient(model="claude-haiku-5-5", runs=TOTAL_RUNS, skip_runs=1),
        AnthropicClient(model="claude-sonnet-5-5", runs=TOTAL_RUNS),
        OpenAIClient(model="gpt-6.1-terra", runs=TOTAL_RUNS),
        # CopilotClientNoTools(model="gpt-6-luna", runs=TOTAL_RUNS),
        # HarnessClient(model="gpt-6-luna", runs=TOTAL_RUNS),
        # HarnessClient(model="gpt-5-mini", runs=TOTAL_RUNS),
        # HarnessClient(model="claude-haiku-5.5", runs=TOTAL_RUNS),
        # HarnessClient(model="claude-sonnet-5.5", runs=TOTAL_RUNS),
        # HarnessClient(model="gpt-6.1-terra", runs=TOTAL_RUNS),
    ]


def main(argv: list[str] | None = None) -> int:
    run_timestamp = timestamp_now()
    parser = argparse.ArgumentParser(
        description="Generate categorized Java implementations with one or more clients."
    )
    parser.add_argument(
        "--prompt-file",
        type=Path,
        help="Use this prompt JSON file instead of the newest generated file.",
    )
    destination = parser.add_mutually_exclusive_group()
    destination.add_argument(
        "--output-dir",
        type=Path,
        default=DEFAULT_OUTPUT_DIR,
        help=f"Root output directory (default: {DEFAULT_OUTPUT_DIR}).",
    )
    destination.add_argument(
        "--generation-dir",
        type=Path,
        help="Generate directly into this folder, preserving existing Java files.",
    )
    args = parser.parse_args(argv)

    prompts = load_prompts(args.prompt_file)
    failures = run(
        default_clients(),
        prompts,
        args.output_dir,
        generation_dir=(
            args.generation_dir
            if args.generation_dir is not None
            else args.output_dir / run_timestamp
        ),
    )
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
