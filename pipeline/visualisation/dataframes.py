from __future__ import annotations

from collections.abc import Iterable
from pathlib import Path
from urllib.parse import unquote

import pandas as pd

IDENTITY_COLUMNS = ["client", "model", "category", "problem", "run"]
PROBLEM_COLUMNS = ["category", "problem"]
MODEL_COLUMNS = ["client", "model"]
CATEGORY_ORDER = ("datastructures", "advanced_datastructures", "novel_problems")
SOKRATES_METRICS = {
    "sokrates_lines_of_code": "Lines of code",
    "sokrates_unit_count": "Unit count",
    "sokrates_unit_size_total_loc": "Total unit size (LOC)",
    "sokrates_unit_size_max_loc": "Maximum unit size (LOC)",
    "sokrates_unit_size_mean_loc": "Mean unit size (LOC)",
    "sokrates_conditional_complexity_total": "Total conditional complexity",
    "sokrates_conditional_complexity_max": "Maximum conditional complexity",
    "sokrates_conditional_complexity_mean": "Mean conditional complexity",
}
ERROR_COLUMNS = {
    "compilation": "compilation_errors",
    "codeql": "codeql_issues",
    "infer": "infer_issues",
}
THREAD_SAFETY_CATEGORY_PREFIX = "thread_safety_violation_"


def error_type_label(tool: str, error_type: str) -> str:
    if tool == "infer" and error_type.startswith(THREAD_SAFETY_CATEGORY_PREFIX):
        category = error_type.removeprefix(THREAD_SAFETY_CATEGORY_PREFIX)
        return category.replace("_", " ").replace("read write", "read/write").title()
    return error_type.replace("_", " ").capitalize()


def _issue_columns(frame: pd.DataFrame, tool: str) -> list[str]:
    return sorted(
        column for column in frame.columns
        if column.startswith(f"{tool}_")
        and column not in (f"{tool}_issues", f"{tool}_status")
    )


def _counts(values: pd.Series, column: str) -> pd.Series:
    counts = pd.to_numeric(values.replace("", pd.NA), errors="raise")
    if not (counts.isna() | ((counts >= 0) & (counts % 1 == 0))).all():
        raise ValueError(f"{column} must contain nonnegative integers or blanks")
    return counts.astype("Int64")


def _model_index(outcomes: pd.DataFrame) -> pd.MultiIndex:
    return pd.MultiIndex.from_frame(
        outcomes[MODEL_COLUMNS].drop_duplicates().sort_values(MODEL_COLUMNS),
    )


def _client_order(client: str) -> tuple[int, str]:
    priorities = {
        "copilot-tools": 0,
        "harness": 0,
        "openai": 1,
        "anthropic": 2,
    }
    return priorities.get(client, 3), client


def ordered_model_index(index: pd.MultiIndex) -> pd.MultiIndex:
    models = list(dict.fromkeys(index.tolist()))
    models.sort(key=lambda item: (*_client_order(item[0]), item[1]))
    return pd.MultiIndex.from_tuples(models, names=MODEL_COLUMNS)


def model_label(client: str, model: str) -> str:
    display_names = {
        "copilot-tools": "Harness",
        "harness": "Harness",
        "openai": "OpenAI API",
        "anthropic": "Anthropic API",
        "lm-studio": "Local (LM Studio)",
    }
    return f"{display_names.get(client, client)} / {model}"


def heatmap_model_label(client: str, model: str) -> str:
    display_names = {
        "copilot-tools": "Harness",
        "harness": "Harness",
        "openai": "OpenAI",
        "anthropic": "Anthropic",
        "lm-studio": "Local",
    }
    short_model = unquote(model).split("@", maxsplit=1)[0].rsplit("/", maxsplit=1)[-1]
    if client == "lm-studio":
        short_model = short_model.removesuffix("-instruct-mlx")
    return f"{display_names.get(client, client)}: {short_model}"


def _category_label(category: str) -> str:
    if not category:
        return "Uncategorised"
    return category.replace("_", " ").title()


def _ordered_categories(categories: Iterable[str]) -> list[str]:
    order = {category: index for index, category in enumerate(CATEGORY_ORDER)}
    return sorted(
        set(categories),
        key=lambda category: (order.get(category, len(order)), category),
    )


