# How Different LLM Benchmarks Construct Their Prompts

## Executive Summary

LLM benchmarks do not share one prompt standard. Knowledge benchmarks usually serialize an item as a question, labeled choices, and an answer prefix, while coding benchmarks expose a task description and evaluate executable completions rather than matching text. Instruction benchmarks commonly use single- or multi-turn conversations and an LLM judge, with explicit delimiters and randomized response order to reduce position bias. Frameworks such as EleutherAI's `lm-evaluation-harness` and Stanford HELM separate dataset representation from prompt construction, making few-shot sampling, chat formatting, truncation, and output normalization configurable.[^1][^2]

The most consequential prompt-design choices are: whether demonstrations are fixed or sampled; whether answer choices are rendered in the prompt or scored by conditional likelihood; whether the model receives plain text or structured chat messages; how completion boundaries are marked; and how free-form output is normalized before scoring. These choices can materially change measured performance, so benchmark results should always report the exact prompt protocol, model chat template, shot selection, decoding settings, and parser.

## 1. Prompt-construction patterns

| Pattern | Typical benchmarks | Prompt shape | Scoring boundary |
|---|---|---|---|
| Fixed few-shot multiple choice | MMLU, GPQA | Demonstrations followed by an unlabeled question and `Answer:` | Letter or choice index |
| Configurable task formatting | BIG-bench, HELM | Task-specific prefixes, examples, choices, and separators | Regex/string match, likelihood, or task metric |
| Chain-of-thought demonstrations | GSM8K, GPQA, BBH | Worked reasoning followed by a final-answer convention | Extract final number/letter/string |
| Judge-based comparison | MT-Bench, AlpacaEval, Arena-Hard | Candidate outputs embedded in a judge prompt | Rating, ranking, or pairwise label |
| Code completion | HumanEval, MBPP, APPS, LiveCodeBench | Problem statement/signature/tests and a completion marker | Tests, pass@k, or execution |
| Repository repair | SWE-bench | Issue plus repository context and patch instruction | Patch application and test outcomes |
| Multimodal MCQ/free response | MMBench, MMMU, LLaVA-Bench | Image content plus text question/options | Choice extraction or judge |
| Continuation safety evaluation | RealToxicityPrompts | Naturally occurring text only | Toxicity of generated continuation |

## 2. Knowledge and reasoning benchmarks

### MMLU

The original MMLU evaluator uses a subject-specific instruction, five development examples by default, four choices labeled `A` through `D`, and an answer prefix. Demonstrations include the gold letter; the test item omits it:

```text
The following are multiple choice questions (with answers) about geography.

What is the capital of France?
A. Berlin
B. Madrid
C. Paris
D. Rome
Answer: C

Which city is the capital of Italy?
A. Milan
B. Rome
C. Naples
D. Venice
Answer:
```

The original implementation selects the first `k` development rows, with `k=5`, and reduces `k` if the prompt exceeds the context window.[^3] It scores the log probabilities of the four answer letters rather than requiring a natural-language response.[^4] The current `lm-evaluation-harness` task preserves the same core structure with `fewshot_split: dev`, a `first_n` sampler, and `doc_to_text`/`doc_to_choice`/`doc_to_target` fields.[^5]

### GPQA

GPQA provides explicit prompt modes including zero-shot, five-shot, zero-shot chain-of-thought, and chain-of-thought.[^6] Its baseline zero-shot format is:

```text
What is the correct answer to this question: <question>

Choices:
(A) <choice 1>
(B) <choice 2>
(C) <choice 3>
(D) <choice 4>

Format your response as follows: "The correct answer is (insert answer here)"
```

The chain-of-thought variant inserts expert examples with explanations and asks for step-by-step reasoning before a final `The correct answer is (...)` line.[^7] The baseline shuffles the four choices with a seeded random generator and records the post-shuffle correct index, preventing fixed answer-position shortcuts.[^8] Parsers accept several parenthesized answer forms, while newer OpenAI evaluation code standardizes on a final `Answer: A/B/C/D` pattern.[^9]

### GSM8K

GSM8K is commonly evaluated with either zero-shot or eight-shot chain-of-thought. The harness uses a compact format:

```text
Q: <question>
A: <worked reasoning ending in “The answer is <number>.”>

Q: <test question>
A: Let's think step by step.
```

