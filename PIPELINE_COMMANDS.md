# Pipeline commands

Run from the repository root. Generation-based standalone steps default to the newest
`Generations/date:*` directory by name. To choose another, append its path
(use `--input-dir PATH` for compilation). For resuming, set
`GEN="Generations/<generation-folder>"`.
Generation uses the clients configured in `pipeline/runner/runner.py`;
compilation and analysis require `javac`, CodeQL, and Infer on `PATH`.
Set `runs=N` on a Copilot, LM Studio, OpenAI, or Anthropic client to repeat all prompts sequentially
(default `1`). Outputs use
`Generations/<date>/<client>/<model>/run-N/<category>/`.
Resuming reuses configured `run-1` through `run-N`; LM Studio's `skip_chats`
applies independently to each run. Existing Java files are never overwritten.
Full runs and combined analysis also judge correctness by default using local
Ollama and `kev:4b-mlx-bf16`; use `--no-correctness` to skip that stage.

Hosted APIs are opt-in: run `uv sync`, copy `.env.example` to repository-root
`.env`, and set `OPENAI_API_KEY` and/or `ANTHROPIC_API_KEY`. Environment variables
override `.env`. In `default_clients()`, import and add
`OpenAIClient(model="your-openai-model-id", runs=3)` from
`pipeline.clients.openai` or
`AnthropicClient(model="your-anthropic-model-id", runs=3)` from
`pipeline.clients.anthropic`. Both use the existing `Client` protocol and runner.
Their constructor defaults are `max_output_tokens=20_000`, `timeout=815.0`, and
`max_retries=2`; adjust the token limit for models that cannot support it. SDK
retries may incur additional API costs. The default CLI client list is unchanged.

| Task | Command |
| --- | --- |
| Full pipeline | `uv run python -m pipeline` |
| Reuse newest prompts | `uv run python -m pipeline --skip-prompts` |
| Resume a generation | `uv run python -m pipeline --generation-dir "$GEN" --skip-prompts` |
| Full pipeline without correctness judging | `uv run python -m pipeline --no-correctness` |
| Generate prompts only | `uv run python -m pipeline.prompt_generator.generator` |
| Generate Java only | `uv run python -m pipeline.runner.runner` |
| Preprocess Java | `uv run python -m pipeline.preprocessor.preprocessor` |
| Compile Java | `uv run python -m pipeline.verifier.java_verifier` |
| Run all analyses, including correctness | `uv run python -m pipeline.analysis.analyze --overwrite` |
| Run static analyses only | `uv run python -m pipeline.analysis.analyze --overwrite --no-correctness` |
| Build/rebuild CodeQL databases | `uv run python -m pipeline.analysis.codeql.database_builder --overwrite` |
| Query existing CodeQL databases | `uv run python -m pipeline.analysis.codeql.analyzer` |
| Run Infer only | `uv run python -m pipeline.analysis.infer.racerd` |
| Judge correctness only (requires local Ollama) | `uv run python -m pipeline.analysis.correctness` |
| Collect results, export DataFrames, and plot passing rates and error types | `uv run python -m pipeline.visualisation` |
| Collect evaluation-report paths as JSON only | `uv run python -m pipeline.visualisation.collection` |
| Extract CSV from an existing collection | `uv run python -m pipeline.visualisation.extraction "visualisation/<generation-folder>/collection.json"` |
| Plot an existing extracted CSV without rerunning analysis | `uv run python -m pipeline.visualisation.plotting "visualisation/<generation-folder>/errors.csv"` |

Visualisation also runs after analysis in the full pipeline. It collects
compilation, CodeQL, Infer, and correctness summary reports, nested as
client -> model -> category -> run-N -> report paths. Older layouts omit absent
category/run levels.
Each successful collection saves or refreshes
`visualisation/<generation-folder-name>/collection.json` at the repository root,
preserving the full generation-folder name, including its unique suffix.
Missing report types warn on stderr; partial results are allowed, but finding
no reports is an error. The combined command then extracts `errors.csv` beside
the index, with one row per client/model/category/problem/run and numeric
run ordering (all run 1, then run 2, then run 3). The root generation manifest
must map each result to a problem; missing or ambiguous mappings are errors.

