# An Overview of LLM Research—and the Theory Behind It

## Executive Summary

Modern large language models (LLMs) are best understood as probabilistic sequence models that learn distributed representations from large corpora and use attention-based computation to predict and generate tokens. Their practical abilities arise from several interacting layers: pretraining, scale, data composition, architecture, in-context computation, post-training, retrieval, tools, and evaluation. No single theory currently explains all frontier-model behavior.

The most established theory is mathematical and architectural: token sequences are embedded into vectors; Transformer layers repeatedly mix information through self-attention and nonlinear transformations; autoregressive pretraining minimizes next-token cross-entropy; and model loss often follows approximate scaling laws with parameters, data, and compute.[^1][^2][^3] More ambitious abilities—few-shot adaptation, reasoning, planning, truthfulness, and generalization—are less settled. Research supports several overlapping explanations for in-context learning, including retrieval and pattern copying, implicit Bayesian inference, gradient-descent-like computation, and selection among pretrained priors.[^4][^5]

Post-training changes which behaviors are preferred rather than creating intelligence from scratch. Instruction tuning teaches models to interpret natural-language task descriptions; RLHF and preference optimization push them toward human-rated outputs; and constitutional or AI-feedback methods replace some direct human labeling with explicit principles or model-generated judgments.[^6][^7] These methods optimize proxies, so they can improve helpfulness while leaving truthfulness, robustness, or goal specification imperfect.

Retrieval-augmented generation (RAG), tools, external memory, and agents change the theoretical object from a self-contained language model into a hybrid interactive system. Knowledge can reside in parameters, retrieved documents, databases, tools, or persistent state. Reliability consequently becomes a pipeline property: retrieval, evidence quality, interpretation, generation, tool execution, and error recovery all matter.[^8]

## Scope and Confidence

This is a conceptual overview of LLM research, with emphasis on theory and mechanisms rather than a chronological bibliography. It prioritizes peer-reviewed journal and conference papers, while labeling technical reports and preprints where relevant. Seminal papers from before 2020 are included because current LLM theory builds directly on them.

“Theory” is used in three senses:

1. **Formal mechanism:** equations describing objectives, attention, optimization, or inference.
2. **Empirically supported explanatory model:** a mechanism that predicts behavior in controlled experiments.
3. **Interpretive hypothesis:** a useful but incomplete account of broad frontier-model behavior.

Confidence is highest for the first category, moderate for narrow empirical mechanisms, and lower for global claims about reasoning, consciousness, general intelligence, or the internal meaning of representations.

## 1. The Core Mathematical Object: A Conditional Probability Model

At its simplest, a language model estimates the probability of a token sequence:

\[
p_\theta(x_{1:T})=\prod_{t=1}^{T}p_\theta(x_t\mid x_{<t}).
\]

The model receives a context \(x_{<t}\) and produces a probability distribution over the next token. Generation samples or selects tokens sequentially from this distribution. The standard autoregressive training objective is negative log-likelihood, or cross-entropy:

\[
\mathcal{L}_{\mathrm{CLM}}(\theta)
=
-\sum_{t=1}^{T}\log p_\theta(x_t\mid x_{<t}).
\]

This objective does not directly say “learn facts,” “reason,” or “be truthful.” It rewards assigning high probability to the continuation found in the training data. Consequently, a model can produce fluent and statistically likely text that is false, biased, or inappropriate. TruthfulQA showed that models can reproduce common human falsehoods, demonstrating that language-model likelihood is not equivalent to truth.[^9]

The model’s output is therefore best viewed as a conditional distribution, not as a database lookup or a symbolic proof. Temperature, sampling, prompt wording, decoding constraints, and context all affect which continuation is produced.

## 2. Distributed Representations: From Words to Geometry

Early neural language modeling replaced sparse symbolic word identities with continuous vectors. Bengio et al. proposed mapping words to learned vectors and using a neural network to estimate:

\[
\hat p(w_t\mid w_{t-n+1:t-1})
=
\operatorname{softmax}
\left(
f(C(w_{t-n+1}),\ldots,C(w_{t-1});\theta)
\right).
\]

The theoretical idea is **distributed representation**: related words and contexts share statistical structure through nearby or compositional vectors rather than requiring separate parameters for every n-gram.[^10]

