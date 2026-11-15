from __future__ import annotations

import argparse
import sys
import tempfile
from collections.abc import Iterable
from pathlib import Path

from pipeline.analysis import analyze
from pipeline.analysis.correctness.system_1 import DEFAULT_HOST, DEFAULT_MODEL
from pipeline.clients.interface import Client
from pipeline.helpers import timestamp_now
from pipeline.preprocessor import preprocessor
from pipeline.prompt_generator import generator
from pipeline.runner import runner
from pipeline.verifier import java_verifier
from pipeline.visualisation import visualisation


def run(
    clients: Iterable[Client],
    prompts: Iterable[dict[str, str]],
    output_dir: Path = runner.DEFAULT_OUTPUT_DIR,
    *,
    generation_dir: Path | None = None,
    codeql: str = "codeql",
    infer: str = "infer",
    sokrates: str = "sokrates",
    correctness: bool = True,
    ollama_model: str = DEFAULT_MODEL,
    ollama_host: str = DEFAULT_HOST,
) -> bool:
    clients = list(clients)
    prompts = list(prompts)
    if not clients or not prompts:
        raise ValueError("The pipeline requires at least one client and one prompt")
    resume = generation_dir is not None
    if generation_dir is None:
        output_dir.mkdir(parents=True, exist_ok=True)
        generation_dir = Path(
            tempfile.mkdtemp(prefix=f"{timestamp_now()}-", dir=output_dir)
        ).resolve()
    else:
        generation_dir = generation_dir.resolve()
        generation_dir.mkdir(parents=True, exist_ok=True)
    print(f"==> Generate: {generation_dir}", flush=True)
    failures = runner.run(clients, prompts, generation_dir=generation_dir)
    if not any(generation_dir.rglob("*.java")):
        print("Generation failed: no Java files were produced.", file=sys.stderr)
        return False

    print("==> Preprocess", flush=True)
    try:
        preprocessor.run(generation_dir)
    except (OSError, RuntimeError, ValueError) as error:
        print(f"Preprocessing failed: {error}", file=sys.stderr)
        return False

    successful = not failures
    steps = (
        ("Verify", lambda: java_verifier.run(generation_dir)),
        (
            "Analyze",
            lambda: analyze.run(
                generation_dir, codeql=codeql, infer=infer, overwrite=resume,
                sokrates=sokrates,
                correctness=correctness, ollama_model=ollama_model,
                ollama_host=ollama_host,
            ),
        ),
        ("Visualisation", lambda: visualisation.run(generation_dir)),
    )
    for label, action in steps:
        print(f"==> {label}", flush=True)
        try:
            passed = action()
        except (OSError, RuntimeError, ValueError) as error:
            print(f"{label} failed: {error}", file=sys.stderr)
            passed = False
        if not passed:
            print(f"!! Step failed: {label}", file=sys.stderr)
            successful = False
    return successful


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Generate Java implementations, annotate, evaluate, and export CSV counts."
    )
    parser.add_argument("--prompt-file", type=Path, help="Use an existing prompt file.")
    parser.add_argument(
        "--skip-prompts",
        action="store_true",
        help="Reuse the newest prompt file instead of generating prompts.",
    )
    destination = parser.add_mutually_exclusive_group()
    destination.add_argument(
        "--output-dir",
        type=Path,
        default=runner.DEFAULT_OUTPUT_DIR,
        help=f"Generation root (default: {runner.DEFAULT_OUTPUT_DIR}).",
    )
    destination.add_argument(
        "--generation-dir",
        type=Path,
        help="Resume in this folder, preserving Java files and refreshing evaluation.",
    )
    parser.add_argument("--codeql", default="codeql", help="CodeQL executable.")
    parser.add_argument("--infer", default="infer", help="Infer executable.")
    parser.add_argument(
        "--sokrates",
        default="sokrates",
        help='Sokrates command (e.g. "java -jar /path/sokrates.jar").',
    )
    parser.add_argument(
        "--correctness", action=argparse.BooleanOptionalAction, default=True,
        help="Estimate functional correctness with a local Ollama decision model (default: enabled).",
    )
    parser.add_argument("--ollama-model", default=DEFAULT_MODEL, help="Local decision model.")
    parser.add_argument("--ollama-host", default=DEFAULT_HOST, help="Ollama host:port.")
    args = parser.parse_args(argv)
    try:
        prompt_file = args.prompt_file
        if prompt_file is None and not args.skip_prompts:
            prompt_file = generator.write_prompts()
            print(f"Generated prompts in {prompt_file}", flush=True)
        successful = run(
            runner.default_clients(),
            runner.load_prompts(prompt_file),
            args.output_dir,
            generation_dir=args.generation_dir,
            codeql=args.codeql,
            infer=args.infer,
            sokrates=args.sokrates,
            correctness=args.correctness,
            ollama_model=args.ollama_model,
            ollama_host=args.ollama_host,
        )
    except (OSError, RuntimeError, ValueError) as error:
        print(f"Pipeline failed: {error}", file=sys.stderr)
        return 1
    return 0 if successful else 1


if __name__ == "__main__":
    raise SystemExit(main())
