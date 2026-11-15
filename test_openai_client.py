from pathlib import Path

from pipeline.clients.openai import OpenAIClient
from pipeline.helpers import find_latest_file
from pipeline.runner.runner import load_prompts

ROOT = Path(__file__).resolve().parent
MODEL = "gpt-6-luna"
OUTPUT = ROOT / "openai_client_test_response.txt"


def main() -> None:
    prompt_file = find_latest_file(ROOT / "prompts", "date:*.json")
    prompts = load_prompts(prompt_file)
    prompt = next(
        (record for record in prompts if record.get("category") == "datastructures"),
        None,
    )
    if prompt is None:
        raise ValueError(f"No datastructure prompt found in {prompt_file}")

    response = OpenAIClient(model=MODEL, max_retries=0).call(prompt["prompt"])
    OUTPUT.write_text(response + "\n", encoding="utf-8")
    print(
        f"Saved {MODEL} response for {prompt.get('name', prompt['datastructure'])} "
        f"from {prompt_file.name} to {OUTPUT.name}"
    )


if __name__ == "__main__":
    main()