The zero-shot configuration uses `num_fewshot: 0` and appends `A: Let's think step by step.`; the few-shot configuration uses eight demonstrations.[^10] The dataset annotation convention ends with `#### <numeric answer>`, while the harness extracts either a strict `The answer is ...` number or a more flexible numeric span, then removes commas, dollar signs, preceding reasoning, and a final period before exact comparison.[^11]

### ARC-Challenge

ARC-Challenge inherits the ARC-Easy configuration in the harness. Its textual prompt is simply:

```text
Question: <question>
Answer:
```

Choices are supplied separately to the multiple-choice evaluator, and the target is the zero-based index corresponding to `answerKey`.[^12] The task configuration has no demonstrations, so the standard harness protocol is zero-shot. This is an important contrast with MMLU: the choices may affect likelihood scoring without being embedded in the rendered text template.

### BIG-bench and BIG-bench Hard

BIG-bench deliberately has no universal natural-language prompt. JSON task authors define examples, targets or `target_scores`, prefixes, separators, stop strings, output regexes, and metrics.[^13] Documented defaults include:

```text
task_prefix                  ""
example_input_prefix         "\nQ: "
example_output_prefix        "\nA: "
choice_prefix                "\n  choice: "
append_choices_to_input      true
few_shot_example_separator   "\n"
```

The conceptual two-shot generative form is:

```text
Q: 2+2=
A: 4

Q: 4+3=
A: 7

Q: 5+1=
A:
```

Few-shot examples are selected with a configurable seed, formatted with gold targets, joined by the task separator, and prepended to the held-out input.[^14] Multiple-choice tasks may use conditional log probabilities over candidate strings instead of asking for `A/B/C/D`; generated tasks can instead use `output_regex` and stop strings.[^15]

BBH is a curated release of 23 BIG-bench tasks with manually authored chain-of-thought files. Its canonical prompts generally contain three worked demonstrations and a final unanswered question. A multiple-choice example ends with a natural-language conclusion such as `So the answer is (B).`, while the dataset target is only `(B)`.[^16] Therefore, BBH implementations must separate reasoning text from the final answer and should not assume that native BIG-bench JSON parsing applies unchanged.

### TruthfulQA

TruthfulQA has distinct generation and multiple-choice protocols. Its default generation preset is few-shot and uses a `Q:`/`A:` primer; the `null` preset is zero-shot:

```text
Q: <question>

A:
```

The benchmark targets a short, truthful and informative answer rather than one canonical string.[^17] The generation evaluator compares the response against sets of true and false references, using similarity or a learned truth/informativeness judge; it does not require exact answer extraction.[^18]

Historical MC1/MC2 variants use answer likelihoods, while the newer binary format randomizes `Best Answer` and `Best Incorrect Answer` as `(A)` and `(B)`.[^19] This makes TruthfulQA a useful example of one dataset supporting multiple prompt and scoring contracts.

## 3. Instruction-following and judge benchmarks

### MT-Bench

MT-Bench questions contain one or two turns. Candidate answers are generated sequentially using a conversation template; the second turn sees the first assistant response.[^20] The default single-answer judge uses a system message telling the judge to be impartial and a user prompt containing `[Question]` and explicit start/end delimiters around the assistant answer. It requests a short explanation followed by a rating in the exact form `[[5]]`.[^21]

For pairwise judging, the prompt embeds Assistant A and Assistant B in separate delimited blocks and requests `[[A]]`, `[[B]]`, or `[[C]]`. FastChat evaluates both response orders and maps the result back to model identities, directly addressing position bias.[^22] Judge calls use deterministic temperature settings, and parsers search for the bracketed rating or verdict.[^23]

### AlpacaEval

AlpacaEval is primarily single-turn. Its original judge serializes the instruction and two model outputs as Python-style dictionaries, then asks the judge to return an ordered list of model names and ranks.[^24] AlpacaEval 2 uses a classifier-style pairwise prompt with two anonymous output identifiers, `m` and `M`; the weighted evaluator can compare their token log probabilities instead of sampling a long judgment.[^25]

The annotator randomizes output order using a deterministic seed and reverses the label when restoring the original order.[^26] AlpacaEval 2 reports a length-controlled win rate because raw LLM judges tend to favor longer answers.[^27] Thus the prompt protocol includes not only wording and serialization, but also anonymization, order randomization, and post-hoc length normalization.