def _metric_columns(outcomes: pd.DataFrame, domain: str) -> list[tuple[str, str]]:
    if domain == "compilation":
        return [
            ("compilation_errors", "Compilation errors"),
        ] if "compilation_errors" in outcomes else []
    if domain == "correctness":
        return [
            ("correctness_probability", "Correctness probability"),
        ] if "correctness_probability" in outcomes else []
    if domain == "sokrates":
        return [
            (column, label)
            for column, label in SOKRATES_METRICS.items()
            if column in outcomes
        ]
    if domain not in ("codeql", "infer"):
        raise ValueError(f"Unknown metric domain: {domain}")
    columns = [
        column for column in outcomes
        if column == f"{domain}_issues"
        or column.startswith(f"{domain}_")
        and column != f"{domain}_status"
    ]
    return [
        (
            column,
            "Total findings" if column == f"{domain}_issues" else (
                error_type_label(domain, column.removeprefix(f"{domain}_")).title()
            ),
        )
        for column in columns
    ]


def _metric_problem_values(
    outcomes: pd.DataFrame,
    domain: str,
    *,
    average_runs: bool,
) -> tuple[pd.DataFrame, list[tuple[str, str]]]:
    metrics = _metric_columns(outcomes, domain)
    if not metrics:
        raise ValueError(f"No {domain} metrics to visualise")
    columns = [column for column, _ in metrics]
    selected = outcomes.loc[
        outcomes["run"].le(3) if average_runs else outcomes["run"].eq(1)
    ].copy()
    for column in columns:
        selected[column] = pd.to_numeric(selected[column], errors="coerce")
    if domain == "sokrates":
        selected.loc[selected["sokrates_status"].ne("success"), columns] = float("nan")
    elif domain == "correctness":
        selected.loc[selected["correctness_status"].ne("success"), columns] = float("nan")
    elif domain == "compilation":
        selected.loc[selected["compilation_status"].eq("missing"), columns] = float("nan")
    values = selected.groupby(
        [*MODEL_COLUMNS, *PROBLEM_COLUMNS], dropna=False, sort=False,
    )[columns].mean().reset_index()
    return values, metrics


def _problem_index(outcomes: pd.DataFrame, category: str | None) -> pd.Index:
    problems = outcomes
    if category is not None:
        problems = problems.loc[problems["category"].eq(category)]
        return pd.Index(sorted(problems["problem"].unique()), name="problem")
    rows = [
        (category_name, problem)
        for category_name in _ordered_categories(outcomes["category"])
        for problem in sorted(
            outcomes.loc[outcomes["category"].eq(category_name), "problem"].unique(),
        )
    ]
    return pd.MultiIndex.from_tuples(rows, names=PROBLEM_COLUMNS)


def metric_summary(
    outcomes: pd.DataFrame,
    domain: str,
    *,
    average_runs: bool = False,
    category: str | None = None,
) -> pd.DataFrame:
    """Summarise metrics over problems with category-grouped table columns."""
    values, metrics = _metric_problem_values(
        outcomes, domain, average_runs=average_runs,
    )
    if category is not None:
        values = values.loc[values["category"].eq(category)]
    problem_groups = values.groupby(
        [*MODEL_COLUMNS, "category"], dropna=False, sort=False,
    )[[column for column, _ in metrics]]
    grouped = (
        problem_groups.mean()
        if domain in ("sokrates", "correctness")
        else problem_groups.sum(min_count=1)
    )
    models = ordered_model_index(_model_index(outcomes))
    categories = _ordered_categories(outcomes["category"])
    if category is not None and category in categories:
        categories = [category]
    table = pd.DataFrame(index=models)
    labels = []
    for category_name in categories:
        label = _category_label(category_name)
        if category_name in grouped.index.get_level_values("category"):
            category_values = grouped.xs(
                category_name, level="category",
            ).reindex(models)
        else:
            category_values = pd.DataFrame(index=models)
        for column, metric_label in metrics:
            table[(label, metric_label)] = category_values.get(
                column, pd.Series(index=models, dtype="float64"),
            )
            labels.append((label, metric_label))
    table.columns = pd.MultiIndex.from_tuples(labels)
    if domain in ("codeql", "infer"):
        total = pd.DataFrame(
            [table.sum(axis=0, min_count=1)],
            index=pd.MultiIndex.from_tuples([("Total", "")], names=MODEL_COLUMNS),
        )
        table = pd.concat([table, total])
    return table


