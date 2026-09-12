"""Generate prompts from the project base prompt and data structures."""

from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path

from pipeline.base_prompt import base_prompt
from pipeline.datastructures import datastructures


DEFAULT_OUTPUT_DIR = Path("prompts")


def build_prompts() -> list[str]:
    return [
        base_prompt.replace("$datastructure", datastructure)
        for datastructure in datastructures
    ]


def write_prompts(output_dir: Path = DEFAULT_OUTPUT_DIR) -> Path:
    generated_at = datetime.now().astimezone()
    prompts = build_prompts()
    document = {
        "header": {
            "date": generated_at.date().isoformat(),
            "timestamp": generated_at.isoformat(),
            "count": len(prompts),
        },
        "prompts": prompts,
    }
    timestamp = generated_at.strftime("%Y-%m-%dT%H-%M-%S-%f%z")
    output = output_dir / f"date:{timestamp}.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(document, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    return output


def main() -> int:
    output = write_prompts()
    print(f"Generated {len(datastructures)} prompt(s) in {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
