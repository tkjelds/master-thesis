# master-thesis
Concurrency analysis of LLM-generated code. 

## Prompt generator

`prompt_generator` is a dependency-free Python tool for producing prompt
datasets from a template and CSV files.

Create `prompt_generator/template.txt`, for example:

```text
Generate a Java file where you have implemented the data structure $DATASTRUCTURE using locks. Do not use any libraries.
```

Each CSV can contain one value per row, or a named column matching its
wildcard. Run the generator from the repository root:

```text
python -m prompt_generator.generator prompt_generator/template.txt prompts.json --data DATASTRUCTURE=prompt_generator/data_structures.csv
```

For multiple wildcards, repeat `--data`:

```text
python -m prompt_generator.generator prompt_generator/template.txt prompts.json --data LANGUAGE=prompt_generator/languages.csv --data DATASTRUCTURE=prompt_generator/data_structures.csv
```

The JSON output contains the original template, wildcard names, generated
prompt count, and each prompt together with the values used to create it.

Run the tests with:

```text
python -m unittest discover -s prompt_generator/tests
```