def metric_problem_table(
    outcomes: pd.DataFrame,
    domain: str,
    metric: str,
    *,
    average_runs: bool = False,
    category: str | None = None,
) -> pd.DataFrame:
    """Pivot one metric by problem and ordered client/model."""
    values, metrics = _metric_problem_values(
        outcomes, domain, average_runs=average_runs,
    )
    if metric not in {column for column, _ in metrics}:
        raise ValueError(f"Unknown {domain} metric: {metric}")
    if category is not None:
        values = values.loc[values["category"].eq(category)]
    models = ordered_model_index(_model_index(outcomes))
    return values.pivot(
        index=PROBLEM_COLUMNS if category is None else "problem",
        columns=MODEL_COLUMNS,
        values=metric,
    ).reindex(index=_problem_index(outcomes, category), columns=models)


def non_compilable_problem_table(
    outcomes: pd.DataFrame,
    *,
    average_runs: bool = False,
    category: str | None = None,
) -> pd.DataFrame:
    """Mark absent or non-compilable problems for the run scope in a heatmap."""
    selected = outcomes.loc[
        outcomes["run"].le(3) if average_runs else outcomes["run"].eq(1)
    ].copy()
    if category is not None:
        selected = selected.loc[selected["category"].eq(category)]
    selected["compiled"] = (
        selected["compilation_status"].eq("success")
        & selected["compilation_errors"].eq(0).fillna(False)
    )
    valid_runs = selected.groupby(
        [*MODEL_COLUMNS, *PROBLEM_COLUMNS], dropna=False, sort=False,
    )["compiled"].sum()
    required_runs = 3 if average_runs else 1
    valid = valid_runs.ge(required_runs).rename("valid").reset_index()
    models = ordered_model_index(_model_index(outcomes))
    compiled = valid.pivot(
        index=PROBLEM_COLUMNS if category is None else "problem",
        columns=MODEL_COLUMNS,
        values="valid",
    ).reindex(
        index=_problem_index(outcomes, category),
        columns=models,
    ).astype("boolean").fillna(False).astype(bool)
    return ~compiled


def load_results(csv_file: Path) -> pd.DataFrame:
    return outcomes_dataframe(pd.read_csv(csv_file, dtype=str, keep_default_na=False))


def outcomes_dataframe(results: pd.DataFrame) -> pd.DataFrame:
    """Keep the extracted measurements and add one conservative pass flag per run."""
    required = {
        *IDENTITY_COLUMNS, *ERROR_COLUMNS.values(),
        *(f"{tool}_status" for tool in ERROR_COLUMNS),
    }
    missing = required.difference(results.columns)
    if missing:
        raise ValueError(f"Missing evaluation columns: {', '.join(sorted(missing))}")
    if results.empty:
        raise ValueError("No evaluation results to visualise")
    frame = results.copy()
    for column in ("client", "model", "problem"):
        if not frame[column].map(
            lambda value: isinstance(value, str) and bool(value.strip())
        ).all():
            raise ValueError(f"{column} must contain nonblank strings")
    if not frame["category"].map(lambda value: isinstance(value, str)).all():
        raise ValueError("category must contain strings (blank for legacy results)")

    # Older, single-run generations have a blank run field.
    runs = pd.to_numeric(frame["run"].replace("", pd.NA), errors="raise").fillna(1)
    if not ((runs >= 1) & (runs % 1 == 0)).all():
        raise ValueError("run must contain positive integers")
    frame["run"] = runs.astype("int64")
    if frame.duplicated(IDENTITY_COLUMNS).any():
        raise ValueError("Duplicate client/model/category/problem/run results")

    frame["passed"] = True
    for tool, column in ERROR_COLUMNS.items():
        status = frame[f"{tool}_status"]
        if not status.isin(("success", "failed", "missing")).all():
            raise ValueError(f"Invalid {tool} status")
        counts = _counts(frame[column], column)
        if (status.eq("success") & counts.isna()).any():
            raise ValueError(f"Successful {tool} results require {column}")
        frame[column] = counts
        frame["passed"] &= status.eq("success") & counts.eq(0).fillna(False)
        if tool in ("codeql", "infer"):
            columns = _issue_columns(frame, tool)
            for name in columns:
                frame[name] = _counts(frame[name], name)
                if (status.eq("success") & frame[name].isna()).any():
                    raise ValueError(f"Successful {tool} results require {name}")
            if columns and (frame[columns].sum(axis=1) > counts).any():
                raise ValueError(f"{tool} error-type counts exceed the total issue count")
    frame["passed"] = frame["passed"].astype(bool)
    return frame.sort_values(
        ["run", *MODEL_COLUMNS, *PROBLEM_COLUMNS], ignore_index=True,
    )


