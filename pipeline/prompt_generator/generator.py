"""Generate prompts from the project base prompt and data structures."""

from __future__ import annotations

import json
from pathlib import Path

from pipeline.base_prompt import base_prompt
from pipeline.datastructures import datastructures
from pipeline.helpers import timestamp_now


DEFAULT_OUTPUT_DIR = Path("prompts")



def build_prompts() -> list[dict]:
    result = []
    for datastructure in datastructures:
       result.append(
            {
                "datastructure": datastructure,
                "prompt": base_prompt.replace("$datastructure", datastructure)
            }
        ) 
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
    print(f"Generated {len(datastructures)} prompt(s) in {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