### Arena-Hard

Arena-Hard uses a single user prompt and candidate answers generated as user/assistant messages. Its judge receives:

```text
<|User Prompt|>
{QUESTION}

<|The Start of Assistant A's Answer|>
{ANSWER_A}
<|The End of Assistant A's Answer|>

<|The Start of Assistant B's Answer|>
{ANSWER_B}
<|The End of Assistant B's Answer|>
```

For most categories, the judge is instructed to first generate its own answer, compare both candidates against it, assess helpfulness, relevance, concision, correctness, creativity, and missing information, and finally emit one of `[[A>>B]]`, `[[A>B]]`, `[[A=B]]`, `[[B>A]]`, or `[[B>>A]]`.[^28] The baseline and candidate are judged in both positions, and labels are converted to weighted preference scores.[^29]

## 4. Coding benchmarks

### HumanEval

HumanEval stores a complete task prompt containing the function signature, docstring, and starter code. The official usage passes that prompt directly to the model and requires a completion-only JSONL field; no benchmark-wide demonstration block is added.[^30] The evaluator concatenates the stored prompt, generated completion, tests, and a call to the entry point, then classifies execution as passed, timed out, or failed.[^31]

This means the prompt boundary is semantic rather than a textual answer marker: the model must continue valid code, and correctness is determined by execution.

### MBPP

MBPP uses self-contained Python tasks with a description, solution, and three tests. Its official few-shot template is explicitly delimited:

```text
You are an expert Python programmer, and here is your task: {prompt} Your code should pass these tests:

{tests}
[BEGIN]
{code}
[DONE]
```

The original setup reserves task IDs for demonstrations and uses three solved examples.[^32] `[BEGIN]` and `[DONE]` are prompt-level completion boundaries; the authoritative release emphasizes execution of the generated code against automated tests rather than textual matching.[^33]

### APPS

APPS uses a single plain-text causal-LM prompt:

```text
QUESTION:
{problem statement}
{starter code, if present}
Use Standard Input format
ANSWER:
```

Call-based tasks use `Use Call-Based format` instead. The standard protocol is zero-shot; an optional `peeking` mode appends a truncated fragment of a reference solution after `ANSWER:` and is an explicit experimental condition.[^34] The generator removes the prompt by splitting on `ANSWER:\n`, and the evaluator executes either a stdin/stdout program or a required function against serialized tests.[^35]

### LiveCodeBench and SWE-bench

LiveCodeBench uses a model-family-specific prompt dispatcher, explicit system/user variants, optional starter code, and a one-demonstration base-model variant.[^36] This illustrates why coding benchmark results require the exact model adapter: the same problem can be packaged differently for different model families.

SWE-bench is repository-level repair rather than isolated synthesis. Its preprocessing packages an issue with repository and code context and asks the model to produce a `git apply` patch. Evaluation extracts the patch, applies it to a repository at a fixed commit in Docker, and scores fail-to-pass and pass-to-pass tests.[^37] The core prompt-design problem is therefore context selection and patch-boundary control, not merely wording a function-generation instruction.

## 5. Safety and multimodal benchmarks

### BBQ

BBQ generates templated social-bias questions with ambiguous or disambiguating contexts, three answer options, and metadata. The options are shuffled, and the post-shuffle gold index is stored in the JSONL record.[^38] Official historical input formats place the context/question and `(a)`/`(b)`/`(c)` options in a fixed text layout; the canonical evaluation is zero-shot multiple choice.[^39]

### RealToxicityPrompts

RealToxicityPrompts does not add a benchmark instruction or demonstrations. The model receives only the naturally occurring `prompt.text` continuation prefix; metadata and toxicity scores are not appended.[^40] Generation length and model-specific EOS/control strings are configured separately, and toxicity is scored on the generated continuation through Perspective rather than answer parsing.[^41]

### MMBench, MMMU, and LLaVA-style evaluation

Multimodal benchmarks commonly separate image transport from textual prompt content. MMBench constructs image-grounded multiple-choice questions with explicit options and a request to select the correct answer; the image is packaged separately from the text.[^42] MMMU supports standardized four- or ten-option formats and direct or chain-of-thought variants, while LLaVA-Bench uses multimodal prompts followed by a judge for open-ended responses.[^43] Across these tasks, reproducibility depends on image-token ordering, whether the image is inserted before or after text, exact option labels, decoding temperature, and the answer-letter extraction rule.

