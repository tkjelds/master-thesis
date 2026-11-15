from __future__ import annotations

import json
from pathlib import Path

from pipeline.helpers import timestamp_now
from pipeline.prompt_generator.base_prompt import make_prompt
from pipeline.prompt_generator.problems import PROBLEM_CATEGORIES


DEFAULT_OUTPUT_DIR = Path("prompts")


def build_prompts() -> list[dict[str, str]]:
    result = []
    names = set()
    for category, problems in PROBLEM_CATEGORIES.items():
        for problem in problems:
            name = f"{category}/{problem.name}"
            if name in names:
                raise ValueError(f"Duplicate prompt name: {name!r}")
            names.add(name)
            record = {
                "name": name,
                "datastructure": problem.name,
                "category": category,
                "prompt":make_prompt(problem.description),
            }
            if problem.source is not None:
                record["source"] = problem.source
            result.append(record)
    return result


def write_prompts(output_dir: Path = DEFAULT_OUTPUT_DIR) -> Path:
    prompts = build_prompts()
    timestamp = timestamp_now()
    document = {
        "header": {
            "date": timestamp.removeprefix("date:").split("T", 1)[0],
            "timestamp": timestamp,
            "count": len(prompts),
        },
        "prompts": prompts,
    }
    output = output_dir / f"{timestamp}.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(document, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    return output


def main() -> int:
    output = write_prompts()
    print(f"Generated {len(build_prompts())} prompt(s) in {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
