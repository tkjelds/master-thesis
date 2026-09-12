---
applyTo: "**/*.tex,**/*.bib,.github/workflows/latex.yml"
---

# LaTeX thesis instructions

## Build commands

- Build the thesis from the repository root with
  `latexmk -pdf -interaction=nonstopmode -halt-on-error main.tex`.
- Build the preliminary problem statement separately with
  `latexmk -pdf -interaction=nonstopmode -halt-on-error preliminary_problem_statement.tex`.
- Both documents use `biblatex` with the Biber backend. The two builds are
  mirrored by `.github/workflows/latex.yml`.

## Document architecture

- `main.tex` is the thesis entry point. It loads shared thesis packages and
  `bibliography.bib` through `preamble.tex`, then explicitly includes chapter
  files with `\input`.
- A file under `chapters/` is not part of the thesis until it is referenced by
  `main.tex`.
- `preliminary_problem_statement.tex` is an independent document. It uses
  `preamble_PPS.tex` and `bibliography_PPS.bib`; keep its bibliography and
  preamble separate from the thesis equivalents.

## Conventions

- Add shared thesis packages and bibliography configuration to `preamble.tex`;
  keep document structure and chapter inclusion in `main.tex`.
- LaTeX build products are generated artifacts covered by `.gitignore`. Edit
  `.tex` and `.bib` sources rather than generated PDFs or auxiliary files.
## Writi

