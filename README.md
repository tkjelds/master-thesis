# master-thesis
Concurrency analysis of LLM-generated code. 

## Complete Python pipeline

Run the full pipeline from the repository root:

```text
uv run python -m pipeline
```

The Python entry point in `pipeline/main.py` runs
**prompts -> generate -> preprocess -> verify -> analyze -> visualisation**. It generates prompts and Java
implementations, strips Markdown fences, adds `@ThreadSafe` annotations, compiles the sources, then
builds and analyzes CodeQL databases, runs Infer and Sokrates, and estimates
functional correctness. Every stage uses the exact
generation directory created or explicitly selected by this run, rather than finding the newest
directory afterward. A unique suffix on its `date:*` directory prevents runs
started in the same minute from sharing sources or reports. CodeQL databases
are built from the annotated sources before analysis. The visualisation step then
collects the available evaluation-report paths and exports per-problem
measurements to CSV.

To resume into a particular generation folder, configure each model's
`skip_chats` in `default_clients()` and reuse the original prompt file:

```text
uv run python -m pipeline \
  --prompt-file prompts/date:2026-10-14T22-33.json \
  --generation-dir "Generations/date:2026-10-15T21-45-uxymcb8a"
```

`--generation-dir` uses that exact folder (creating it if needed) instead of
making a timestamped child. It cannot be combined with `--output-dir`.
Generation never overwrites an existing Java file: a matching output filename
is reported as a failure, and remaining requests continue. Skipped prompts
leave existing files untouched. Preprocessing and evaluation then cover all
programs in the selected folder, including older outputs. Preprocessing may
update those sources, evaluation reports are refreshed, and CodeQL databases
are rebuilt to include the combined contents. Do not resume into a folder
another process is still generating or evaluating.

Use `--skip-prompts` to reuse the newest prompt file, or supply one explicitly:

```text
uv run python -m pipeline --prompt-file prompts/date:2026-09-15T16-55.json \
  --output-dir Generations
```

The configured LLM server, `javac`, `codeql`, `infer`, and Sokrates must be
available locally. Use `--codeql /path/to/codeql`, `--infer /path/to/infer`, and
`--sokrates "java -jar /path/to/sokrates.jar"` to override the tool commands.
Sokrates runs once per generated Java file in a temporary source tree containing
only that file, so complexity and unit-size measurements cannot be affected by
other generated programs. Its per-tier report is
`results/<model>.sokrates.result.json`; it contains source LOC, unit count and
size summaries, plus the sum, maximum, and mean of Sokrates' per-unit McCabe
indices. Generation failures are retained in the final exit status while
successfully generated files continue through evaluation. If
generation produces no files or preprocessing fails, evaluation stops. Otherwise,
verification and benchmark failures are reported without blocking the remaining
evaluation steps, and the pipeline exits nonzero if any stage failed.

## Functional correctness estimates (Ollama)

