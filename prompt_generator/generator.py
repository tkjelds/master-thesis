"""Generate prompt combinations from a template and CSV wildcard values."""

from __future__ import annotations

import argparse
import csv
import itertools
import json
import re
from pathlib import Path
from typing import Iterable, Mapping


WILDCARD_PATTERN = re.compile(r"\$([A-Za-z_][A-Za-z0-9_]*)")


def find_wildcards(template: str) -> list[str]:
    """Return wildcard names in first-occurrence order."""
    return list(dict.fromkeys(WILDCARD_PATTERN.findall(template)))


def read_csv_values(path: Path, wildcard: str) -> list[str]:
    """Read values from a one-column CSV or a named CSV column."""
    with path.open("r", encoding="utf-8-sig", newline="") as file:
        rows = [[cell.strip() for cell in row] for row in csv.reader(file)]

    rows = [row for row in rows if any(row)]
    if not rows:
        raise ValueError(f"CSV file is empty: {path}")

    header = rows[0]
    matching_columns = [
        index for index, value in enumerate(header) if value.casefold() == wildcard.casefold()
    ]
    if len(header) > 1:
        if len(matching_columns) != 1:
            raise ValueError(f"CSV file {path} must have a column named {wildcard!r}")
        column = matching_columns[0]
        values = [row[column] for row in rows[1:] if len(row) > column]
    else:
        start = 1 if matching_columns else 0
        values = [row[0] for row in rows[start:]]

    values = [value for value in values if value]
    if not values:
        raise ValueError(f"CSV file has no values for {wildcard!r}: {path}")
    return values


def generate_prompts(
    template: str, values_by_wildcard: Mapping[str, Iterable[str]]
) -> list[dict[str, object]]:
    """Generate one prompt for every Cartesian-product combination."""
    wildcards = find_wildcards(template)
    supplied = set(values_by_wildcard)
    required = set(wildcards)
    missing = required - supplied
    extra = supplied - required
    if missing:
        raise ValueError(
            f"Missing CSV mapping(s) for wildcard(s): {', '.join(sorted(missing))}"
        )
    if extra:
        raise ValueError(
            f"CSV mapping(s) are not used by the template: {', '.join(sorted(extra))}"
        )

    if not wildcards:
        return [{"id": 1, "values": {}, "prompt": template}]

    value_lists = [list(values_by_wildcard[name]) for name in wildcards]
    if any(not values for values in value_lists):
        raise ValueError("Every wildcard must have at least one value")

    generated = []
    for prompt_id, combination in enumerate(itertools.product(*value_lists), start=1):
        substitutions = dict(zip(wildcards, combination))
        prompt = WILDCARD_PATTERN.sub(
            lambda match: str(substitutions[match.group(1)]), template
        )
        generated.append({"id": prompt_id, "values": substitutions, "prompt": prompt})
    return generated


def build_output(template: str, values_by_wildcard: Mapping[str, Iterable[str]]) -> dict:
    """Build the JSON-serializable output document."""
    prompts = generate_prompts(template, values_by_wildcard)
    return {
        "template": template,
        "wildcards": find_wildcards(template),
        "count": len(prompts),
        "prompts": prompts,
    }


def parse_mapping(argument: str) -> tuple[str, Path]:
    """Parse a CLI mapping in the form WILDCARD=path/to/file.csv."""
    name, separator, filename = argument.partition("=")
    if not separator or not name or not filename:
        raise argparse.ArgumentTypeError(
            "data mappings must use the form WILDCARD=path/to/file.csv"
        )
    return name, Path(filename)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Expand a prompt template using values from CSV files."
    )
    parser.add_argument("template", type=Path, help="Text file containing the prompt template")
    parser.add_argument("output", type=Path, help="Destination JSON file")
    parser.add_argument(
        "--data",
        action="append",
        default=[],
        type=parse_mapping,
        metavar="WILDCARD=CSV",
        help="CSV mapping; repeat once for each wildcard",
    )
    args = parser.parse_args(argv)

    template = args.template.read_text(encoding="utf-8")
    mappings: dict[str, Path] = {}
    for name, path in args.data:
        if name in mappings:
            parser.error(f"Duplicate CSV mapping for wildcard: {name}")
        mappings[name] = path

    values = {name: read_csv_values(path, name) for name, path in mappings.items()}
    document = build_output(template, values)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(document, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    print(f"Generated {document['count']} prompt(s) in {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