## 6. Evaluation frameworks

### EleutherAI `lm-evaluation-harness`

The harness separates per-task document rendering from run-level model formatting. YAML/Jinja fields define dataset splits, `doc_to_text`, choices, targets, metrics, filters, and optional few-shot configuration.[^44] A run can additionally enable a tokenizer-specific chat template, a system instruction, and `fewshot_as_multiturn`, so the same task text can become materially different serialized prompts for different models.[^45]

Generated answers pass through configurable filters such as regex extraction and `take_first`; GSM8K demonstrates strict and flexible extraction pipelines in YAML.[^46] This architecture makes prompt construction declarative but also means that a benchmark name alone does not identify the full protocol.

### Stanford HELM

HELM represents raw data as structured `Scenario` instances and uses an `AdapterSpec` to define instructions, prefixes/suffixes, separators, few-shot count, sampling, decoding, and output mappings.[^47] A generation prompt is assembled from an instruction block, labeled training blocks, and an unlabeled evaluation block:

```text
[instructions]

[input prefix][training input]
[output prefix][gold output]

[input prefix][test input]
[output prefix]
```

HELM can sample demonstrations with class-coverage logic and reproducible trial seeds, remove training examples when the context is too long, and only then truncate the prompt.[^48] Its chat adapter instead sends structured role/content messages directly, while separate multiple-choice adapters support joint label generation, conditional likelihood, and calibration.[^49]

## 7. Design implications

1. **Prompt wording is part of the benchmark.** `Answer:`, `A:`, `[BEGIN]`, role markers, and judge delimiters define where the model should respond and what the parser will accept.
2. **Few-shot is not one variable.** Fixed first-N examples (MMLU), seeded random examples (BIG-bench and HELM), manually authored demonstrations (BBH), and no demonstrations (ARC-Challenge, HumanEval, RealToxicityPrompts) are different experimental conditions.
3. **Choice rendering changes the task.** Some benchmarks render choices and ask for a letter; others score candidate likelihoods without a generated letter; others randomize option order and compare indices.
4. **Chat templates add a second prompt layer.** A textual benchmark template can be wrapped in model-specific system/user/assistant serialization, changing tokenization and behavior.
5. **Output normalization is usually benchmark-specific.** Numeric cleanup, regex extraction, multilingual answer-prefix handling, code-fence removal, answer-letter mapping, and execution-based grading are not interchangeable.
6. **Judge benchmarks need bias controls.** Anonymous labels, swapped response order, deterministic settings, length control, and explicit anti-position-bias instructions are recurring safeguards.
7. **Coding benchmarks should report context packaging.** Isolated function prompts, tests in the prompt, starter code, repository snapshots, issue text, and patch instructions measure different capabilities.

## Confidence Assessment

High confidence: the core prompt structures, few-shot policies, choice handling, and scoring boundaries for MMLU, GSM8K, GPQA, BIG-bench, MT-Bench, AlpacaEval, HumanEval, MBPP, APPS, TruthfulQA, BBQ, RealToxicityPrompts, `lm-evaluation-harness`, and HELM are supported by primary repositories or their official implementations.[^3]-[^49]

Moderate confidence: LiveCodeBench, SWE-bench, and multimodal benchmark details are summarized from the official implementation findings, but their rapidly changing adapters and model-specific variants mean that exact prompts should be pinned to a benchmark release, commit, and model adapter before reproducing a score.[^36][^37][^42][^43]

Assumption: “Different LLM benchmarks” was interpreted broadly to include knowledge, reasoning, instruction-following, coding, safety, multimodal, and evaluation-harness protocols rather than one narrow benchmark family.

## Footnotes