Correctness judging is **enabled by default** and uses the local Ollama System One decision
model `kev:4b-mlx-bf16`. Start an Ollama version with System One support and
that model installed. The evaluator uses Python's HTTP client against
[`POST /v1/systemone`](https://docs.ollama.com/api/systemone), not the chat API.
Its yes/no (`noul`) answer is the model's probability that the program satisfies
the functional specification, from 0 to 1. No explanations or reasoning are
requested or saved.

It runs after the static analyses in a full run:

```text
uv run python -m pipeline
```

To evaluate an existing generation without regenerating programs or rerunning
the compiler, CodeQL, or Infer:

```text
uv run python -m pipeline.analysis.correctness \
  "Generations/date:2026-10-19T16-41-fo5imwnt"
```

`pipeline.analysis.analyze` also includes correctness judging by default
alongside CodeQL and Infer. All three entry
points accept `--ollama-model` (default `kev:4b-mlx-bf16`) and `--ollama-host`
(default `localhost:11434`). Use a `host:port` address without a URL scheme or
path. The analysis-only client lives in `pipeline/analysis/correctness/system_1.py`, separate
from the generation-client protocol. It sends requests directly, without
configuration validation or cloud/remote-model restrictions.
Pass `--no-correctness` to the full pipeline or combined analysis command to
skip correctness judging and make no Ollama requests. The existing
`--correctness` flag remains available to explicitly enable it.

The evaluator requires the generation's **`manifest.json`**. Each Java file must
have exactly one valid mapping to its `prompt_file` and `prompt_name`. The exact
original prompt is selected by name from that corpus, not reconstructed from the
current problem registry or guessed from the Java class name. The linked problem
and category must agree with the manifest. Corpora must remain in the repository's
`prompts/` directory or the generation's `prompts/` directory; Java source paths
must remain inside the generation. Older manifests with `metadata_file` sidecar
links remain readable. Sources without manifest mappings remain unscored.

One independent request per program judges **functional behavior under
sequential use**: operations, return values, state transitions, boundary cases,
and specified validation/error behavior. The rubric explicitly excludes thread
safety, concurrent interleavings, contention atomicity, deadlock/starvation, and
synchronization/library-style restrictions, even when these appear in the saved
generation prompt. Existing tools continue to check concurrency; their findings
are not supplied to this judge. Programs are not executed or repaired.

Each source directory gets
`results/<generating-model>.correctness.results.json`, separately from the other
reports. Top-level fields identify the generating model, evaluator model and
local digest, timestamp, and `functional-v1` rubric. The `files` array records
repository-relative `file` (including the generation directory), manifest
`prompt_name` and `problem`,
`analyzed`, `probability`, and `error`. A successful assessment has a numeric
probability and an empty error. Failed assessments have `analyzed: false`,
`probability: null`, and an explicit error rather than a fabricated score.

Evaluation is sequential with no automatic retries. Requests have an 815-second
socket timeout. System One rejects inputs exceeding its local text/schema
limit of 64 KiB or the loaded model's token context; the evaluator never cuts
source code or specifications to fit. Missing/invalid mappings, metadata,
transport errors, unavailable model memory, and invalid probabilities are
reported while independently valid files continue. Any analysis failure causes
a nonzero exit status; a low valid probability does not. Rerunning refreshes
only this tool's reports, without changing sources, manifests, or other reports.

These are **uncalibrated model estimates**, not measured success rates,
execution results, or proof of correctness. In particular, a high score says
nothing about concurrency correctness.

## Visualisation: collection and CSV extraction

Visualisation collects compilation, CodeQL, Infer, Sokrates, and correctness report paths
from an existing generation folder, then extracts one CSV row per
client/model/category/problem/run:

```text
uv run python -m pipeline.visualisation "Generations/<generation-folder>"
```

Omit the path to use the newest `Generations/date:*` directory by name. Outputs
are saved at the repository root under
`visualisation/<generation-folder-name>/`: `collection.json` is the nested
report-path index and `errors.csv` contains the extracted measurements.
The full generation-folder name, including its unique suffix, is preserved.
Successful runs refresh these files. No analyzers or LLM calls are needed to
visualise previously evaluated results.

To collect paths only, without parsing any evaluation reports:

```text
uv run python -m pipeline.visualisation.collection "Generations/<generation-folder>"
```

This collection-only CLI also prints the index as JSON with absolute path
strings. Its Python API saves the index and returns `pathlib.Path` values:

```python
from pathlib import Path

from pipeline.visualisation.collection import run

reports = run(Path("Generations/<generation-folder>"))
# reports["client"]["model"]["datastructures"]["run-1"]["compilation"]
```

The dictionary and JSON nest results as **client -> model -> category -> run**:

```json
{
  "example-client": {
    "example-model": {
      "datastructures": {
        "run-1": {
          "compilation": "/absolute/generation/example-client/example-model/run-1/datastructures/results/example-model.results.json"
        }
      }
    }
  }
}
```

Runs are grouped inside each category, even though the generation folders put
runs before categories. Older layouts without run or category directories omit
those levels rather than inventing names. Innermost report keys are
`compilation`, `codeql`, `infer`, and `correctness`, including only existing
report files. Manifests, SARIF, databases, and raw Infer outputs are excluded
from the path index.
Missing report types produce warnings on stderr, including when correctness
judging was disabled; partial results remain usable. No reports anywhere is an
error, and invalid inputs or filesystem errors are reported rather than silently
ignored. An existing report is collected regardless of whether its evaluation
passed.

To extract a CSV from an existing index without recollecting:

```text
uv run python -m pipeline.visualisation.extraction \
  "visualisation/<generation-folder>/collection.json"
```

Extraction writes `errors.csv` beside the supplied collection file. The
generation's root `manifest.json` is required: problem names come from its
`problem` fields, not generated Java class names or guesses from filenames.
Missing, invalid, duplicate, or ambiguous mappings cause extraction to fail.
Local filenames in static-analysis reports and repository-relative correctness
paths are matched to the same generation-relative Java file.

CSV identity columns are `client`, `model`, `category`, `run`, and `problem`.
The generation is identified by the output directory, not a CSV column.
File paths are used internally for matching, but are not exported.
`run` is numeric, with all run-1 rows before run-2
rows, run-3, and so on (including correct ordering of run 10). Within each run,
rows sort by client, model, category, problem, and filename. Legacy rows without
a run have a blank `run` and appear first; absent categories are also blank.
Manifest programs lacking evaluations still receive rows.

| Measurement | Meaning |
| --- | --- |
| `compilation_errors` | 0 for successful compilation, 1 for failure; not a count of javac diagnostic lines |
| `codeql_issues` | The file's reported CodeQL `issue_count`, including warnings |
| `codeql_safe_publication` | Findings from CodeQL's `java/safe-publication` rule (unsafe publication) |
| `codeql_escaping` | Findings from CodeQL's `java/escaping` rule |
| `codeql_not_threadsafe` | Findings from CodeQL's `java/not-threadsafe` rule |
| `infer_issues` | The file's reported Infer `issue_count`, including warnings |
| `correctness_probability` | The successful evaluator's probability in [0, 1]; no correctness threshold or error count |

CodeQL's total is retained alongside the per-rule columns. Additional rule IDs
encountered in successful analyses get their own columns with a `codeql_`
prefix; the `java/` prefix is removed and punctuation becomes underscores.
The three configured rule columns are always present. Successful analyses have
zeroes for absent rule types; failed or missing analyses have blank rule counts.
Rule-column naming conflicts, malformed issue lists, or disagreement between
the total and the issue list cause extraction to fail rather than lose findings.

Each tool has a corresponding status column:
`compilation_status`, `codeql_status`, `infer_status`, or
`correctness_status`. Values are `success`, `failed`, or `missing`.
Failed analyses and absent reports/file results have blank measurements, not
zeroes; a failed compilation has count 1. Successful analyses with no findings
have count 0. This allows per-problem counts to be summed later without
confusing unavailable evaluations with error-free programs.

CodeQL's group-level `unmapped_issues` trigger a warning and are excluded from
per-problem counts rather than attributed arbitrarily. Summing CSV rows therefore
excludes those unassigned findings. Counts from different tools remain separate;
there is no deduplication or combined total.

Full pipeline runs also collect and extract after analysis, even if an evaluation
stage failed, while retaining the overall failure status. A recorded tool failure
does not prevent CSV extraction. Invalid JSON/schema, missing manifest mappings,
unreadable inputs, or output failures do fail extraction and the pipeline.
Extraction validates all rows before replacing an existing CSV, so failures
leave the previous CSV intact. Neither step changes source evaluation reports.
Plots and aggregate summaries are left for later steps.

## Pipeline commands

See [PIPELINE_COMMANDS.md](PIPELINE_COMMANDS.md) for a concise command table
covering the full pipeline and individual steps. Run commands from the repository
root. Standalone preprocessing, verification, analysis, and visualisation commands use the newest
`Generations/date:*` directory by name when no input directory is supplied;
an explicit input directory overrides that default.

## Prompt generator

Every prompt has the same three-part structure: a shared Java introduction,
a `Problem:` section, and `Requirements:` at the end. The introduction,
common requirements, and renderer live in `pipeline/prompt_generator/base_prompt.py`; problem-specific
descriptions and any explicitly allowed library imports are kept separate.

`pipeline.prompt_generator` generates three categories: one prompt per entry in
`pipeline/prompt_generator/datastructures.py` (`datastructures`), twenty concurrency-problem
prompts from `pipeline/prompt_generator/novel_problems.py` (`novel_problems`), and difficult data
structures from `pipeline/prompt_generator/advanced_datastructures.py` (`advanced_datastructures`).
All categories are generated automatically by the full pipeline's prompt step.
The `datastructures` category covers familiar concurrent collection types from
Java and other mainstream libraries, including skip-list maps/sets, priority
and delay queues, and bags. The `advanced_datastructures` category covers
specialized implementations and structural contracts: explicit binary search
trees, red-black trees, B-trees, and the C5-inspired structures. This distinction
is about library availability and specialization, not just algorithmic difficulty.
See Java's [concurrent collections](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/package-summary.html)
and .NET's [thread-safe collections](https://learn.microsoft.com/en-us/dotnet/standard/collections/thread-safe/).
Run it from the repository root:

```text
uv run python -m pipeline.prompt_generator.generator
```

To add problems, extend the existing lists in `pipeline/prompt_generator/datastructures.py`,
`pipeline/prompt_generator/novel_problems.py`, or
`pipeline/prompt_generator/advanced_datastructures.py`. For a new category,
add a list of `Problem(name, description, source=None)` entries to
`PROBLEM_CATEGORIES` in `pipeline/prompt_generator/problems.py`. Use a simple
directory name for the category key. The generator applies the shared framing
automatically; the runner and evaluation directory discovery use the same
registry, so no category-specific pipeline code is needed.

The first advanced problem is `advanced_datastructures/MysteriousHashMap`.
Its contract specifies per-stripe bucket arrays and `AtomicInteger` size fields,
immutable bucket lists and key-value pairs, reads that take no locks, locking for other
operations, and per-stripe resizing. The six method signatures are an informal
API contract, not a requirement to declare or implement an `IMap` interface.
All categories use the same shared no-import requirements.

Each run writes a new file under `prompts/` named
`date:<timestamp>.json`. The file has a `header` containing the generation
`date`, `timestamp`, and prompt `count`, plus a `prompts` list of fully
expanded records ready to iterate over and execute. Each record has a unique,
stable `name` formed as `<category>/<problem name>` (for example,
`datastructures/map`), a `datastructure` identifier (also used for novel-problem
names), a `category`, and a `prompt`. Names do not depend on prompt ordering or
the class name chosen by a model. Duplicate names are rejected before generation.
Novel records additionally retain their source file and
ClassEval record ID in `source`; generating them does not require loading
the original benchmark datasets.

New generations do not create per-program `.prompt.json` files. Instead,
`manifest.json` at the generation root maps each successful output's `prompt_name`,
`problem`, `category`, `client`, `model`, and numbered `run` to its `java_file` and original
`prompt_file`. The prompt text lives once in that shared corpus. `load_prompts`
retains the selected corpus path, even if a newer file appears later.
For callers supplying in-memory prompts, the runner saves one shared input corpus
under the generation's `prompts/` directory rather than duplicating it per program.
Legacy records without a `name` use `<category>/<datastructure>`, defaulting to
the `datastructures` category.

Each numbered run also gets `<client>/<model>/run-N/results/manifest.json`,
containing only that run's outputs across categories. All links are relative to
the directory containing that particular manifest. For example, a run manifest can contain:

```json
{
  "generations": [
    {
      "prompt_name": "datastructures/map",
      "problem": "map",
      "category": "datastructures",
      "client": "lm-studio",
      "model": "qwen2.5-coder-7b-instruct-mlx",
      "run": "run-1",
      "java_file": "../datastructures/ConcurrentMap.java",
      "prompt_file": "../../../../../../prompts/date:2026-10-19T16-41.json"
    }
  ]
}
```

Both manifests are updated after each successful generation, and resuming preserves
previous mappings. Failed and skipped requests do not add mappings. If saving an
index fails, previous manifests are restored and the new Java file is removed.
Existing Java files are never overwritten. Older manifests and sidecars remain
readable and are not automatically migrated or deleted.

The twenty selected problems are the top twenty in
[`concurrency_benchmark_candidates.md`](concurrency_benchmark_candidates.md):
HierarchicalScheduler, ThermostaticBuildOrchestrator, ResolvingVendingMachine,
HarmonicBufferedStream, UserSpaceProvisioner, VersionedSlotManager,
TaggedCapacityBuffer, AdaptiveTrustVault, SecureMetricAggregator,
TemporalForexCache, BitmaskedTimelineStage, InventoryTransactionBuffer,
ChatSessionStream, GomokuVersionState, ThermostaticCircuitStream,
VerifiableMailbox, PersistedScheduleContext, LexicalGraphProvider, Warehouse,
and RPGCharacter. Their Java adaptations specify APIs, atomicity and snapshot
semantics, failure behavior, and relevant cancellation/expiration rules.
They use the same no-import, intrinsic-monitor-only constraints as the existing
data-structure prompts. External work is represented by interfaces in the
requested source file rather than real filesystem or service dependencies.
These are concurrency adaptations, not verbatim translations of the source
benchmarks or claims of conceptual novelty.

Run the tests with:

```text
uv run python -m unittest discover -s pipeline/prompt_generator/tests
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
completion. It requests JSONL output and extracts only `assistant.message`
content, preserving source whitespace and long lines. CLI text output renders
Markdown and can insert line breaks inside Java string literals, so it must not
be used as the source transport. Reasoning, streaming deltas, and session
metadata are excluded; malformed, empty, and failed responses raise
`CopilotClientError` and are reported by the runner.

Both Copilot clients enforce a five-minute (300-second) timeout per CLI invocation.
Timeouts terminate the CLI process and retry the identical request up to five times:
six attempts total, or approximately 30 minutes per prompt. Retry progress is logged,
and partial output from timed-out attempts is discarded. After exhaustion, the runner
records a failure and continues to the next prompt. Other errors are not retried.
Each prompt has its own retry budget. Exhausted generation timeouts also save
the deliberately broken placeholder described below.

### Exhausted generation timeouts

All generation clients share `ClientTimeoutError` and `timeout_failure_source`
from `pipeline.clients.interface`. The `Client` protocol and `call(prompt) -> str`
contract stay unchanged: clients signal exhausted timeouts, and the runner uses
the prompt record's problem name to create and save the placeholder once.
For a problem named `Map`, it produces a source like:

```java
public class Map {
    Generation failed after timeout retry exhaustion (6 attempts).
}
```

The text is deliberately not quoted or commented, so compilation fails. The
public class name makes the existing filename function select `Map.java`.
The runner saves the usual manifest mapping, records the timeout as a generation
failure, and continues. This is a failed attempt, not a successful generation;
the pipeline retains a nonzero exit status and downstream evaluation sees the
broken source instead of a missing program. Existing files are never overwritten.
Problem names must be Java identifiers; invalid names cause an explicit error.

Retry settings are unchanged. Copilot (including the harness) and LM Studio use
their existing timeout retry budgets. OpenAI and Anthropic qualify when the SDK
exhausts its configured retries and ends with `APITimeoutError`; their reported
attempt count is the SDK retry budget, which may include earlier transient errors.
Non-timeout errors and incomplete responses do not produce placeholders.

### Tool-enabled harness

`pipeline.clients.harness.HarnessClient` runs the same Copilot generation task
with file read/write/edit, workspace listing/search, general shell, Java
compilation and self-written tests, and unrestricted web access enabled.
It implements the same `Client.call(prompt) -> str` protocol. Copilot CLI must
be installed and authenticated, and a working JDK (`javac` and `java`) must be
on `PATH`. This client requires macOS or Linux for process-group cleanup.

The default client list runs three downloaded LM Studio models first, each with
`runs=TOTAL_RUNS`: `qwen/qwen3.5-9b`, `google/gemma-4-12b`, and
`qwen2.5-coder-7b-instruct-mlx`. They use the existing single-completion
`LMStudioClient`, not the tool-enabled Copilot harness. Their selection and order
use public Hugging Face downloads for the corresponding upstream models as a
popularity proxy: [Qwen3.5 9B](https://huggingface.co/Qwen/Qwen3.5-9B),
[Gemma 4 12B](https://huggingface.co/google/gemma-4-12B-it), then
[Qwen2.5 Coder 7B Instruct](https://huggingface.co/Qwen/Qwen2.5-Coder-7B-Instruct).
Popularity is not a coding-quality ranking.

The local clients are followed by harness clients, also with `runs=TOTAL_RUNS`:
`gpt-6-luna`, `gpt-6.1-terra`, `gpt-5-mini`, `claude-sonnet-5.5`, and
`claude-haiku-5.5`. The no-tools Copilot client remains commented out. `gpt-5-mini`
provides a small, older-model baseline advertised by the CLI instead of GPT-4o mini.
The requested `gpt-6.1-terra` is not currently advertised by the installed CLI;
it remains configured explicitly and may fail if unavailable to your account.
To run only one harness model explicitly:

```python
from pipeline.clients.harness import HarnessClient
from pipeline.main import run
from pipeline.prompt_generator.generator import write_prompts
from pipeline.runner.runner import TOTAL_RUNS, load_prompts

successful = run(
    [HarnessClient(model="gpt-6-luna", runs=TOTAL_RUNS)],
    load_prompts(write_prompts()),
)
```

The selected model must be available to your Copilot account. The client reports
unavailable models rather than silently substituting one.

Each call starts in an empty temporary workspace, without copying repository
files or previous generations. The model may create scratch sources and tests,
but its final response must still contain only the requested Java source.
The harness extracts only the final completed top-level assistant message from
JSONL, excluding progress and tool output. The runner saves and evaluates it
normally. Scratch files are removed on success or failure; no additional traces
are saved by the pipeline (Copilot's own local session/log storage still applies).

The existing 300-second timeout and five timeout retries apply. Retries reuse
that call's workspace but start a new CLI session; separate calls never share
workspaces. The harness kills its CLI process group on exit or timeout.
Built-in MCPs, custom instructions, user questions, history sync, and auto-updates
are disabled, and only the selected native tools are exposed.

**This is best-effort separation, not a sandbox.** Native CLI path checks remain
enabled, including disabling automatic access to the wider temporary directory.
However, general shell commands and Java programs can bypass those checks and
read host files, including earlier solutions. Network access is unrestricted,
so external solutions are not technically blocked either. Do not treat this
setup as an enforced security or benchmark-contamination boundary.

## OpenAI and Anthropic API clients

`pipeline.clients.openai.OpenAIClient` and
`pipeline.clients.anthropic.AnthropicClient` implement the same `Client` protocol
as LM Studio: `call(prompt: str) -> str`. Install the official SDKs and dotenv
support with `uv sync`. Copy `.env.example` to `.env` at the repository root and
replace its placeholders with your provider keys:

```dotenv
OPENAI_API_KEY=replace-with-your-openai-api-key
ANTHROPIC_API_KEY=replace-with-your-anthropic-api-key
```

`.env` files are ignored by Git; never commit real keys. Existing environment
variables take precedence, including blank values (which fail explicitly).
Only the selected provider's key is required. Keys are resolved at call time,
without modifying the process environment or storing them on client objects.

```python
from pipeline.clients.anthropic import AnthropicClient
from pipeline.clients.interface import Client
from pipeline.clients.openai import OpenAIClient
from pipeline.runner.runner import load_prompts, run

clients: list[Client] = [
    OpenAIClient(model="your-openai-model-id", runs=3),
    AnthropicClient(model="your-anthropic-model-id", runs=3),
]
failures = run(clients, load_prompts())
for failure in failures:
    print(f"Failed {failure.client_name}/{failure.run_name}/{failure.datastructure}: {failure.error}")
```

Supply a model identifier available to your API account. Each client defaults to
`runs=1`, `max_output_tokens=20_000`, `timeout=815.0` seconds per request, and
`max_retries=2`. Override these as constructor parameters, for example
`OpenAIClient(model="your-model-id", max_output_tokens=8_000, max_retries=0)`.
The 20,000-token output limit is sent unchanged; choose a lower limit if your
model requires one. Model reasoning may consume part of that budget.

Calls are independent, text-only, and non-streaming, without tools or chat history.
OpenAI uses the Responses API with `store=False`; Anthropic uses the Messages API.
Only completed assistant text is returned, excluding reasoning. Refusals,
truncation, unexpected/empty responses, missing credentials, and API failures
raise `OpenAIClientError` or `AnthropicClientError`. The runner records the failure
and continues without saving partial source. Errors omit raw provider bodies and
credentials. SDKs handle bounded retries for transient errors; retries may incur
additional charges, and the timeout is not a total deadline across retries.

Repeated runs reuse the runner's existing directory and manifest layout:
`Generations/<date>/<openai-or-anthropic>/<encoded-model>/run-N/<category>/`.
Model identifiers are encoded for safe paths but sent unchanged to the provider
and preserved in manifests. Existing CLI defaults remain unchanged; explicitly
add these clients to `default_clients()` in `pipeline/runner/runner.py` to use
them with either generation CLI or the full pipeline. No new CLI flags are needed.

## LM Studio client

`pipeline.clients.lm_studio.LMStudioClient` invokes `curl` against LM Studio's
[native REST chat endpoint](https://lmstudio.ai/docs/developer/rest/chat),
`POST /api/v1/chat`. It requires LM Studio 0.4.0+ and curl 7.76+ on `PATH`,
without a Python model SDK dependency. Install project dependencies with
`uv sync`, download/select a model in LM Studio, and start its local server.
Use the model identifier shown by LM Studio, not a display name:

```python
from pipeline.clients.lm_studio import LMStudioClient
from pipeline.runner.runner import load_prompts, run

client = LMStudioClient(
    model="publisher/model",
    api_host="localhost:1234",
)
response = client.call("Explain this code")

failures = run([client], load_prompts())
for failure in failures:
    print(f"Failed {failure.client_name}/{failure.run_name}/{failure.datastructure}: {failure.error}")
```

The host shown above is the default; use a `host:port` address,
without a URL scheme or path. Use a local server that does not enforce API-token
authentication. Each attempt has an 815-second (13m 35s) curl transfer timeout
(`--max-time 815`), including connection, model loading, and generation. This
rounds up the estimated 80th percentile of 17 completed Gemma requests.
Only timeouts (curl exit code 28) are retried, with up to five retries by default:
six attempts total, or roughly 81m 30s per prompt. Set `timeout_retries=0` on a
client to disable retries, or an integer from 0 to 5 to change the budget.
Each retry resends the identical request and logs progress; partial timeout
responses are discarded. Exhausted retries raise `LMStudioClientError`, and the
runner continues with the next prompt. Other errors fail immediately.
Disconnecting does not guarantee that LM Studio cancels server-side generation,
so a retry may overlap an earlier attempt still running on the server.

Each call sends `model`, `input`, `stream: false`, and `store: false` as JSON on
curl's standard input, without a shell. Requests do not reuse chat history,
enable tools, or retry errors other than timeouts. Model loading and other
generation settings are left to the server.

Set `reasoning="off"` or `reasoning="on"` on a client to explicitly control
thinking for a model that supports it. The default `None` omits the API field
and uses the server's setting. Gemma 4 12B and Qwen3.5 9B advertise both options;
unsupported model/settings combinations are reported as API errors.
An explicit reasoning setting adds `@reasoning-off` or `@reasoning-on` to the
encoded model folder name, keeping those outputs separate from existing runs
that used the server default. Only the original model identifier is sent in
the API's `model` field.

Only `content` from `output` entries with `type: "message"` is returned. Multiple
messages are joined in response order with newlines and outer whitespace is
trimmed. Reasoning, tool calls, and metadata are excluded; there is no fallback
to reasoning when no message is present. Connection, timeout, HTTP, malformed
response, and empty-message errors raise `LMStudioClientError`; the runner reports
these immediately and continues.

Pass LM Studio alongside other clients to `run`, for example
`run([client, CopilotClientNoTools(model="gpt-5.6-luna")], load_prompts())`
after importing `CopilotClientNoTools` from `pipeline.clients.copilot`.
To configure the CLI to use LM Studio, include it in `default_clients()`.
Set `skip_chats` on an individual LM Studio client to skip its first N prompts:

```python
LMStudioClient(model="google/gemma-4-12b", skip_chats=14)
```

This starts that model at prompt 15 in the supplied prompt order, counting across
all categories. The default is zero; only nonnegative integers are accepted.
The runner logs skipped prompts without making API requests, writing files, or
counting them as failures. Other clients keep their own skip counts. The count
applies independently to every numbered run, and anew on every `run()` invocation,
even when reusing the same client.
It is a runner setting: direct `client.call()` requests are not skipped.
Skipping does not copy previous outputs. By default the full pipeline evaluates
only newly generated files; with `--generation-dir`, it evaluates all programs
already present or newly generated there. An empty generation folder fails
explicitly.

Outputs use `lm-studio/<encoded-model>/run-N/<category>/`, with unsafe characters
percent-encoded so model identifiers containing `/` remain a single folder:
`publisher/model` becomes `publisher%2Fmodel`. The original identifier is sent
unchanged to LM Studio; downstream analysis reports use the encoded folder name.

## Generation runner

The clients and models are configured in `default_clients()` in
`pipeline/runner/runner.py`. The active clients are Copilot without tools using
`gpt-6-luna`, then LM Studio using `qwen2.5-coder-7b-instruct-mlx`.
Set a positive integer `runs` on either client to generate the complete prompt
corpus repeatedly with the same model:

```python
CopilotClientNoTools(model="gpt-6-luna", runs=3)
LMStudioClient(model="qwen2.5-coder-7b-instruct-mlx", runs=3)
```

The default is `runs=1`. Generation is sequential: all prompts in `run-1`, then
all prompts in `run-2`, and so on, before proceeding to the next client.
This setting affects the runner, not a direct `client.call()` invocation.
When using `--generation-dir`, the configured `run-1` through `run-N` folders
are reused, not renumbered to the next unused folder. LM Studio's `skip_chats`
skips the same prefix independently in each run. Existing Java files are never
overwritten: non-skipped filename collisions are reported as failures while
later prompts and runs continue. Increasing `runs` creates the additional
numbered runs, but still attempts earlier runs unless their prompts are skipped.

Make the configured clients available and
authenticated (or start the local server when using LM Studio). The runner
loads the newest generated prompt file from `prompts/` and writes successful
responses to:

```text
Generations/date:{timestamp}/{client}/{model}/run-1/datastructures/{public-type}.java
Generations/date:{timestamp}/{client}/{model}/run-1/novel_problems/{public-type}.java
Generations/date:{timestamp}/{client}/{model}/run-1/advanced_datastructures/{public-type}.java
```

The same category structure is repeated under `run-2` through `run-N`.
The timestamp is captured once when the runner's `main()` starts and reused for
every client and prompt, so crossing a minute boundary does not create another
generation directory.

The runner names each file after its detected public top-level Java type, for
example `ConcurrentSet.java`. It falls back to `{datastructure}.java` when no
public type is present. Prompt records without a `category` are treated as
`datastructures`, so older prompt files still work. Unknown categories are
rejected before any client requests. Runs and categories remain separate even
if responses declare the same public class name.

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

The standalone runner also accepts `--generation-dir` to generate directly into
an existing folder without overwriting Java files. It does not preprocess or
evaluate; use `python -m pipeline` for the complete workflow.

The runner prints flushed progress messages before each request and the saved
file path after each completed response. Logs and failure records identify the
numbered run. Individual failures are reported immediately and do not prevent
remaining prompts or runs from being processed. LM Studio
calls are non-streaming: Java files appear only after a full response arrives,
not while the model is generating. Restart a running process to pick up changes.

## Source preprocessing

The Python pipeline runs `pipeline.preprocessor.preprocessor` after generation
and before the verifier. To prepare an existing generation separately:

```text
uv run python -m pipeline.preprocessor.preprocessor "Generations/date:2026-09-21T20-02"
```

The step updates Java files in place, first extracting a single complete Markdown
code block (` ```java ` or ` ``` `, with optional indentation and case-insensitive
`java`) and removing surrounding explanatory prose. Fences inside Java comments,
strings, and text blocks are preserved; conversational apostrophes do not hide
fences. Multiple blocks, incomplete fences, mixed fence languages, or Java content
or comments outside a block cause an explicit preprocessing error without rewriting
that file. The step does not choose among implementations or repair invalid Java.
Unfenced sources and blocks labelled only with other languages are left unchanged
by the fence step.
It then adds `@ClassName.ThreadSafe` and a nested
`@interface ThreadSafe {}` to each unannotated top-level class. This keeps each
file independently compilable without an annotation library and enables the
annotation-driven CodeQL and Infer checks. Nested helper classes are left alone;
existing `@ThreadSafe` annotations (including qualified names) are preserved.
Repeated runs do not add duplicates. Comments and string literals are ignored.
The annotation declares the thread-safety property to check; it does not make
the implementation thread-safe.

The ordered steps are coordinated by `preprocess_source()` in
`pipeline/preprocessor/preprocessor.py`: fence stripping lives in
`markdown_fences.py`, and annotation lives in `thread_safe.py`. Add future
source transformations to that coordinator. A file is written only after all
its steps succeed. The standalone `pipeline.preprocessor.thread_safe` command
remains available for annotation only.

Standalone verifier and analyzer commands do not add annotations automatically.
Run preparation before those commands, and rebuild existing CodeQL databases
after annotation changes. `uv run python -m pipeline` stops if preparation fails rather than
continuing analysis on incompletely prepared input.

## Java compilation verifier

`pipeline.verifier.java_verifier` checks generated Java files with `javac` and
writes one report per model/run/category directory. The verifier expects a mounted date
directory with this structure:

```text
date-root/
  client/
    model/
      run-1/
        datastructures/
          implementation.java
          results/
            model.results.json
        novel_problems/
          implementation.java
          results/
            model.results.json
        advanced_datastructures/
          implementation.java
          results/
            model.results.json
```

Each model's report is written to
`date-root/client/model/run-N/category/results/model.results.json`. Each report
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

## Analysis coordinator

Analysis tools live under `pipeline/analysis/`. The full pipeline delegates to
`pipeline.analysis.analyze`, which creates CodeQL databases, runs CodeQL queries,
and then runs Infer. Add future tools to the ordered steps in its `run()` function.
Each stage reports failures without blocking the remaining tools; the command
returns a nonzero exit status if any stage fails.

To analyze an existing, preprocessed generation without making model requests:

```text
uv run python -m pipeline.analysis.analyze \
  "Generations/date:2026-10-15T21-45-uxymcb8a" --overwrite
```

Use `--codeql /path/to/codeql` and `--infer /path/to/infer` to override executables.
`--overwrite` rebuilds existing CodeQL databases; omit it for first-time database
creation. The coordinator does not preprocess or run the standalone compilation
verifier. Report paths inside generation folders are unchanged.

## CodeQL databases

`pipeline.analysis.codeql.database_builder` creates one CodeQL Java database per model/run/category
directory in a generation, at
`date-root/client/model/run-N/category/results/model.codeql-db/`. It uses CodeQL's manual
build mode: CodeQL traces `pipeline/analysis/codeql/build.sh`, which compiles each Java
file separately with `javac`, so one failing file does not block the others.
Model directories without Java files are skipped.

```text
uv run python -m pipeline.analysis.codeql.database_builder \
  "Generations/date:2026-09-21T20-02" [--overwrite] [--codeql /path/to/codeql]
```

The `codeql` CLI and `javac` must be on `PATH`. On Apple Silicon, CodeQL's
build tracer needs Rosetta 2 (`softwareupdate --install-rosetta`). The
databases are ignored by Git.

## CodeQL concurrency analysis

`pipeline.analysis.codeql.analyzer` runs the bundled `ThreadSafe.ql`,
`SafePublication.ql`, and `Escaping.ql` alert queries against each model's
**existing** CodeQL database. It does not rebuild databases or compile Java.
These queries inspect classes annotated `@ThreadSafe`; they are not general
data-race checks for unannotated classes.
Create the databases with the preceding step before running:

```text
uv run python -m pipeline.analysis.codeql.analyzer \
  "Generations/date:2026-09-21T20-02" [--codeql /path/to/codeql]
```

The queries are a CodeQL pack depending on `codeql/java-all`. Use a CodeQL
bundle with the Java packs installed, or install the dependency once with
`codeql pack install pipeline/analysis/codeql/queries`. Use `--queries /path/to/query.ql`
(or an alert-query directory or `.qls` suite) to override the bundled alert suite.
`AnalyticsThreadSafeClasses.ql` reports aggregate metrics rather than
source-file alerts and is intentionally excluded from that suite.

Each model/run/category gets
`date-root/client/model/run-N/category/results/model.codeql.results.json`,
with a timestamp, model, tool version, database/query paths, and a `files` list.
Each Java file has `analyzed`, `exit_code`, `issue_count`, `issues`, and `stderr`,
matching the Infer report layout. Issues contain the rule ID/name, severity,
message, and source line/column range. Findings without a matching generated
source file are retained in `unmapped_issues`. The raw SARIF report is kept at
`results/model.codeql.sarif` and ignored by Git.

Analysis runs once per model/run/category database, not once per file. The per-file
`analyzed` flag reflects whether database analysis succeeded; it does not
recompile or check whether the current file was included in the database.
Findings refer to the database's source snapshot, so rebuild the database after
changing generated Java files. Missing databases and execution/report failures
produce failed JSON records and a nonzero exit status without blocking later
models. Findings alone do not fail the run; models without Java files are
skipped. The Python pipeline runs this step immediately after database creation.

## Infer RacerD and starvation analysis

`pipeline.analysis.infer.racerd` runs [Infer](https://fbinfer.com/)'s RacerD data-race
checker and starvation checker
(`infer run --no-default-checkers --racerd --starvation`) on every Java file
in a generation. The starvation checker detects supported concurrency-related
progress issues, including potential deadlocks; it does not prove freedom from
starvation. Each
file is analyzed separately, so one file that fails to compile does not block
the others. Model directories without Java files are skipped.

```text
uv run python -m pipeline.analysis.infer.racerd \
  "Generations/date:2026-09-21T20-02" [--infer /path/to/infer]
```

Each model/run/category directory gets a report at
`date-root/client/model/run-N/category/results/model.infer.results.json`. For each file, the
report lists whether Infer analyzed it, the exit code, the issues from both checkers
(bug type, severity, qualifier, procedure, line, …) and the stderr tail when
analysis failed. Infer's raw per-file output is kept in
`results/model.infer-out/<FileStem>/` and ignored by Git. The exit status is
nonzero only if a file could not be analyzed; race findings alone do not fail
the run. `infer` and `javac` must be on `PATH`. Infer cannot run inside paths
that contain `:` (such as `date:...`), so each file is analyzed as a copy in a
temporary directory and the output is then copied back.
For qualified `@ClassName.ThreadSafe` markers, the wrapper supplies the nested
binary annotation names through `--threadsafe-aliases`, so RacerD recognizes
the self-contained annotations added by the preparation step.

Annotation, verification, CodeQL, Infer, and correctness judging discover every
numbered run's category directories. Each run/category has its own `results/`
subtree while JSON reports retain the actual model name, not `run-N`.
Legacy generations with Java files directly under `date-root/client/model/`
or its category folders remain supported, with their original report paths.
Mixed layouts evaluate legacy sources alongside numbered runs without migrating
or deleting previous outputs.
