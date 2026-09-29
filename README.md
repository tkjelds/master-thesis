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

The runner names each file after its detected public top-level Java type, for
example `ConcurrentSet.java`. It falls back to `{datastructure}.java` when no
public type is present.

Run it with:

```text
uv run python -m pipeline.runner.runner
```

To use a specific generated prompt file or output directory:

```text
uv run python -m pipeline.runner.runner \
  --prompt-file prompts/date:2026-09-15T16-55.json \
  --output-dir Generations
```

Individual failures are reported and do not prevent remaining prompts from
being processed.

## Java compilation verifier

`pipeline.verifier.java_verifier` checks generated Java files with `javac` and
writes one report per model directory. The verifier expects a mounted date
directory with this structure:

```text
date-root/
  client/
    model/
      implementation.java
      results/
        model.results.json
```

Each model's report is written to
`date-root/client/model/results/model.results.json`. Each report
contains a timezone-aware `timestamp`, the `model`, and a `files` list with one
record per Java file. Records include `compiled`, `exit_code`, `stdout`, and
`stderr`.

Build the verifier image and run it against a generated date directory:

```text
docker build -f Dockerfile.verifier -t master-thesis-java-verifier .
docker run --rm \
  --mount "type=bind,source=$PWD/Generations/date:2026-09-15T17-28,target=/data" \
  master-thesis-java-verifier
```

The image uses Python 3.14 and JDK 21. A nonzero container exit status means at
least one Java file failed to compile; reports are still written for all model
directories. The generation runner names each Java file after its detected
public top-level type, so the files can be passed directly to `javac`.

## CodeQL databases

`pipeline.codeql.database_builder` creates one CodeQL Java database per model
directory in a generation, at
`date-root/client/model/results/model.codeql-db/`. It uses CodeQL's manual
build mode: CodeQL traces `pipeline/codeql/build.sh`, which compiles each Java
file separately with `javac`, so one failing file does not block the others.
Model directories without Java files are skipped.

```text
uv run python -m pipeline.codeql.database_builder \
  "Generations/date:2026-09-21T20-02" [--overwrite] [--codeql /path/to/codeql]
```

The `codeql` CLI and `javac` must be on `PATH`. On Apple Silicon, CodeQL's
build tracer needs Rosetta 2 (`softwareupdate --install-rosetta`). The
databases are ignored by Git.