Word2vec and GloVe demonstrated that semantic and syntactic regularities can emerge geometrically from distributional context. Skip-gram learns to predict surrounding words, while GloVe fits word vectors to global co-occurrence statistics.[^11][^12] These models established that useful linguistic structure can be learned without explicit symbolic definitions.

The limitation is that a vector is not automatically an interpretable concept. Static embeddings conflate senses such as “bank” as a financial institution and “bank” as a river edge. They also encode corpus biases and do not guarantee compositional reasoning. Modern LLMs replace static word vectors with context-dependent hidden states, but the underlying idea remains: meaning is represented through patterns in a learned continuous space.

## 3. Why Transformers Matter

### 3.1 Self-attention

The Transformer replaced recurrent state updates with attention-based information exchange. Given queries \(Q\), keys \(K\), and values \(V\):

\[
\operatorname{Attention}(Q,K,V)
=
\operatorname{softmax}
\left(
\frac{QK^\top}{\sqrt{d_k}}
\right)V.
\]

Each query compares itself with all keys, producing weights that determine how values are mixed. Multi-head attention repeats this process in multiple learned subspaces:

\[
\operatorname{MultiHead}(Q,K,V)
=
\operatorname{Concat}(\mathrm{head}_1,\ldots,\mathrm{head}_h)W^O.
\]

The \(\sqrt{d_k}\) normalization prevents large dot products from making the softmax excessively saturated.[^13]

Self-attention gives every token a short computational path to every other token in the context. It is also highly parallelizable during training, unlike recurrent models that must process sequence positions serially. These properties enabled the large-scale training regimes that define modern LLMs.

### 3.2 Position is not automatic

Self-attention alone is permutation-equivariant: if tokens are reordered, the outputs reorder correspondingly. A model therefore needs positional information. The original Transformer added sinusoidal position vectors:

\[
PE_{(pos,2i)}
=
\sin\left(\frac{pos}{10000^{2i/d_{\mathrm{model}}}}\right),
\qquad
PE_{(pos,2i+1)}
=
\cos\left(\frac{pos}{10000^{2i/d_{\mathrm{model}}}}\right).
\]

Modern systems use several alternatives, but the theoretical issue remains: the model must represent order, distance, and sometimes relative position in a way that generalizes to the context lengths encountered at inference.

### 3.3 A Transformer layer as repeated computation

A simplified decoder-only Transformer layer can be described as:

\[
H^{(\ell+1)}
=
H^{(\ell)}
+
\operatorname{Attention}(H^{(\ell)})
+
\operatorname{MLP}(H^{(\ell)}+\operatorname{Attention}(\cdot)).
\]

Residual connections preserve and accumulate information across layers. Attention mixes information between positions; the feed-forward network applies position-wise nonlinear transformations. The model’s “knowledge” and computations are distributed across these components rather than stored in one explicit symbolic module.

The Transformer paper established the architecture’s practical advantages, but it did not explain why large Transformers later exhibit instruction following, in-context learning, or broad reasoning behavior.[^13]

## 4. Pretraining Objectives and Model Families

Different objectives create different information-processing biases.

### Autoregressive language modeling

GPT-style models predict the next token from left context:

\[
p(x_t\mid x_{<t}).
\]

This objective naturally supports generation because training and inference use the same causal direction. It also exposes the model to every token as a prediction target and encourages learning syntax, discourse, facts, styles, and patterns that help predict continuations.[^14]

### Masked language modeling

BERT masks selected positions and predicts them from both left and right context:

\[
\mathcal{L}_{\mathrm{MLM}}
=
-\sum_{i\in M}\log p_\theta(x_i\mid x_{\setminus M}).
\]

This produces strong bidirectional representations for language understanding, although the artificial masking process differs from ordinary generation.[^15]

### Span corruption and text-to-text transfer

T5 turns many tasks into text-to-text generation and trains using corrupted spans. The broader principle is that a unified interface can make classification, summarization, translation, and question answering instances of conditional generation.[^16]

The objective matters because “what the model learns” is shaped by which prediction problem it repeatedly solves. A next-token model may learn extensive world knowledge without having a direct mechanism for checking whether a statement is true. A masked encoder may build strong contextual representations without being a natural open-ended generator.

