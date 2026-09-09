# How Modern LLMs Are Used by Software Engineers in 2025 and Onwards

## Executive Summary

Modern large language models (LLMs) are being used by software engineers primarily as interactive, context-dependent assistants rather than autonomous programmers. The most established uses are code completion and generation, boilerplate and repetitive changes, code explanation, information retrieval, debugging, testing, documentation, refactoring, and code-review support.[^1][^2][^3] Evidence from randomized and field experiments indicates that LLM coding assistants can increase measured throughput or reduce completion time, but effects are heterogeneous: gains are larger in some enterprise and bounded-task settings, while experienced developers working in mature open-source repositories may become slower because prompting, reviewing, correcting, and integrating output adds overhead.[^4][^5][^6]

The central engineering change is a shift from writing every line directly toward supervising, evaluating, testing, and integrating generated artifacts. LLMs are capable of useful code and explanations, but recent research documents hallucinated APIs and dependencies, missed vulnerabilities, failed CVE repairs, inaccurate license information, and low-precision review comments.[^7][^8][^9][^10] Human review, repository-aware validation, testing, static and dynamic analysis, security checks, and provenance controls therefore remain essential.

The evidence is strongest for short-term individual productivity and coding-adjacent assistance. It is weaker for long-term maintainability, technical debt, documentation accuracy, architecture, requirements engineering, team learning, employment, wages, and organizational governance. The most defensible conclusion is not that LLMs “replace software engineers,” but that they redistribute engineering effort toward specification, judgment, verification, debugging, integration, and accountability.

## Scope and Evidence Standard

This report synthesizes academic journal articles and peer-reviewed conference papers published in 2025 or later, with a small number of earlier studies used only as context. Preprints and working papers are labeled explicitly and are not treated as equivalent to peer-reviewed evidence. “Use” is distinguished from “capability”: a benchmark showing that a model can generate a refactoring is not the same as evidence that professional engineers routinely use LLMs for refactoring.

The query is conceptual and explanatory. The report therefore emphasizes recurring workflows, measurable effects, trade-offs, and confidence rather than implementation instructions.

## 1. What Engineers Actually Use LLMs For

### 1.1 Implementation, completion, and repetitive work

Coding and implementation remain the most mature and frequently observed uses. In an observational study of 49 programming sessions across 25 days, generative AI was used in all 16 solo sessions and 30 of 33 pair-programming sessions. Developers used Copilot and ChatGPT for coding assistance, information retrieval, repetitive work, and problem solving; the study reported approximately 23 minutes of perceived daily workload reduction.[^1] A separate mixed-methods study of 114 developers and 13 interviewees in a large public-sector organization similarly found uses including code generation, boilerplate, documentation, brainstorming, and unfamiliar-code assistance.[^2]

These findings suggest that the practical unit of use is often not “generate an entire feature.” Engineers ask for a starting point, a pattern, an API example, a transformation, or an explanation, then adapt the result to local requirements. Earlier observational work with professional engineers likewise found three broad modes: manipulating artifacts, learning about technologies and concepts, and obtaining high-level guidance that the developer implements independently.[^11]

### 1.2 Explanation, search, and learning-in-the-moment

LLMs increasingly act as conversational search and explanation systems. Developers use them to understand unfamiliar code, APIs, technologies, errors, and possible approaches.[^2][^11] This can reduce context switching and the need to search multiple sources, but it also creates a verification problem: a fluent explanation can be incomplete or wrong, particularly when repository-specific context is missing.[^7]

The evidence does not yet show durable learning or skill improvement. Studies observe information seeking and perceived assistance, but few follow professional developers long enough to measure retention, independent performance, or whether reduced peer interaction creates “comprehension debt.”[^12]

### 1.3 Debugging and repair

Debugging is a common use, but model performance can degrade during repeated failed attempts. A 2025 *Scientific Reports* study found that several tested models lost roughly 60–80% of their initial debugging effectiveness within two or three attempts, motivating fresh-context or strategy-reset interventions rather than indefinite continuation of a failing conversation.[^13]