def passing_rates(
    outcomes: pd.DataFrame, k_values: tuple[int, ...] = (1, 3),
) -> pd.DataFrame:
    """Count problems with any passing run numbered 1 through k, not random samples."""
    if not k_values or any(
        isinstance(k, bool) or not isinstance(k, int) or k < 1 for k in k_values
    ):
        raise ValueError("k_values must contain positive integers")
    if outcomes.empty:
        raise ValueError("No evaluation results to summarise")
    models = _model_index(outcomes)
    total = len(outcomes[PROBLEM_COLUMNS].drop_duplicates())
    summaries = []
    for k in sorted(set(k_values)):
        selected = outcomes.loc[outcomes["run"] <= k]
        per_problem = selected.groupby(
            [*MODEL_COLUMNS, *PROBLEM_COLUMNS], dropna=False,
        )["passed"].any()
        passed = per_problem.groupby(level=MODEL_COLUMNS).sum().reindex(models, fill_value=0)
        observed = selected.groupby(MODEL_COLUMNS).size().reindex(models, fill_value=0)
        summary = passed.rename("passed").reset_index()
        summary["k"] = k
        summary["total"] = total
        summary["pass_rate"] = summary["passed"] / total
        summary["observed_attempts"] = observed.to_numpy()
        summary["expected_attempts"] = total * k
        summaries.append(summary)
    return pd.concat(summaries, ignore_index=True)


def passing_rate_table(summary: pd.DataFrame) -> pd.DataFrame:
    """Format counts for tables in the shared client/model order."""
    rates = summary.pivot(index=MODEL_COLUMNS, columns="k", values="pass_rate")
    rates = rates.reindex(
        index=ordered_model_index(rates.index), columns=sorted(rates.columns),
    )
    formatted = summary.copy()
    formatted["value"] = [
        f"{row.passed}/{row.total} ({row.pass_rate:.2%})"
        for row in summary.itertuples(index=False)
    ]
    return formatted.pivot(
        index=MODEL_COLUMNS, columns="k", values="value",
    ).reindex(index=rates.index, columns=rates.columns)


def error_counts(outcomes: pd.DataFrame, run: int = 1) -> pd.DataFrame:
    """Sum findings by type for one run, retaining analysis coverage per model."""
    if isinstance(run, bool) or not isinstance(run, int) or run < 1:
        raise ValueError("run must be a positive integer")
    if outcomes.empty:
        raise ValueError("No evaluation results to summarise")
    models = _model_index(outcomes)
    selected = outcomes.loc[outcomes["run"] == run]
    expected = len(outcomes[PROBLEM_COLUMNS].drop_duplicates())
    summaries = []
    for tool, total_column in ERROR_COLUMNS.items():
        if tool == "compilation":
            measurements = {"errors": outcomes[total_column]}
        else:
            columns = _issue_columns(outcomes, tool)
            measurements = {
                column.removeprefix(f"{tool}_"): outcomes[column] for column in columns
            }
            remainder = (outcomes[total_column] - outcomes[columns].sum(axis=1)).astype("Int64")
            if remainder.gt(0).any():
                measurements["unclassified"] = remainder
            elif not columns:
                measurements["all_findings"] = outcomes[total_column]
        for error_type, counts in measurements.items():
            valid = (
                selected[f"{tool}_status"].isin(("success", "failed"))
                if tool == "compilation" else selected[f"{tool}_status"].eq("success")
            )
            values = selected[MODEL_COLUMNS].copy()
            values["count"] = counts.loc[selected.index].where(valid)
            grouped = values.groupby(MODEL_COLUMNS)["count"]
            summary = grouped.sum(min_count=1).reindex(models).rename("count").reset_index()
            summary["count"] = summary["count"].astype("Int64")
            summary["tool"] = tool
            summary["error_type"] = error_type
            summary["run"] = run
            summary["observed_attempts"] = grouped.count().reindex(models, fill_value=0).to_numpy()
            summary["expected_attempts"] = expected
            summaries.append(summary)
    return pd.concat(summaries, ignore_index=True)