## 5. Scaling Laws: Why Bigger Models Often Work Better

Empirical scaling research finds that held-out language-model loss often follows approximate power laws:

\[
L(N,D)\approx L_\infty+A N^{-\alpha}+B D^{-\beta},
\]

where \(N\) is parameter count and \(D\) is training data. Kaplan et al. fit smooth relationships among loss, model size, data, and compute across model families.[^17]

Hoffmann et al.’s Chinchilla work showed that many large models were under-trained relative to their parameter count. Under their compute-optimal analysis, model size and training-token count should grow in roughly comparable proportions, rather than spending most additional compute on parameters alone.[^18]

The key theoretical lesson is not a universal exponent. It is that **capacity, data, and optimization interact**. A model with more parameters can underperform a smaller model if it is inadequately trained. Conversely, smaller models can be deliberately overtrained when inference cost matters more than minimizing pretraining compute.[^19]

Scaling laws primarily predict aggregate loss. They do not directly predict every downstream ability. Downstream performance also depends on task structure, prompting, fine-tuning, data quality, contamination, tokenization, and evaluation metrics.

## 6. In-Context Learning: Learning Without Updating Weights

In-context learning (ICL) occurs when a model uses demonstrations in the prompt without changing its parameters. Given examples \(D=\{(x_i,y_i)\}_{i=1}^k\) and a query \(x_*\), the model estimates:

\[
p_\theta(y_*\mid D,x_*).
\]

GPT-3 made this behavior prominent by showing zero-shot, one-shot, and few-shot task performance from prompt conditioning alone.[^20]

### 6.1 Implicit Bayesian inference

One theory treats ICL as approximate Bayesian prediction over a latent task or data-generating process:

\[
p(y_*\mid x_*,D)
=
\int p(y_*\mid x_*,z)\,p(z\mid D)\,dz.
\]

Under this view, demonstrations update uncertainty about \(z\), such as the task identity, label mapping, topic, or latent function. Xie et al. constructed settings where language-model training produces behavior resembling Bayesian inference.[^21]

This account explains why additional, diverse demonstrations can reduce uncertainty, but it does not prove that frontier models explicitly represent a posterior distribution. Similar behavior can arise from other mechanisms.

### 6.2 Gradient-descent-like computation

A standard gradient update is:

\[
w_{t+1}=w_t-\eta\nabla_w\ell(f_w(x_t),y_t).
\]

The model cannot normally update its learned weights during inference, but it can encode an implicit parameter estimate in activations and transform that estimate across layers. Studies on linear-regression tasks found that Transformers can implement behavior resembling gradient descent, least-squares, or ridge regression.[^22][^23]

This is evidence for a powerful idea: a forward pass can implement an algorithm over examples. It is not evidence that every LLM literally runs gradient descent for every task.

### 6.3 Retrieval, copying, and induction

Some ICL is more like pattern retrieval than abstract task learning. Induction-head research identifies circuits that locate a previous token pattern and copy the token that followed it. This provides a mechanistic account of repeated-pattern completion and some forms of sequence continuation.[^24]

Induction heads are important but not a complete theory of ICL. They explain a narrow computational motif; they do not by themselves explain open-ended instruction following, mathematical reasoning, or all few-shot adaptation.

### 6.4 Pretrained priors and prompt calibration

Demonstrations can select behavior already encoded during pretraining. Min et al. found that randomly corrupted labels sometimes preserved much of few-shot performance, suggesting that input distribution, formatting, label-space information, and pretrained task priors contribute in addition to learning the intended input-output mapping.[^25]

Prompt order, label tokens, templates, and calibration also matter. Few-shot results should therefore report prompt sensitivity rather than treating one prompt as the model’s stable capability.[^26][^27]

### 6.5 The best current synthesis

These theories are not mutually exclusive. A model can:

1. use pretrained knowledge to identify a likely task;
2. retrieve relevant demonstrations through attention;
3. infer a latent task or label mapping;
4. execute a gradient-like update in activations;
5. copy local patterns through induction-like circuits.

The unresolved research question is which mechanism dominates for a given task, model, layer, prompt, and context length.

## 7. Reasoning, Chain of Thought, and Test-Time Compute

### 7.1 Chain of thought as computation

