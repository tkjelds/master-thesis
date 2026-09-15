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

`pipeline.clients.copilot.CopilotClient` invokes the local GitHub Copilot CLI.
The model is selected when the client is created:

```python
from pipeline.clients.copilot import CopilotClient

client = CopilotClient(model="gpt-5.6-luna")
response = client.call("Explain this code")
```

The client runs `copilot -p <prompt> --model <modelname>` without a shell and
does not pass tool-enabling options. The GitHub Copilot CLI must be installed
and authenticated in the environment.

`CopilotClientNoTools` adds explicit non-interactive restrictions: it disables
available tools, built-in MCPs, custom instructions, user prompts, remote
features, auto-updates, and streaming so the response is closer to a plain
completion.

## Generation runner

The runner starts with one client: Copilot using the `gpt-5.6-luna` model. It
loads the newest generated prompt file from `prompts/` and writes successful
responses to:

```text
Generations/date:{timestamp}/copilot/gpt-5.6-luna/{datastructure}.java
```

Run it with:

```text
uv run python -m pipeline.runner.runner
```

To use a specific generated prompt file or output directory:

```text
uv run python -m pipeline.runner.runner \
  --prompt-file prompts/date:2026-09-15T16-55-56-410660+0200.json \
  --output-dir Generations
```

Individual failures are reported and do not prevent remaining prompts from
being processed.