[^1]: [EleutherAI/lm-evaluation-harness task YAML and factory](https://github.com/EleutherAI/lm-evaluation-harness/blob/main/lm_eval/tasks/_factory.py#L35-L85)
[^2]: [Stanford HELM `AdapterSpec`](https://github.com/stanford-crfm/helm/blob/main/src/helm/benchmark/adaptation/adapter_spec.py#L28-L53)
[^3]: [hendrycks/test `evaluate.py`](https://github.com/hendrycks/test/blob/master/evaluate.py#L12-L54)
[^4]: [hendrycks/test `evaluate.py` log-probability scoring](https://github.com/hendrycks/test/blob/master/evaluate.py#L60-L83)
[^5]: [lm-evaluation-harness MMLU default template](https://github.com/EleutherAI/lm-evaluation-harness/blob/main/lm_eval/tasks/mmlu/default/_default_template_yaml)
[^6]: [idavidrein/gpqa README](https://github.com/idavidrein/gpqa/blob/main/README.md#L15-L38)
[^7]: [GPQA baseline prompt construction](https://github.com/idavidrein/gpqa/blob/main/baselines/utils.py#L91-L158)
[^8]: [GPQA choice shuffling](https://github.com/idavidrein/gpqa/blob/main/baselines/utils.py#L180-L189)
[^9]: [GPQA answer parsing](https://github.com/idavidrein/gpqa/blob/main/baselines/closed_book.py#L74-L82); [OpenAI simple-evals GPQA parser](https://github.com/openai/simple-evals/blob/main/gpqa_eval.py#L42-L67)
[^10]: [GSM8K zero-shot and eight-shot configurations](https://github.com/EleutherAI/lm-evaluation-harness/blob/main/lm_eval/tasks/gsm8k/gsm8k-cot-zeroshot.yaml#L10-L39); [eight-shot CoT](https://github.com/EleutherAI/lm-evaluation-harness/blob/main/lm_eval/tasks/gsm8k/gsm8k-cot.yaml#L1-L13)
[^11]: [GSM8K extraction and normalization](https://github.com/EleutherAI/lm-evaluation-harness/blob/main/lm_eval/tasks/gsm8k/gsm8k-cot.yaml#L81-L98)
[^12]: [ARC task configuration](https://github.com/EleutherAI/lm-evaluation-harness/blob/main/lm_eval/tasks/arc/arc_easy.yaml#L1-L18)
[^13]: [BIG-bench JSON task documentation](https://github.com/google/BIG-bench/blob/main/docs/doc.md)
[^14]: [BIG-bench JSON task few-shot construction](https://github.com/google/BIG-bench/blob/main/bigbench/api/json_task.py)
[^15]: [BIG-bench model and metrics APIs](https://github.com/google/BIG-bench/blob/main/bigbench/api/model.py); [task metrics](https://github.com/google/BIG-bench/blob/main/bigbench/api/task_metrics.py)
[^16]: [BIG-bench Hard CoT prompt](https://github.com/suzgunmirac/BIG-Bench-Hard/blob/main/cot-prompts/logical_deduction_three_objects.txt)
[^17]: [TruthfulQA presets and prompt construction](https://github.com/sylinrl/TruthfulQA/blob/main/truthfulqa/presets.py#L1-L45); [formatting utility](https://github.com/sylinrl/TruthfulQA/blob/main/truthfulqa/utilities.py#L17-L57)
[^18]: [TruthfulQA metrics](https://github.com/sylinrl/TruthfulQA/blob/main/truthfulqa/metrics.py#L95-L220)
[^19]: [TruthfulQA README, multiple-choice protocols](https://github.com/sylinrl/TruthfulQA/blob/main/README.md#L39-L84)
[^20]: [FastChat answer generation](https://github.com/lm-sys/FastChat/blob/main/fastchat/llm_judge/gen_model_answer.py#L77-L135)
[^21]: [FastChat judge prompts](https://github.com/lm-sys/FastChat/blob/main/fastchat/llm_judge/data/judge_prompts.jsonl#L6-L8)
[^22]: [FastChat pairwise judge prompt and order handling](https://github.com/lm-sys/FastChat/blob/main/fastchat/llm_judge/data/judge_prompts.jsonl#L1-L2); [common judgment logic](https://github.com/lm-sys/FastChat/blob/main/fastchat/llm_judge/common.py#L183-L220)
[^23]: [FastChat judge settings and parsing](https://github.com/lm-sys/FastChat/blob/main/fastchat/llm_judge/common.py#L128-L153)
[^24]: [AlpacaEval original evaluator template](https://github.com/tatsu-lab/alpaca_eval/blob/main/src/alpaca_eval/evaluators_configs/alpaca_eval_gpt4/alpaca_eval.txt#L1-L31)
[^25]: [AlpacaEval 2 classifier configuration](https://github.com/tatsu-lab/alpaca_eval/blob/main/src/alpaca_eval/evaluators_configs/weighted_alpaca_eval_gpt4_turbo/configs.yaml#L1-L18)
[^26]: [AlpacaEval pairwise randomization](https://github.com/tatsu-lab/alpaca_eval/blob/main/src/alpaca_eval/annotators/pairwise_evaluator.py#L260-L325)
[^27]: [AlpacaEval 2 paper](https://arxiv.org/abs/2404.04475)
[^28]: [Arena-Hard judge prompt and verdict labels](https://github.com/lmarena/arena-hard-auto/blob/main/utils/judge_utils.py#L1-L29); [user template](https://github.com/lmarena/arena-hard-auto/blob/main/config/arena-hard-v0.1.yaml#L10-L18)
[^29]: [Arena-Hard order normalization](https://github.com/lmarena/arena-hard-auto/blob/main/gen_judgment.py#L58-L91)
[^30]: [OpenAI HumanEval README](https://github.com/openai/human-eval/blob/master/README.md#L23-L42)
[^31]: [HumanEval execution](https://github.com/openai/human-eval/blob/master/human_eval/execution.py#L24-L79)
[^32]: [MBPP README and three-shot template](https://github.com/google-research/google-research/blob/master/mbpp/README.md#L1-L22)
[^33]: [MBPP task/test description](https://github.com/google-research/google-research/blob/master/mbpp/README.md#L1-L7)
[^34]: [APPS prompt generation and peeking](https://github.com/hendrycks/apps/blob/main/eval/generate_gpt_codes.py#L34-L79)
[^35]: [APPS completion extraction and testing](https://github.com/hendrycks/apps/blob/main/eval/generate_gpt_codes.py#L126-L144); [APPS testing](https://github.com/hendrycks/apps/blob/main/eval/testing_util.py#L18-L125)
[^36]: [LiveCodeBench findings and repository](https://github.com/LiveCodeBench/LiveCodeBench)
[^37]: [SWE-bench findings and repository](https://github.com/swe-bench/SWE-bench)
[^38]: [BBQ option construction and shuffling](https://github.com/nyu-mll/BBQ/blob/main/utils.py#L107-L203)
[^39]: [BBQ README input formats](https://github.com/nyu-mll/BBQ/blob/main/README.md#L74-L83)
[^40]: [RealToxicityPrompts generation input](https://github.com/allenai/real-toxicity-prompts/blob/master/scripts/run_prompts_experiment.py#L49-L67)
[^41]: [RealToxicityPrompts generation controls](https://github.com/allenai/real-toxicity-prompts/blob/master/scripts/run_prompts_experiment.py#L25-L104)
[^42]: [MMBench repository](https://github.com/open-compass/MMBench)
[^43]: [MMMU and LLaVA benchmark findings](https://github.com/MMMU-Benchmark/MMMU); [LLaVA repository](https://github.com/haotian-liu/LLaVA)
[^44]: [lm-evaluation-harness ARC YAML example](https://github.com/EleutherAI/lm-evaluation-harness/blob/main/lm_eval/tasks/arc/arc_easy.yaml#L1-L17)
[^45]: [lm-evaluation-harness interface options](https://github.com/EleutherAI/lm-evaluation-harness/blob/main/docs/interface.md#L45-L100)
[^46]: [lm-evaluation-harness GSM8K filters](https://github.com/EleutherAI/lm-evaluation-harness/blob/main/lm_eval/tasks/gsm8k/gsm8k.yaml#L18-L31)
[^47]: [HELM `AdapterSpec` and scenario model](https://github.com/stanford-crfm/helm/blob/main/src/helm/benchmark/adaptation/adapter_spec.py#L1-L153); [scenario schema](https://github.com/stanford-crfm/helm/blob/main/src/helm/benchmark/scenarios/scenario.py#L52-L160)
[^48]: [HELM few-shot sampling and context fitting](https://github.com/stanford-crfm/helm/blob/main/src/helm/benchmark/adaptation/adapters/in_context_learning_adapter.py#L95-L285)
[^49]: [HELM chat and multiple-choice adapters](https://github.com/stanford-crfm/helm/blob/main/src/helm/benchmark/adaptation/adapters/chat_adapter.py#L7-L59); [multiple-choice adapters](https://github.com/stanford-crfm/helm/blob/main/src/helm/benchmark/adaptation/adapters/multiple_choice_joint_adapter.py#L8-L87)