Chain-of-thought (CoT) prompting asks a model to generate intermediate steps:

\[
x\rightarrow r_1\rightarrow r_2\rightarrow\cdots\rightarrow r_T\rightarrow y.
\]

The intermediate tokens can function as scratch space, decomposition, a learned procedure, or an interface to later verification. Wei et al. showed that few-shot rationales can improve arithmetic, symbolic, and commonsense reasoning, especially at sufficient model scale.[^28]

The important theoretical point is that CoT converts a one-step answer into a longer sequence of conditional computations. The model gets more internal context to condition on, but it is still generating text—not executing a guaranteed formal proof.

### 7.2 Search and external structure

Tree-of-Thoughts and ReAct-style methods improve reasoning by adding branching, backtracking, tool calls, explicit state evaluation, or interaction with an environment.[^29][^30] Their gains are best interpreted as **language-model-controlled search and action selection**, not simply as the result of producing longer explanations.

Test-time compute is therefore a second scaling axis. Multiple samples, verifiers, adaptive token allocation, and search can improve accuracy when:

- the proposal distribution produces at least some correct candidates;
- the verifier can distinguish good from bad candidates;
- aggregation or search is appropriate to the task;
- additional computation is affordable.

More tokens alone do not guarantee better reasoning. The quality of the search policy and verifier is decisive.

### 7.3 Are chain-of-thought traces faithful?

An answer can be correct while its written rationale is incomplete or post hoc. Conversely, a plausible rationale can support a wrong answer. CoT is therefore useful as a computational scaffold, but it should not automatically be treated as a transparent transcript of the model’s internal causal process.

## 8. Alignment and Post-Training

### 8.1 Instruction tuning

Instruction tuning fine-tunes a pretrained model on examples formatted as natural-language instructions and desired responses. FLAN showed that a diverse instruction mixture can substantially improve zero-shot transfer to unseen tasks.[^31]

The supervised fine-tuning objective is ordinary conditional likelihood:

\[
\mathcal{L}_{\mathrm{SFT}}(\theta)
=
-\mathbb{E}_{(x,y)\sim D}
\sum_t\log\pi_\theta(y_t\mid x,y_{<t}).
\]

Instruction tuning teaches the interface of assistance—how to interpret requests, structure answers, follow formats, and refuse some requests. It does not automatically solve truthfulness, preference ambiguity, or robust safety.

### 8.2 Reward modeling and RLHF

RLHF typically has three stages:

1. supervised fine-tuning on demonstrations;
2. reward-model training from human comparisons;
3. policy optimization, often PPO, with a KL penalty.

For preferred response \(y^+\) and rejected response \(y^-\), a Bradley–Terry reward model can use:

\[
\mathcal{L}_{\mathrm{RM}}(\phi)
=
-\log\sigma(r_\phi(x,y^+)-r_\phi(x,y^-)).
\]

The policy objective is approximately:

\[
\max_\pi\;
\mathbb{E}[r_\phi(x,y)]
-\beta\,\mathrm{KL}\big(\pi(\cdot\mid x)\Vert\pi_{\mathrm{ref}}(\cdot\mid x)\big).
\]

The KL term limits uncontrolled drift from the reference model. InstructGPT demonstrated that a much smaller RLHF-trained model could be preferred by human evaluators over a much larger base model for instruction-following behavior.[^6]

### 8.3 Why alignment is difficult

Human preferences are a proxy, not a complete objective. Reward models can overvalue fluency, agreement, politeness, or apparent helpfulness. They can underweight factuality, uncertainty, long-term consequences, or rare failure modes. Optimization can exploit weaknesses in the proxy, producing reward hacking or specification gaming.

Alignment also has multiple meanings:

- instruction following;
- harmlessness;
- truthfulness;
- preference matching;
- robustness under distribution shift;
- preserving human control;
- pursuing intended goals rather than literal proxies.

Improvement on one dimension does not establish improvement on all of them.

### 8.4 Constitutional and AI-feedback approaches

Constitutional AI uses explicit principles to prompt critique and revision, then can use model-generated comparisons for reinforcement learning from AI feedback.[^32] This may reduce some direct human-labeling cost and make principles more explicit, but it moves the problem rather than eliminating it: the constitution, critique model, and preference model are all imperfect specifications.