Repository-level repair is harder than isolated code completion. The NAACL 2025 CVE-Bench study evaluated 509 real-world vulnerabilities from 120 open-source repositories and found that a representative software-engineering agent repaired at most about 21% of the vulnerabilities.[^8] This supports using LLMs for candidate diagnosis and patch generation, but not for unsupervised vulnerability remediation.

### 1.4 Testing

LLMs are used to generate unit tests, suggest test cases, explain failures, and help construct acceptance criteria. However, much of the academic evidence evaluates model-generated tests rather than observing professional engineers using them in production. Research on testing LLM-powered applications finds that developers must combine conventional code-level testing with behavioral evaluation because outputs can be unpredictable, prompt-sensitive, and difficult to oracle.[^3]

The practical implication is that generated tests should be treated as additional test hypotheses, not proof of correctness. Engineers still need to assess coverage, assertions, test independence, mutation effectiveness, and whether the tests encode the intended behavior rather than the implementation’s current bug.

### 1.5 Refactoring and maintenance

LLMs can assist with code transformations, modernization, and technical-debt analysis. A 2025 *Automated Software Engineering* study evaluated 180 real-world refactorings from 20 Java projects. With structured prompts, ChatGPT’s refactoring-opportunity identification increased from 15.6% to 86.7%; 63.6% of 176 generated solutions were judged comparable to or better than human-expert refactorings. Nevertheless, 13 ChatGPT outputs and 9 Gemini outputs were unsafe because they changed functionality or contained syntax errors.[^14]

This is strong capability evidence but weaker evidence of routine practitioner use. It shows that prompt structure and validation matter; it does not show that teams can safely delegate maintenance work without review.

### 1.6 Documentation

Engineers use LLMs for comments, API documentation, summaries, examples, and repetitive documentation edits.[^1][^2] Academic evaluations often find that LLMs can produce readable technical prose, but objective lexical metrics do not reliably capture factual correctness or usefulness; human evaluation and context-aware assessment are more informative.[^3]

The major unresolved issue is documentation drift. There is little longitudinal evidence that generated documentation remains synchronized with changing code, dependencies, and operational behavior.

### 1.7 Code review

Code review is an important emerging use. An ESEM 2025 field study at WirelessCar compared proactive AI-generated reviews with on-demand interactive assistance, using retrieval-augmented generation to provide repository context. Developers generally preferred AI assistance for large or unfamiliar pull requests, but preferences varied with codebase familiarity and perceived risk; the authors favor hybrid designs that combine proactive suggestions with reviewer control.[^15]

At larger scale, BitsAI-CR describes an industrial deployment at ByteDance with more than 12,000 weekly active users and reported review-comment precision of approximately 75%. For Go code, its Outdated Rate was about 26.7%, below reported human-review rates of roughly 35–46%.[^10] These results demonstrate operational scalability, not autonomous correctness: imprecise comments still create triage work and alert fatigue, and “outdated” is only an indirect measure of whether a comment led to a code change.

### 1.8 Requirements, architecture, and design

LLMs are being investigated for requirements extraction, classification, ambiguity reduction, formalization, validation, UML generation, architecture advice, and design alternatives. A 2025 systematic review of requirements-engineering research identified 29 primary studies after screening 287 initial records and found growing use across elicitation, analysis, specification, and validation.[^16]

However, the review also found limited industrial validation, small or synthetic datasets, inconsistent metrics, hallucinations, and difficulty handling domain-specific dependencies. Evidence that professional teams achieve better requirements or architecture outcomes through LLM use is therefore weak compared with evidence for coding assistance.

## 2. Productivity: Positive, Heterogeneous, and Measurement-Sensitive

### 2.1 Positive effects in randomized and enterprise settings

The strongest causal evidence comes from randomized experiments. A peer-reviewed *Management Science* article reports three field experiments at Microsoft, Accenture, and an anonymous Fortune 100 company involving 4,867 developers. Access to an AI coding assistant increased completed tasks by 26.08% in the pooled estimate, with larger gains among less-experienced developers.[^4]

