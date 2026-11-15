from __future__ import annotations

import argparse
import sys
from pathlib import Path

from pipeline.analysis.codeql import analyzer, database_builder
from pipeline.analysis.correctness import analyzer as correctness_analyzer
from pipeline.analysis.correctness.system_1 import DEFAULT_HOST, DEFAULT_MODEL
from pipeline.analysis.infer import racerd
from pipeline.analysis.sokrates import analyzer as sokrates_analyzer
from pipeline.helpers import parse_generation_args


def run(
    input_dir: Path,
    *,
    codeql: str = "codeql",
    infer: str = "infer",
    sokrates: str = "sokrates",
    overwrite: bool = False,
    correctness: bool = True,
    ollama_model: str = DEFAULT_MODEL,
    ollama_host: str = DEFAULT_HOST,
) -> bool:
    successful = True
    steps = [
        (
            "CodeQL database",
            lambda: database_builder.run(input_dir, codeql, overwrite=overwrite),
        ),
        ("CodeQL analysis", lambda: analyzer.run(input_dir, codeql)),
        ("Infer", lambda: racerd.run(input_dir, infer)),
        ("Sokrates", lambda: sokrates_analyzer.run(input_dir, sokrates)),
    ]
    if correctness:
        steps.append((
            "Functional correctness",
            lambda: correctness_analyzer.run(
                input_dir, model=ollama_model, api_host=ollama_host,
            ),
        ))
    for label, action in steps:
        print(f"==> Analyze: {label}", flush=True)
        try:
            passed = action()
        except (OSError, RuntimeError, ValueError) as error:
            print(f"{label} failed: {error}", file=sys.stderr)
            passed = False
        if not passed:
            print(f"!! Analysis step failed: {label}", file=sys.stderr)
            successful = False
    return successful


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Build CodeQL databases and run all configured Java analysis tools."
    )
    parser.add_argument(
        "input_dir", type=Path, nargs="?",
        help="Generation date directory (default: newest Generations/date:* directory).",
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
    parser.add_argument(
        "--overwrite", action="store_true", help="Replace existing CodeQL databases."
    )
    args = parse_generation_args(parser, argv)
    return 0 if run(
        args.input_dir.resolve(),
        codeql=args.codeql,
        infer=args.infer,
        sokrates=args.sokrates,
        overwrite=args.overwrite,
        correctness=args.correctness,
        ollama_model=args.ollama_model,
        ollama_host=args.ollama_host,
    ) else 1


if __name__ == "__main__":
    raise SystemExit(main())