Direct Preference Optimization (DPO) avoids an explicit reward-model-plus-PPO loop by optimizing a preference objective directly relative to a reference policy.[^33] This simplifies training, but it does not remove preference-data bias or guarantee robust alignment.

## 9. Retrieval, Tools, Memory, and Agents

Classical language-model theory treats parameters as the primary knowledge store. Retrieval-augmented systems add external evidence \(z\):

\[
p(y\mid x)
=
\sum_z p_\eta(z\mid x)\,p_\theta(y\mid x,z).
\]

This separates **knowledge acquisition** from **knowledge access**. The model weights can contain compressed statistical regularities, while documents, indexes, databases, and tools provide current or specialized information.[^34][^35]

### 9.1 Retrieval-augmented generation

REALM and RAG demonstrated that retrieval can improve knowledge-intensive question answering and make the knowledge source more modular.[^34][^35] RETRO showed that a relatively small parametric model augmented with a very large external memory can approach the performance of substantially larger parametric models.[^36]

The theoretical implication is that model size and external-memory size become partially substitutable resources. A system can gain effective knowledge without encoding every fact solely in its weights.

### 9.2 Retrieval is not grounding

Retrieval does not guarantee truth. The retriever may select irrelevant, stale, or false documents; the generator may ignore or distort them; and citations may not actually support the claims made. ALCE and FActScore separate answer quality, citation quality, and claim-level factual support.[^37][^38] RAGTruth documents hallucinations that persist even when retrieved evidence is available.[^39]

Grounded generation therefore requires claim-level support, source quality, attribution, and faithfulness—not merely a non-empty context window.

### 9.3 Agents as policies over actions and observations

An agentic system can be modeled as a policy:

\[
\pi_\theta(a_t\mid h_t),
\]

where \(a_t\) is an action chosen from history \(h_t\). The environment returns observations, and external memory is updated:

\[
o_{t+1}\sim P(o\mid s_t,a_t),
\qquad
m_{t+1}=U(m_t,h_t,o_{t+1}).
\]

Tools change the action space from “emit text” to “search, calculate, execute code, call an API, inspect a file, or modify an environment.” This can improve reliability when the tool provides a verifiable operation, but it also introduces action-selection errors, permission risks, and failure recovery problems.

Self-RAG illustrates a related control idea: the model learns when retrieval is needed and can emit reflection signals about relevance, support, and usefulness.[^40]

## 10. Interpretability, Circuits, and What We Know Internally

Mechanistic interpretability seeks causal explanations of model behavior in terms of components and features. Transformer-circuit work analyzes attention heads, residual-stream interactions, and small computational motifs such as induction heads.[^24]

Sparse-autoencoder research attempts to decompose dense activations into more interpretable features, motivated by the superposition hypothesis: a network may represent more features than it has dedicated neurons by encoding them in distributed directions.[^41]

The strongest current distinction is:

1. **Correlation:** an activation correlates with a concept.
2. **Causal localization:** changing the activation changes behavior.
3. **Circuit explanation:** a set of components and interactions reproduces behavior under interventions.
4. **Global theory:** the explanation generalizes across tasks, prompts, scales, and domains.

Much current work reaches levels 1–3 for narrow behaviors; level 4 remains unestablished. Feature decompositions can be non-unique, polysemantic, basis-dependent, and sensitive to sparse-autoencoder choices. A useful feature is not necessarily the model’s only or canonical representation of a concept.[^41]

## 11. Hallucination, Calibration, and Generalization Limits

Hallucination is not one failure mechanism. It can arise from:

- incomplete or conflicting parametric knowledge;
- statistical continuation of false claims;
- decoding uncertainty;
- retrieval–generation mismatch;
- failure to represent uncertainty;
- distribution shift;
- optimization toward fluency or preference rather than truth.

SelfCheckGPT uses disagreement among multiple samples as a black-box hallucination signal, while semantic-entropy work attempts to group semantically equivalent answers and estimate uncertainty at the meaning level rather than only at the token level.[^42][^43] Neither method is a universal truth detector: consistent answers can be consistently false, and multiple answers can be valid.