An ICSE-SEIP 2025 randomized trial with 96 full-time Google software engineers found that participants using internal AI-enhanced coding features completed a complex enterprise task approximately 21% faster.[^17] An earlier controlled experiment with professional programmers reported a 55.8% speed improvement on a standardized JavaScript server task, but that work is a preprint and used a bounded task rather than ordinary production development.[^18]

Together, these studies support a qualified claim: LLM assistants can increase short-run throughput or reduce time-on-task under some conditions.

### 2.2 Negative or null effects in mature codebases

The gains are not universal. A 2025 METR randomized study of 16 experienced open-source developers completing 246 real GitHub tasks found that allowing early-2025 AI tools increased task completion time by approximately 19%.[^6] The authors associate the result with the cost of prompting, reviewing, correcting, and integrating output in mature repositories.

Longitudinal observational evidence also cautions against simple before-and-after claims. A study of Copilot use in a Norwegian public-sector organization examined 26,317 non-merge commits across 703 repositories, with a focused comparison of 25 Copilot users and 14 non-users. Copilot users were already more active before adoption, and no statistically significant change in commit-based activity was detected after adoption, even though users reported higher perceived productivity.[^19]

### 2.3 Why results differ

The literature points to several moderators:

- **Task structure:** bounded, well-specified coding tasks are more favorable than ambiguous, cross-cutting maintenance.
- **Repository maturity:** unfamiliar or mature codebases impose context and integration costs.
- **Developer experience:** less-experienced developers often show larger measured gains, while expert maintainers may spend more time validating suggestions.
- **Tool and model integration:** context retrieval, IDE integration, test execution, and repository indexing affect usefulness.
- **Measurement choice:** commits, lines of code, time-on-task, perceived productivity, completed tickets, quality, and maintenance cost measure different constructs.
- **Adoption behavior:** access to a tool is not the same as use, and adopters may differ systematically from non-adopters.[^4][^6][^19]

The 2026 systematic review and mapping study of 39 peer-reviewed primary studies concludes that most research reports benefits, but code-quality findings are contradictory and only a minority of studies cover more than three dimensions of the SPACE productivity framework.[^20] Productivity should therefore be treated as multidimensional: speed is only one component alongside performance, activity, satisfaction, communication, and efficiency.

## 3. Quality, Security, and the Verification Burden

### 3.1 Repository-context hallucinations

Practical code-generation failures are often contextual rather than syntactic. An ISSTA 2025 study of six LLMs using the CoderEval repository-level benchmark identified hallucination classes involving task requirements, factual knowledge, project context, APIs, dependencies, environments, and non-code resources. Task-requirement conflicts were the most prevalent type; repository-specific retrieval improved performance but did not eliminate hallucinations.[^7]

This explains why code that compiles can still be wrong: it may violate requirements, call an invented API, assume the wrong dependency version, or conflict with project conventions.

### 3.2 Security weaknesses and vulnerability repair

A 2025 *Empirical Software Engineering* study evaluated Claude 3, GPT-4, and Llama 3 on approximately 300 vulnerable programming questions. Vulnerability-detection rates ranged from 12.6% to 40%, depending on model and vulnerability type.[^9] Models could provide useful explanations when they detected a flaw, but omission and false reassurance remained common.

CVE-Bench provides a complementary repository-level result: only about 21% of real-world CVE vulnerabilities were repaired by a representative software-engineering agent under the benchmark conditions.[^8] These findings support mandatory human security review, tests targeted at the original vulnerability, static and dynamic analysis, and careful regression checking.

### 3.3 Security-functionality trade-offs

Security-oriented generation methods can appear safer by deleting or disabling functionality. An accepted ICSE 2026 study found that some secure-code-generation techniques reduced overall performance by more than 50%, produced unrelated “garbage code,” or removed vulnerable code without implementing the intended behavior. It also found that CodeQL missed several vulnerabilities, showing that a single analyzer is not a sufficient oracle.[^21]

