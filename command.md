# Python pipeline commands

Run these commands from the repository root. The project uses Python 3.14+
through `uv`.

## Environment

```bash
uv sync
```

## Generate prompts

Generate a timestamped prompt file under `prompts/`:

```bash
uv run python -m pipeline.prompt_generator.generator
```

## Generate Java programs

Use the newest prompt file and write generated programs under `Generations/`:

```bash
uv run python -m pipeline.runner.runner
```

Use a specific prompt file:

```bash
uv run python -m pipeline.runner.runner \
  --prompt-file prompts/date:2026-09-15T16-55.json
```

Use a different output directory:

```bash
uv run python -m pipeline.runner.runner \
  --output-dir Generations
```

The runner names files after detected public Java types, such as
`ConcurrentSet.java`.

## Run the Java verifier locally

The local environment must have `javac` available:

```bash
uv run python -m pipeline.verifier.java_verifier \
  --input-dir Generations/date:2026-09-15T17-28
```

Reports are written under the input directory's `results/` folder.

## Run the Java verifier with Docker

Build the image:

```bash
docker build -f Dockerfile.verifier -t master-thesis-java-verifier .
```

Run it against one generated date directory:

```bash
docker run --rm \
  --mount "type=bind,source=$PWD/Generations/date:2026-09-15T17-28,target=/data" \
  master-thesis-java-verifier
```

## Tests

Run the full test suite:

```bash
uv run pytest
```

Run the prompt-generator tests with their native unittest runner:

```bash
uv run python -m unittest discover -s pipeline/prompt_generator/tests
```

Run the runner and verifier tests only:

```bash
uv run pytest \
  pipeline/runner/tests/test_runner.py \
  pipeline/verifier/tests/test_java_verifier.py \
  pipeline/tests/test_helpers.py
```