LLMs also remain fragile under systematic distribution shift. They may solve familiar templates while failing small changes in ordering, composition, or symbolic structure. Scaling improves many capabilities but does not guarantee robust abstraction, causal reasoning, or reliable extrapolation.

## 12. Emergent Abilities: Real Transitions or Measurement Artifacts?

Some work describes capabilities that appear only above certain model scales.[^44] However, benchmark scores can create apparent phase transitions even when underlying token-level competence improves smoothly.

Suppose the probability of producing an individual correct token improves smoothly:

\[
p_N(\text{token correct})=\exp[-\mathcal{L}_{CE}(N)].
\]

If exact-match evaluation requires \(L\) tokens to be correct, then approximately:

\[
\operatorname{Accuracy}(N)
\approx
p_N(\text{token correct})^L.
\]

Exponentiation can turn gradual token-level improvement into a sharp-looking task-level curve. Schaeffer et al. show that changing from exact-match or pass/fail metrics to more continuous measures such as log loss, edit distance, or calibration can make apparent emergence weaker or disappear.[^45]

This does not prove that every capability transition is an artifact. Genuine thresholds can arise when a system acquires enough competence to execute a multistep procedure, use a tool, or benefit from search. The correct practice is to report:

- continuous and discrete metrics;
- confidence intervals;
- multiple prompts and orderings;
- fresh or contamination-controlled test sets;
- complexity sweeps;
- partial credit and per-step success;
- calibration and error decomposition.

## 13. A Unified Theoretical Picture

A useful abstraction is to view a modern LLM system as a hierarchy:

```text
Data and objective
        |
        v
Pretrained representations and circuits
        |
        v
In-context computation over prompt examples
        |
        v
Post-training policy shaping and preference optimization
        |
        v
Retrieval, tools, memory, and external search
        |
        v
Task-specific behavior measured by imperfect evaluations
```

Each layer answers a different question:

- **Pretraining:** What statistical structure can be compressed from data?
- **Architecture:** How can information move and be transformed?
- **Scale:** How does predictive quality change with capacity, data, and compute?
- **In-context learning:** How can the forward pass adapt to examples without weight updates?
- **Post-training:** Which behaviors are preferred or suppressed?
- **Retrieval/tools:** What knowledge and computation can be externalized?
- **Evaluation:** How do we distinguish real capability from artifacts, memorization, or formatting skill?

The layers interact. A larger pretrained model may acquire better priors for instruction tuning. Better representations may make in-context algorithms easier to implement. Retrieval may compensate for limited parametric memory. Tool use may convert an unreliable text prediction into a verifiable computation. Alignment may improve usability while also changing the distribution of answers in ways that affect benchmark performance.

## 14. Open Problems

1. **A unified theory of ICL:** Current Bayesian, optimization, retrieval, and circuit accounts explain different regimes but not all of them.
2. **Generalization:** Why do models generalize well on some novel combinations but fail on small systematic changes?
3. **Truthfulness:** How can systems distinguish high-probability language from well-supported claims?
4. **Faithful reasoning:** When does a chain-of-thought trace reflect the computation that caused the answer?
5. **Alignment robustness:** How can preference optimization avoid sycophancy, reward hacking, and distribution-shift failures?
6. **Mechanistic scaling:** Can circuit explanations scale from small controlled models to frontier systems?
7. **Data quality and provenance:** How do mixture choices, duplication, synthetic data, and contamination shape capabilities?
8. **Long-context theory:** What determines whether information in a large context is actually retrieved and used?
9. **Agent reliability:** How should systems decide when to act, ask for clarification, retrieve, verify, or stop?
10. **Evaluation:** How can benchmarks measure abstraction, causal reasoning, uncertainty, and real-world reliability rather than template familiarity?

## Confidence Assessment

**High confidence:** LLMs are trained as probabilistic sequence models; distributed representations, self-attention, positional information, and likelihood objectives are the core mathematical foundations.[^1][^10][^13] Scaling laws reliably describe broad regimes of aggregate language-model loss, though exact exponents are not universal.[^17][^18]

**Moderate confidence:** Transformers can implement specific in-context algorithms, retrieval/copying mechanisms, and gradient-descent-like computation on controlled task families.[^4][^22][^24] Instruction tuning and preference optimization reliably alter response behavior, but the generalization and safety of those changes depend on data and evaluation.[^6][^7]

