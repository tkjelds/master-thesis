---
applyTo: "pipeline/**/*.py,src/**/*.py,pyproject.toml,uv.lock,pipeline/**/*.csv"
---

# Python pipeline instructions

## Tooling and commands

- Use `uv` for Python dependency and environment management. Keep dependencies in
  `pyproject.toml`; use `uv add <package>`, `uv sync`, and `uv run <command>`
  rather than invoking `pip` directly.
- Python requires version 3.14 or newer.
- Generate the prompt corpus from the repository root:
  `uv run python -m pipeline.prompt_generator.generator`.
- Run the full test suite with `uv run pytest`.
- Run one test by pytest node ID, for example:
  `uv run pytest pipeline/clients/tests/test_copilot.py::CopilotClientTests::test_passes_prompt_and_model_without_using_a_shell`.
- The prompt-generator tests can also run through their native `unittest`
  runner: `uv run python -m unittest discover -s pipeline/prompt_generator/tests`.