Compilation failures count as 1, successful compilation as 0. CodeQL and Infer
issue counts stay separate and include warnings. CodeQL also has separate
`codeql_safe_publication`, `codeql_escaping`, and `codeql_not_threadsafe` columns,
plus columns for any additional rules encountered; `codeql_issues` remains the
total. Infer similarly has an `infer_<bug_type>` column for each encountered
error type, such as `infer_thread_safety_violation` or
`infer_interface_not_thread_safe`; `infer_issues` remains its total. Type columns
use lowercase, sanitised identifiers, preserve repeated findings, and sum to the
tool's issue count. Successfully analysed files have zeroes for absent types;
failed or missing analyses have blanks. File paths are used internally but are
not included in the CSV.
Correctness retains its
probability, without a binary error threshold. Missing or failed analyses use
blank measurements and explicit status columns instead of zeroes; failed
compilation retains its count of 1. Unmapped CodeQL findings warn and are excluded
from per-problem counts.

The combined command also uses Pandas, Matplotlib, and Seaborn to export
`outcomes.csv`, `passing_rates.csv`, and publication-style table and heatmap
figures (`passing_rates_table` and `passing_rates_heatmap`, each in PNG and PDF).
Each row in `outcomes.csv` retains the extracted measurements and adds `passed`.
A run passes only when compilation, CodeQL, and Infer all succeed with zero
errors/findings. Any finding (including warnings), failed tool, or missing
analysis prevents a pass. Correctness probability does not affect this flag.

Passing rate at k=1 uses only run 1; k=3 counts a problem as passing if **any**
of runs 1, 2, or 3 passes. It is an empirical first-k rate, not the combinatorial
pass@k estimator. Additional runs are ignored. Each client/model is kept separate.
The denominator is the union of category/problem identities in `errors.csv`,
shared across all models and both k values. Missing attempts cannot pass and
coverage gaps warn on stderr; `observed_attempts` and `expected_attempts` in
`passing_rates.csv` expose those gaps. Problems absent from the entire extracted
dataset cannot be inferred. Legacy blank run fields mean run 1.

Reusable helpers in `pipeline.visualisation.dataframes` load validated outcomes
(`load_results` / `outcomes_dataframe`), aggregate rates (`passing_rates`), and
format a table (`passing_rate_table`). Rates remain fractions in the summary
DataFrame/CSV; figures display percentages and passing/total counts, ranked by
k=1 rate. Filter the outcomes DataFrame before aggregation for category-specific
tables. No CodeBLEU scores are currently collected, so the figures do not include
CodeBLEU columns or substitute correctness probabilities for them.

The same command also exports `error_counts_run_1.csv` and
`error_counts_run_1_table.png` / `.pdf`: a run-1 error summary grouped into
Compilation, CodeQL rules, and Infer bug types, with one row per client/model
and a totals row in the figure. Compilation counts failed programs; the analysis
columns count **individual findings**, not affected programs. Only run 1 is
included, irrespective of later outcomes.

`error_counts(outcomes, run=1)` returns a reusable long-form DataFrame containing
counts, error types, and observed/expected analysis coverage.
`error_count_table(summary)` formats the grouped table and totals. No evaluated
attempts display as `N/A`; partially evaluated counts have `*`, including totals,
and coverage gaps warn on stderr. Compilation failures remain observed counts,
not missing data. For old CSVs without complete type breakdowns, unmatched
findings are explicitly labelled `Unclassified` and warn; rerun the combined
visualisation command to re-extract individual types from the existing reports.

To collect and extract an existing generation explicitly:

```text
uv run python -m pipeline.visualisation "$GEN"
```