**Moderate-to-low confidence:** Chain-of-thought, search, retrieval, and tools can improve reasoning or factuality under appropriate conditions, but they do not guarantee faithful reasoning or grounded answers.[^28][^29][^37]

**Low confidence:** There is no complete, validated theory of frontier LLM reasoning, general intelligence, consciousness, or globally interpretable internal representations. Current theories are pluralistic and often supported by narrow tasks, small models, synthetic data, or benchmark-specific measurements.[^41][^45]

**Assumptions:** “LLM research” is interpreted broadly to include foundational architecture, pretraining, scaling, inference-time adaptation, alignment, interpretability, retrieval, agents, and evaluation. The report treats peer-reviewed publications as stronger evidence than technical reports or preprints, but includes seminal non-peer-reviewed reports when they introduced influential mechanisms.

## Footnotes

[^1]: [Bengio et al., “A Neural Probabilistic Language Model,” *JMLR*](https://doi.org/10.1162/153244303322533223).
[^2]: [Vaswani et al., “Attention Is All You Need,” NeurIPS 2017](https://papers.nips.cc/paper/7181-attention-is-all-you-need).
[^3]: [Devlin et al., “BERT: Pre-training of Deep Bidirectional Transformers,” NAACL 2019](https://doi.org/10.18653/v1/N19-1423).
[^4]: [Brown et al., “Language Models are Few-Shot Learners,” NeurIPS 2020](https://papers.nips.cc/paper/2020/hash/1457c0d6bfcb4967418bfb8ac142f64a-Abstract.html).
[^5]: [Garg et al., “What Can Transformers Learn In-Context?,” NeurIPS 2022](https://doi.org/10.52202/068431-2217).
[^6]: [Ouyang et al., “Training Language Models to Follow Instructions with Human Feedback,” NeurIPS 2022](https://arxiv.org/abs/2203.02155).
[^7]: [Rafailov et al., “Direct Preference Optimization,” NeurIPS 2023](https://arxiv.org/abs/2305.18290).
[^8]: [Asai et al., “Self-RAG: Learning to Retrieve, Generate, and Critique through Self-Reflection,” ICLR 2024](https://proceedings.iclr.cc/paper_files/paper/2024/hash/25f7be9694d7b32d5cc670927b8091e1-Abstract-Conference.html).
[^9]: [Lin, Hilton & Evans, “TruthfulQA,” ACL 2022](https://aclanthology.org/2022.acl-long.229/).
[^10]: [Bengio et al., “A Neural Probabilistic Language Model,” *JMLR*](https://www.jmlr.org/papers/volume3/bengio03a/bengio03a.html).
[^11]: [Mikolov et al., “Efficient Estimation of Word Representations in Vector Space,” arXiv:1301.3781](https://arxiv.org/abs/1301.3781).
[^12]: [Pennington, Socher & Manning, “GloVe,” EMNLP 2014](https://doi.org/10.3115/v1/D14-1162).
[^13]: [Vaswani et al., “Attention Is All You Need,” NeurIPS 2017](https://papers.nips.cc/paper/7181-attention-is-all-you-need).
[^14]: [Radford et al., “Improving Language Understanding by Generative Pre-Training,” technical report](https://cdn.openai.com/research-covers/language-unsupervised/language_understanding_paper.pdf).
[^15]: [Devlin et al., “BERT,” NAACL 2019](https://aclanthology.org/N19-1423/).
[^16]: [Raffel et al., “Exploring the Limits of Transfer Learning with a Unified Text-to-Text Transformer,” *JMLR*](https://jmlr.org/papers/v21/20-074.html).
[^17]: [Kaplan et al., “Scaling Laws for Neural Language Models,” preprint](https://arxiv.org/abs/2001.08361).
[^18]: [Hoffmann et al., “Training Compute-Optimal Large Language Models,” NeurIPS 2022](https://papers.nips.cc/paper_files/paper/2022/hash/c1e2faff6e7a14d8f1f5f3e0d3e7f6c2-Abstract-Conference.html).
[^19]: [Gadre et al., “Language Models Scale Reliably with Over-training and on Downstream Tasks,” preprint](https://arxiv.org/abs/2403.08540).
[^20]: [Brown et al., “Language Models are Few-Shot Learners,” NeurIPS 2020](https://arxiv.org/abs/2005.14165).
[^21]: [Xie et al., “An Explanation of In-context Learning as Implicit Bayesian Inference,” ICLR 2022](https://arxiv.org/abs/2111.02080).
[^22]: [Akyürek et al., “What Learning Algorithm Is In-Context Learning?,” ICLR 2023](https://arxiv.org/abs/2211.15661).
[^23]: [von Oswald et al., “Transformers Learn In-Context by Gradient Descent,” ICML 2023](https://proceedings.mlr.press/v202/von-oswald23a.html).
[^24]: [Olsson et al., “In-context Learning and Induction Heads,” technical report](https://arxiv.org/abs/2209.11895).
[^25]: [Min et al., “Rethinking the Role of Demonstrations,” EMNLP 2022](https://doi.org/10.18653/v1/2022.emnlp-main.759).
[^26]: [Lu et al., “Fantastically Ordered Prompts,” ACL 2022](https://aclanthology.org/2022.acl-long.556/).
[^27]: [Zhao et al., “Calibrate Before Use,” ICML 2021](https://proceedings.mlr.press/v139/zhao21c.html).
[^28]: [Wei et al., “Chain-of-Thought Prompting Elicits Reasoning,” NeurIPS 2022](https://doi.org/10.52202/068431-1800).
[^29]: [Yao et al., “Tree of Thoughts,” NeurIPS 2023](https://arxiv.org/abs/2305.10601).
[^30]: [Yao et al., “ReAct: Synergizing Reasoning and Acting in Language Models,” ICLR 2023](https://openreview.net/forum?id=WE_vluYUL-X).
[^31]: [Wei et al., “Finetuned Language Models Are Zero-Shot Learners,” ICLR 2022](https://arxiv.org/abs/2109.01652).
[^32]: [Bai et al., “Constitutional AI: Harmlessness from AI Feedback,” technical report](https://arxiv.org/abs/2212.08073).
[^33]: [Rafailov et al., “Direct Preference Optimization,” NeurIPS 2023](https://arxiv.org/abs/2305.18290).
[^34]: [Guu et al., “REALM: Retrieval-Augmented Language Model Pre-Training,” ICML 2020](https://proceedings.mlr.press/v119/guu20a.html).
[^35]: [Lewis et al., “Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks,” NeurIPS 2020](https://papers.nips.cc/paper/2020/hash/6b493230205f780e1bc26945df7481e5-Abstract.html).
[^36]: [Borgeaud et al., “Improving Language Models by Retrieving from Trillions of Tokens,” *Nature*](https://doi.org/10.1038/s41586-022-04566-9).
[^37]: [Gao et al., “Enabling Large Language Models to Generate Text with Citations,” EMNLP 2023](https://doi.org/10.18653/v1/2023.emnlp-main.398).
[^38]: [Min et al., “FActScore,” EMNLP 2023](https://doi.org/10.18653/v1/2023.emnlp-main.741).
[^39]: [Niu et al., “RAGTruth,” ACL 2024](https://doi.org/10.18653/v1/2024.acl-long.585).
[^40]: [Asai et al., “Self-RAG,” ICLR 2024](https://selfrag.github.io/).
[^41]: [Conmy et al., “Towards Automated Circuit Discovery for Mechanistic Interpretability,” NeurIPS 2023](https://proceedings.neurips.cc/paper_files/paper/2023/hash/34e1dbe95d34d7ebaf99b9bcaeb5b2be-Abstract-Conference.html).
[^42]: [Manakul, Liusie & Gales, “SelfCheckGPT,” EMNLP 2023](https://doi.org/10.18653/v1/2023.emnlp-main.557).
[^43]: [Farquhar et al., “Detecting Hallucinations in Large Language Models Using Semantic Entropy,” *Nature*](https://doi.org/10.1038/s41586-024-07421-0).
[^44]: [Wei et al., “Emergent Abilities of Large Language Models,” TMLR](https://doi.org/10.48550/arXiv.2206.07682).
[^45]: [Schaeffer, Miranda & Koyejo, “Are Emergent Abilities of Large Language Models a Mirage?,” NeurIPS 2023](https://arxiv.org/abs/2304.15004).
