# master-thesis
Concurrency analysis of LLM-generated code. 

## Prompt generator

`pipeline.prompt_generator` uses `pipeline/base_prompt.py` and
`pipeline/datastructures.py` to generate one prompt for each data structure.
Run it from the repository root:

```text
uv run python -m pipeline.prompt_generator.generator
```

Each run writes a new file under `prompts/` named
`data:<timestamp>.json`. The file has a `header` containing the generation
`date`, `timestamp`, and prompt `count`, plus a `prompts` list of fully
expanded prompt strings ready to iterate over and execute.

Run the tests with:

```text
python -m unittest discover -s pipeline/prompt_generator/tests
```

## GitHub Copilot client

`pipeline.clients.copilot.call_copilot(prompt, modelname)` invokes the local
GitHub Copilot CLI and returns its response:

```python
from pipeline.clients.copilot import call_copilot

response = call_copilot("Explain this code", "gpt-5")
```

The client runs `copilot -p <prompt> --model <modelname>` without a shell and
does not pass tool-enabling options. The GitHub Copilot CLI must be installed
and authenticated in the environment. CLI failures are returned as readable
error strings.