The engineering lesson is that security and functional correctness must be evaluated jointly. A patch that removes a warning by removing the feature is not a successful repair.

### 3.4 Code review and technical debt

LLMs can help detect technical debt, but detection is not the same as reducing long-term debt. DebtGuardian, a PROFES 2025 framework evaluated on more than 10,000 annotated real-world code changes, found that granular prompting and ensemble voting improved detection recall by approximately 8.17%.[^22] This is evidence for assistive triage, not proof that LLM adoption changes future maintenance cost.

Similarly, industrial review systems can increase review coverage, but imperfect precision can shift work from finding issues to triaging low-signal comments.[^10]

### 3.5 Licensing, provenance, and privacy

License and privacy concerns are concrete rather than hypothetical. LiCoEval, published at ICSE 2025, evaluated 14 LLMs and found that approximately 0.88%–2.01% of generated outputs were strikingly similar to known open-source code, depending on model. Models generally failed to provide accurate license information, particularly for copyleft licenses.[^23]

A 2025 EASE study surveyed 51 professional developers about privacy-related code generation. Participants did not consider current assistants sufficiently trustworthy for reliably producing privacy-compliant code and called for stronger model behavior, safeguards, transparency, and developer guidance.[^24]

Organizations using LLMs in software engineering therefore need provenance and similarity scanning, license review, restrictions on sensitive inputs, data-protection controls, and explicit ownership and accountability practices.

## 4. Human–AI Collaboration and Organizational Adoption

### 4.1 Adoption is socio-technical

The strongest organizational studies show that adoption depends on workflow compatibility, perceived reliability, team norms, social influence, and organizational support—not simply on model capability.[^2] Developers’ willingness to continue using tools is shaped by whether the tools fit existing processes and whether colleagues and managers establish acceptable practices.

An IBM enterprise case study using two surveys with a combined N=669 and usability testing with 15 participants found that most users reported a net productivity increase, but benefits were uneven. The deployment also created questions about code ownership, authorship, and responsibility for AI-generated code.[^25]

These findings imply that organizations need team-level conventions: when AI assistance is appropriate, what must be disclosed, what validation is mandatory, and who is accountable for the merged artifact.

### 4.2 Solo, pair, and team work

The HICSS observational study found more consistent AI use in solo than pair programming, where human discussion remained important.[^1] A 2025 comparative study of 234 undergraduate students found that AI-assisted pairing improved motivation, reduced anxiety, and improved programming performance relative to individual work, while human–human pairing produced stronger social presence and perceived collaboration.[^26]

The student setting limits direct workplace generalization, but the direction is informative: an AI partner can provide assistance and reduce friction without reproducing the social learning, shared understanding, and accountability of a human teammate.

### 4.3 Language and context matter

A survey of 65 Spanish-speaking developers in Ecuador found that assistants struggled with Spanish identifiers, hybrid naming, and local conventions. Respondents to the Spanish-language survey abandoned AI recommendations more often than respondents to the English-language survey, 25% versus 17.9%, and requested multilingual support, repository context, provenance, and confidence information.[^27]

LLM adoption should therefore not be evaluated as culturally or linguistically uniform. Tool performance, trust, and abandonment can vary with language, naming conventions, documentation, and training data.

## 5. What Engineers Should Treat as the New Core Skill

The research supports a change in the skill profile of software engineering:

1. **Specification:** express requirements, constraints, interfaces, and acceptance criteria precisely enough for both humans and models.
2. **Context provision:** supply repository conventions, dependencies, architecture, data models, and relevant history.
3. **Critical evaluation:** identify invented APIs, incorrect assumptions, missing edge cases, and requirement violations.
4. **Verification:** compile, test, fuzz, inspect dependencies, run static and dynamic analysis, and conduct human review.
5. **Security and provenance:** check vulnerabilities, secrets, data-protection implications, license similarity, and attribution.
6. **Integration judgment:** decide whether a generated change fits the architecture, operational constraints, and maintenance model.
7. **Communication and accountability:** explain what was generated, how it was validated, and who approved the result.