def average_error_counts(outcomes: pd.DataFrame) -> pd.DataFrame:
    """Average per-run totals, skipping unavailable counts for runs 1 through 3."""
    summaries = pd.concat(
        [error_counts(outcomes, run) for run in (1, 2, 3)],
        ignore_index=True,
    )
    grouped = summaries.groupby(
        [*MODEL_COLUMNS, "tool", "error_type"], dropna=False, sort=False,
    )
    averaged = grouped["count"].mean().rename("count").reset_index()
    averaged["observed_attempts"] = (
        grouped["observed_attempts"].sum().to_numpy()
    )
    averaged["expected_attempts"] = grouped["expected_attempts"].sum().to_numpy()
    averaged["run"] = "avg_runs_1_3"
    return averaged


def category_error_rates(
    outcomes: pd.DataFrame,
    tool: str,
    *,
    average_runs: bool = False,
) -> pd.DataFrame:
    """Summarise findings and analyzed-program rates by model and problem category."""
    if tool not in ("codeql", "infer"):
        raise ValueError("tool must be 'codeql' or 'infer'")
    selected = outcomes.loc[
        outcomes["run"].le(3) if average_runs else outcomes["run"].eq(1)
    ].copy()
    analyzed = selected[f"{tool}_status"].eq("success")
    selected["findings"] = pd.to_numeric(
        selected[f"{tool}_issues"], errors="coerce",
    ).where(analyzed)
    selected["analyzed"] = analyzed.astype("int64")
    per_run = selected.groupby(
        [*MODEL_COLUMNS, "category", "run"], dropna=False, sort=False,
    ).agg(
        findings=("findings", lambda counts: counts.sum(min_count=1)),
        analyzed=("analyzed", "sum"),
    ).reset_index()
    models = ordered_model_index(_model_index(outcomes))
    categories = _ordered_categories(outcomes["category"])
    expected_by_category = {
        category: outcomes.loc[outcomes["category"].eq(category), "problem"].nunique()
        for category in categories
    }
    expected_runs = 3 if average_runs else 1
    rows = []
    for client, model in models:
        for category in categories:
            matches = per_run.loc[
                per_run["client"].eq(client)
                & per_run["model"].eq(model)
                & per_run["category"].eq(category)
            ]
            if average_runs:
                used = matches.loc[matches["analyzed"].gt(0)]
            else:
                used = matches.loc[
                    matches["run"].eq(1) & matches["analyzed"].gt(0)
                ]
            if used.empty:
                findings = float("nan")
                analyzed_programs = 0.0
                runs_used = 0
            else:
                findings = used["findings"].mean()
                analyzed_programs = used["analyzed"].mean()
                runs_used = len(used)
            rate = findings / analyzed_programs if analyzed_programs else float("nan")
            rows.append({
                "client": client,
                "model": model,
                "category": category,
                "findings": findings,
                "analyzed": analyzed_programs,
                "expected_programs": expected_by_category[category],
                "rate": rate,
                "runs_used": runs_used,
                "expected_runs": expected_runs,
            })
    return pd.DataFrame(rows)


def error_count_table(summary: pd.DataFrame) -> pd.DataFrame:
    """Format grouped error columns and a totals row; flag partial counts with *."""
    totals = summary.groupby(["tool", "error_type"], sort=False).agg(
        count=("count", lambda values: values.sum(min_count=1)),
        observed_attempts=("observed_attempts", "sum"),
        expected_attempts=("expected_attempts", "sum"),
    ).reset_index()
    totals["client"] = "Total"
    totals["model"] = ""
    formatted = pd.concat([summary, totals], ignore_index=True)
    formatted["value"] = [
        "N/A" if pd.isna(row.count) else (
            (str(int(row.count)) if float(row.count).is_integer() else f"{row.count:.2f}")
            + ("*" if row.observed_attempts < row.expected_attempts else "")
        )
        for row in formatted.itertuples(index=False)
    ]
    columns = pd.MultiIndex.from_frame(summary[["tool", "error_type"]].drop_duplicates())
    models = ordered_model_index(
        pd.MultiIndex.from_frame(summary[MODEL_COLUMNS].drop_duplicates()),
    )
    table = formatted.pivot(
        index=MODEL_COLUMNS, columns=["tool", "error_type"], values="value",
    )
    return table.reindex(
        index=models.append(pd.MultiIndex.from_tuples([("Total", "")], names=MODEL_COLUMNS)),
        columns=columns,
    )
