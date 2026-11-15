from __future__ import annotations

import argparse
import re
from pathlib import Path

from pipeline.helpers import discover_model_directories, parse_generation_args
from pipeline.preprocessor.java_tokens import JAVA_TOKENS

THREAD_SAFE = re.compile(r"@\s*(?:[\w$]+\s*\.\s*)*ThreadSafe(?![\w$])")


def _java_tokens(source: str) -> list[re.Match[str]]:
    return [
        token for token in JAVA_TOKENS.finditer(source)
        if token.lastgroup != "ignored"
    ]


def thread_safe_aliases(source: str) -> list[str]:
    code = " ".join(token.group() for token in _java_tokens(source))
    aliases = set()
    for annotation in THREAD_SAFE.finditer(code):
        parts = re.split(r"\s*\.\s*", annotation.group()[1:].strip())
        if len(parts) > 1:
            aliases.add(f"{parts[-2]}$ThreadSafe")
    return sorted(aliases)


def annotate_source(source: str) -> str:
    tokens = _java_tokens(source)
    edits: list[tuple[int, str]] = []
    braces = parentheses = 0
    declaration_start = 0
    index = 0
    while index < len(tokens):
        token = tokens[index]
        text = token.group()
        if text == "class" and braces == 0 and parentheses == 0:
            if index + 1 == len(tokens):
                raise ValueError("Incomplete class declaration")
            name = tokens[index + 1].group()
            body = index + 2
            header_parentheses = 0
            while body < len(tokens):
                value = tokens[body].group()
                if value == "{" and header_parentheses == 0:
                    break
                if value == "(":
                    header_parentheses += 1
                elif value == ")":
                    header_parentheses -= 1
                body += 1
            if body == len(tokens):
                raise ValueError(f"Class {name} has no body")
            header = " ".join(
                item.group() for item in tokens[declaration_start:index]
            )
            if not THREAD_SAFE.search(header):
                if name == "ThreadSafe":
                    raise ValueError("Cannot add a nested ThreadSafe type to ThreadSafe")
                depth = 1
                end = body + 1
                existing_annotation = False
                while end < len(tokens) and depth:
                    value = tokens[end].group()
                    if (
                        depth == 1
                        and value in {"class", "interface", "enum", "record"}
                        and end + 1 < len(tokens)
                        and tokens[end + 1].group() == "ThreadSafe"
                    ):
                        if value == "interface" and tokens[end - 1].group() == "@":
                            existing_annotation = True
                        else:
                            raise ValueError(
                                f"Class {name} already declares a ThreadSafe type"
                            )
                    if value == "{":
                        depth += 1
                    elif value == "}":
                        depth -= 1
                    end += 1
                if depth:
                    raise ValueError(f"Class {name} has an unclosed body")
                edits.append(
                    (tokens[declaration_start].start(), f"@{name}.ThreadSafe\n")
                )
                if not existing_annotation:
                    edits.append(
                        (tokens[body].end(), "\n    @interface ThreadSafe {}\n")
                    )
            index = body
            text = "{"
        if text == "(":
            parentheses += 1
        elif text == ")":
            parentheses -= 1
        elif text == "{":
            braces += 1
        elif text == "}":
            braces -= 1
            if braces == 0 and parentheses == 0:
                declaration_start = index + 1
        elif text == ";" and braces == 0 and parentheses == 0:
            declaration_start = index + 1
        index += 1
    for position, insertion in sorted(edits, reverse=True):
        source = source[:position] + insertion + source[position:]
    return source


def run(input_dir: Path) -> int:
    changed = 0
    for model_dir in discover_model_directories(input_dir):
        for path in sorted(model_dir.glob("*.java")):
            source = path.read_text(encoding="utf-8")
            try:
                annotated = annotate_source(source)
            except (IndexError, ValueError) as error:
                raise ValueError(f"Unable to annotate {path}: {error}") from error
            if annotated != source:
                path.write_text(annotated, encoding="utf-8")
                changed += 1
                print(f"Annotated {path}", flush=True)
    return changed


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Add self-contained @ThreadSafe annotations to generated top-level classes."
    )
    parser.add_argument(
        "input_dir", type=Path, nargs="?",
        help="Generation date directory (default: newest Generations/date:* directory).",
    )
    args = parse_generation_args(parser, argv)
    changed = run(args.input_dir)
    print(f"Added @ThreadSafe annotations to {changed} file(s).", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