This is not a claim that prompt engineering replaces programming expertise. Rather, programming expertise becomes more valuable for judging whether generated artifacts are appropriate, safe, maintainable, and consistent with system-level goals.

## 6. Evidence Gaps and Limits

The academic evidence does not yet answer several important questions:

- **Long-term quality:** few studies measure escaped defects, technical debt, maintainability, incidents, or rework over months or years.
- **Team outcomes:** solo use is better studied than effects on shared understanding, onboarding, pair programming, review burden, and knowledge sharing.
- **Learning:** use for explanation and search is documented, but durable professional skill gains or skill erosion are not established.
- **Requirements and architecture:** promising evaluations exist, but industrial evidence is sparse.
- **Documentation drift:** generation quality is studied more often than synchronization with changing code.
- **Employment and wages:** coding-assistant studies do not establish causal effects on layoffs, hiring, wages, promotion, or occupational entry.
- **Privacy and IP incidents:** concerns are well motivated, but direct measurements of proprietary-code leakage or legal outcomes in real organizations remain limited.
- **Model drift:** tool interfaces and underlying models change rapidly, making replication and version control essential.

The literature is also vulnerable to self-selection, self-reported productivity, small single-organization samples, benchmark mismatch, and inconsistent definitions of “productivity.” The 2025–2026 evidence should therefore be read as a growing but incomplete empirical base.

## Confidence Assessment

**High confidence:** Software engineers use LLMs for code completion/generation, boilerplate, explanation, information retrieval, debugging assistance, documentation, and increasingly code review.[^1][^2][^11] LLM output requires human validation because repository-context hallucinations, missed vulnerabilities, unsafe patches, and inaccurate licensing information are empirically documented.[^7][^8][^9][^23]

**Moderate confidence:** LLM assistants can improve short-run measured productivity in some enterprise and bounded-task settings, with larger gains often reported for less-experienced developers.[^4][^17] Benefits are heterogeneous and can reverse in complex mature repositories.[^6]

**Moderate-to-low confidence:** LLMs improve code quality, maintainability, documentation accuracy, requirements, architecture, or long-term technical debt. Existing results are task-, model-, and measurement-dependent, and many are capability evaluations rather than sustained developer studies.[^14][^16][^20]

**Low confidence:** LLM use has established causal effects on software-engineer employment, wages, durable learning, or organization-wide role replacement. The surveyed software-engineering literature does not provide sufficient evidence for those claims.

**Assumptions:** “2025 and onwards” is interpreted as emphasizing publications from 2025 onward while allowing earlier peer-reviewed or preprint studies only to establish context. Academic conference papers are treated as peer-reviewed when the research packet identified an archival proceedings publication; arXiv and SSRN versions are labeled as preprints or working papers.

## Footnotes

[^1]: [Stray et al., “Generative AI and Developer Workflows: How GitHub Copilot and ChatGPT Influence Solo and Pair Programming,” HICSS 2025](https://doi.org/10.24251/HICSS.2025.883).
[^2]: [Stray, Barbala & Wivestad, “Human-AI Collaboration in Software Development,” FSE Companion 2025](https://doi.org/10.1145/3696630.3730566).
[^3]: [Magalhães et al., “Testing the Untestable? An Empirical Study on the Testing Process of LLM-Powered Software Systems,” SCAM 2025](https://arxiv.org/abs/2508.00198).
[^4]: [Cui et al., “The Effects of Generative AI on High-Skilled Work,” *Management Science*](https://doi.org/10.1287/mnsc.2025.00535).
[^5]: [Paradis et al., “How Much Does AI Impact Development Speed?,” ICSE-SEIP 2025](https://doi.org/10.1109/ICSE-SEIP66354.2025.00060).
[^6]: [Becker et al., “Measuring the Impact of Early-2025 AI on Experienced Open-Source Developer Productivity,” preprint](https://arxiv.org/abs/2507.09089).
[^7]: [Zhang et al., “LLM Hallucinations in Practical Code Generation,” ISSTA 2025](https://doi.org/10.1145/3728894).
[^8]: [Wang, Liu & Xiao, “CVE-Bench,” NAACL 2025](https://doi.org/10.18653/v1/2025.naacl-long.212).
[^9]: [Sajadi et al., “Do LLMs Consider Security?,” *Empirical Software Engineering*](https://doi.org/10.1007/s10664-025-10658-6).
[^10]: [Sun et al., “BitsAI-CR: Automated Code Review via LLM in Practice,” FSE 2025](https://doi.org/10.1145/3696630.3728552).
[^11]: [Khojah et al., “Beyond Code Generation: An Observational Study of ChatGPT Usage in Software Engineering Practice,” ESEC/FSE 2024](https://arxiv.org/abs/2404.14901).
[^12]: [Mohamed, Assi & Guizani, “The Impact of LLM-Assistants on Software Developer Productivity,” systematic review and mapping study](https://doi.org/10.1145/3809494).
[^13]: [Adnan & Kuhn, “Measuring and Mitigating Debugging Effectiveness Decay in Code Language Models,” *Scientific Reports*](https://doi.org/10.1038/s41598-025-27846-5).
[^14]: [Liu et al., “Exploring the Potential of General Purpose LLMs in Automated Software Refactoring,” *Automated Software Engineering*](https://doi.org/10.1007/s10515-025-00500-0).
[^15]: [Aðalsteinsson et al., “Rethinking Code Review Workflows with LLM Assistance,” ESEM 2025](https://doi.org/10.1109/ESEM64174.2025.00013).
[^16]: [Hemmat et al., “Research Directions for Using LLM in Software Requirement Engineering,” *Frontiers in Computer Science*](https://doi.org/10.3389/fcomp.2025.1519437).
[^17]: [Paradis et al., “How Much Does AI Impact Development Speed?,” IEEE Xplore record](https://ieeexplore.ieee.org/abstract/document/11121676).
[^18]: [Peng et al., “The Impact of AI on Developer Productivity: Evidence from GitHub Copilot,” preprint](https://arxiv.org/abs/2302.06590).
[^19]: [Stray et al., “Developer Productivity With and Without GitHub Copilot,” longitudinal case study/preprint](https://arxiv.org/abs/2509.20353).
[^20]: [Mohamed, Assi & Guizani, systematic review and mapping study](https://doi.org/10.1145/3809494).
[^21]: [Dai, Xu & Tao, “Rethinking the Evaluation of Secure Code Generation,” accepted ICSE 2026 paper/preprint](https://arxiv.org/abs/2503.15554).
[^22]: [Astekin et al., “Detecting Technical Debt in Source Code Changes Using Large Language Models,” PROFES 2025](https://conf.researchr.org/details/profes-2025/profes-2025-research-papers/21/Detecting-Technical-Debt-in-Source-Code-Changes-using-Large-Language-Models).
[^23]: [Xu et al., “LiCoEval: Evaluating LLMs on License Compliance in Code Generation,” ICSE 2025](https://ieeexplore.ieee.org/document/11029777).
[^24]: [Madampe, Grundy & Arachchilage, “How Are We Doing With Using AI-Based Programming Assistants For Privacy-Related Code Generation?,” EASE 2025](https://doi.org/10.1145/3756681.375703).
[^25]: [Weisz et al., “Examining the Use and Impact of an AI Code Assistant on Developer Productivity and Experience in the Enterprise,” CHI EA 2025](https://doi.org/10.1145/3706599.3706670).
[^26]: [Fan et al., “The Impact of AI-Assisted Pair Programming,” *International Journal of STEM Education*](https://doi.org/10.1186/s40594-025-00537-3).
[^27]: [Botto-Tobar, Serebrenik & van den Brand, “AI Coding Tools in Bilingual Software Development,” FSE Companion 2025](https://doi.org/10.1145/3696630.3731670).
