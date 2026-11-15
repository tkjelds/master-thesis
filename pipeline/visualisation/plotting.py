from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from textwrap import fill

import pandas as pd
import seaborn as sns
from matplotlib.colors import PowerNorm
from matplotlib.figure import Figure

from pipeline.visualisation.dataframes import (
    CATEGORY_ORDER,
    average_error_counts,
    category_error_rates,
    error_count_table,
    error_counts,
    error_type_label,
    heatmap_model_label,
    load_results,
    metric_problem_table,
    metric_summary,
    model_label,
    non_compilable_problem_table,
    ordered_model_index,
    passing_rate_table,
    passing_rates,
)


def _plot_grouped_table(
    table: pd.DataFrame, *, title: str = "", footer: str = "",
) -> Figure:
    labels = [
        model_label(client, model) if model else client
        for client, model in table.index
    ]
    headers = [fill(str(label), width=16) for _, label in table.columns]
    column_widths = [
        max(1.7, 0.11 * max(
            *(len(line) for line in header.splitlines()),
            *(len(str(value)) for value in table.iloc[:, index]),
            len(str(table.columns[index][0])),
        ) + 0.5)
        for index, header in enumerate(headers)
    ]
    label_width = max(5.5, max(map(len, labels)) * 0.11)
    width = label_width + sum(column_widths)
    footer_height = 0.45 if footer else 0
    height = 1.6 + 0.38 * len(table) + footer_height + (0.5 if title else 0)
    figure = Figure(figsize=(width, height), layout="constrained")
    axes = figure.subplots()
    axes.set_axis_off()
    axes.set_xlim(0, width)
    axes.set_ylim(0, height)
    font = {"fontfamily": "serif", "fontsize": 12}
    top = height - (0.55 if title else 0.05)
    if title:
        axes.text(width / 2, height - 0.2, title, ha="center", **font)
    axes.text(0.1, top - 0.65, "Client / Model", weight="bold", **font)
    positions = [label_width]
    for column_width in column_widths:
        positions.append(positions[-1] + column_width)
    groups = table.columns.get_level_values(0)
    for group in dict.fromkeys(groups):
        indexes = [index for index, value in enumerate(groups) if value == group]
        left, right = positions[indexes[0]], positions[indexes[-1] + 1]
        axes.text(
            (left + right) / 2, top - 0.3, group,
            ha="center", weight="bold", **font,
        )
        axes.hlines(top - 0.45, left + 0.05, right - 0.05, colors="black", linewidth=0.6)
    for index, header in enumerate(headers):
        x = (positions[index] + positions[index + 1]) / 2
        axes.text(
            x, top - 0.76, header, ha="center", va="center", weight="bold", **font,
        )
    for index, (label, values) in enumerate(zip(labels, table.to_numpy(), strict=True)):
        y = top - 1.35 - 0.38 * index
        weight = "bold" if table.index[index] == ("Total", "") else "normal"
        if weight == "bold":
            axes.hlines(y + 0.2, 0, width, colors="black", linewidth=0.6)
        axes.text(0.1, y, label, va="center", weight=weight, **font)
        for column, value in enumerate(values):
            axes.text(
                (positions[column] + positions[column + 1]) / 2, y, value,
                ha="center", va="center", weight=weight, **font,
            )
    axes.hlines(
        [top, top - 1.1, footer_height + 0.08], 0, width, colors="black", linewidth=1,
    )
    if footer:
        axes.text(0.1, 0.12, footer, fontfamily="serif", fontsize=10)
    return figure


def plot_passing_rate_table(summary: pd.DataFrame) -> Figure:
    table = passing_rate_table(summary)
    table.columns = pd.MultiIndex.from_tuples([
        ("Passing Rate", f"k={k}") for k in table.columns
    ])
    return _plot_grouped_table(table)


def plot_error_table(summary: pd.DataFrame) -> Figure:
    table = error_count_table(summary)
    names = {"compilation": "Compilation", "codeql": "CodeQL", "infer": "Infer"}
    table.columns = pd.MultiIndex.from_tuples([
        (names[tool], error_type_label(tool, error_type))
        for tool, error_type in table.columns
    ])
    run_number = summary["run"].iloc[0]
    title = (
        "Summary of errors in generated code (average over runs 1-3)"
        if run_number == "avg_runs_1_3"
        else f"Summary of errors in generated code (run {run_number})"
    )
    return _plot_grouped_table(
        table,
        title=title,
        footer=(
            "Counts are findings; compilation counts failed programs. "
            "* Partial results; N/A = no evaluated attempts."
        ),
    )


def plot_passing_rate_heatmap(summary: pd.DataFrame) -> Figure:
    table = passing_rate_table(summary)
    rates = summary.pivot(index=["client", "model"], columns="k", values="pass_rate")
    rates = rates.reindex(index=table.index, columns=table.columns)
    with sns.axes_style("white"), sns.plotting_context("paper", font_scale=1.2):
        figure = Figure(
            figsize=(max(9, 3 * len(table.columns) + 5), max(2.8, 0.55 * len(table) + 1.5)),
            layout="constrained",
        )
        axes = figure.subplots()
        sns.heatmap(
            rates * 100, annot=table.to_numpy(), fmt="", cmap="Blues",
            vmin=0, vmax=100, linewidths=0.5,
            cbar_kws={"label": "Passing rate (%)"}, ax=axes,
            yticklabels=[
                heatmap_model_label(client, model) for client, model in table.index
            ],
            xticklabels=[f"k={k}" for k in table.columns],
        )
        axes.set(title="Passing rate over the first k runs", xlabel="", ylabel="")
        axes.tick_params(axis="both", rotation=0)
    return figure


def _format_metric(value: object) -> str:
    if pd.isna(value):
        return "N/A"
    number = float(value)
    return f"{number:.2f}" if not number.is_integer() else f"{int(number)}"


def _metric_title(domain: str, metric: str) -> str:
    if domain == "compilation":
        return "Compilation errors"
    if domain == "correctness":
        return "Correctness probability"
    if domain == "sokrates":
        return {
            "sokrates_lines_of_code": "Lines of code",
            "sokrates_unit_count": "Unit count",
            "sokrates_unit_size_total_loc": "Total unit size (LOC)",
            "sokrates_unit_size_max_loc": "Maximum unit size (LOC)",
            "sokrates_unit_size_mean_loc": "Mean unit size (LOC)",
            "sokrates_conditional_complexity_total": "Total conditional complexity",
            "sokrates_conditional_complexity_max": "Maximum conditional complexity",
            "sokrates_conditional_complexity_mean": "Mean conditional complexity",
        }[metric]
    if metric.endswith("_issues"):
        return "Total findings"
    return error_type_label(
        domain, metric.removeprefix(f"{domain}_"),
    ).title()


def _metric_plot_name(domain: str) -> str:
    return {
        "compilation": "compilation_errors",
        "correctness": "correctness",
        "sokrates": "sokrates_quality",
        "codeql": "codeql_errors",
        "infer": "infer_errors",
    }[domain]


def _run_label(average_runs: bool) -> str:
    return "average of runs 1-3" if average_runs else "run 1"


def _run_slug(average_runs: bool) -> str:
    return "avg_runs_1_3" if average_runs else "run_1"


def _figure_directory(output_dir: Path, name: str) -> Path:
    if name.startswith("passing_rates"):
        kind = "heatmaps" if "heatmap" in name else "tables"
        group = "passing_rates"
    elif name.startswith("error_counts"):
        kind, group = "tables", "error_counts"
    elif name.startswith("combined_error_rates"):
        kind, group = "tables", "error_rates"
    else:
        metric_groups = (
            ("compilation_errors", "compilation"),
            ("correctness", "correctness"),
            ("sokrates_quality", "sokrates"),
            ("codeql_errors", "codeql"),
            ("infer_errors", "infer"),
        )
        match = next(
            ((prefix, group) for prefix, group in metric_groups
             if name.startswith(prefix)),
            None,
        )
        if match is None:
            return output_dir
        _, group = match
        kind = (
            "bar_charts" if "_bars_" in name
            else "heatmaps" if "heatmap" in name
            else "tables"
        )
    return output_dir / kind / group


def _move_legacy_figures(output_dir: Path) -> None:
    for path in output_dir.iterdir():
        if not path.is_file() or path.suffix not in (".png", ".pdf"):
            continue
        destination_dir = _figure_directory(output_dir, path.stem)
        if destination_dir == output_dir:
            continue
        destination_dir.mkdir(parents=True, exist_ok=True)
        path.replace(destination_dir / path.name)


def plot_metric_table(
    summary: pd.DataFrame, *, domain: str, average_runs: bool,
    category: str | None = None,
) -> Figure:
    table = summary.copy().map(_format_metric)
    category_title = f" — {category.replace('_', ' ').title()}" if category else ""
    return _plot_grouped_table(
        table,
        title=f"{_metric_plot_name(domain).replace('_', ' ').title()}: "
        f"{_run_label(average_runs)}{category_title}",
    )


def plot_category_error_rate_table(
    summary: pd.DataFrame,
    *,
    tool: str,
    average_runs: bool,
) -> Figure:
    return _plot_category_error_rate_table(
        {tool: summary}, average_runs=average_runs,
    )


def plot_combined_category_error_rate_table(
    summaries: dict[str, pd.DataFrame],
    *,
    average_runs: bool,
) -> Figure:
    return _plot_category_error_rate_table(summaries, average_runs=average_runs)


def _plot_category_error_rate_table(
    summaries: dict[str, pd.DataFrame],
    *,
    average_runs: bool,
) -> Figure:
    model_rows = pd.concat(
        [summary[["client", "model"]] for summary in summaries.values()],
        ignore_index=True,
    ).drop_duplicates()
    models = ordered_model_index(pd.MultiIndex.from_frame(model_rows))
    all_categories = set().union(
        *(set(summary["category"]) for summary in summaries.values()),
    )
    categories = [
        category for category in CATEGORY_ORDER
        if category in all_categories
    ]
    categories.extend(sorted(all_categories - set(categories)))
    table = pd.DataFrame(index=models)
    total_values = {}
    for tool, summary in summaries.items():
        for category in categories:
            category_label = category.replace("_", " ").title() or "Uncategorised"
            group_label = (
                f"{category_label} / {tool.title()}"
                if len(summaries) > 1 else category_label
            )
            category_data = summary.loc[
                summary["category"].eq(category)
            ].set_index(["client", "model"]).reindex(models)
            expected = category_data["expected_programs"].fillna(0)
            analyzed = category_data["analyzed"].fillna(0)
            runs_used = category_data["runs_used"].fillna(0)
            expected_runs = category_data["expected_runs"].fillna(0)
            partial = analyzed.lt(expected) | runs_used.lt(expected_runs)

            findings = category_data["findings"]
            table[(group_label, "Total findings")] = [
                "N/A" if pd.isna(value) else (
                    _format_metric(value) + ("*" if partial.iloc[index] else "")
                )
                for index, value in enumerate(findings)
            ]
            table[(group_label, "Analyzed")] = [
                f"{_format_metric(value)}/{int(expected.iloc[index])}"
                + (
                    f" ({int(runs_used.iloc[index])}/{int(expected_runs.iloc[index])} runs)"
                    if average_runs else ""
                )
                + ("*" if partial.iloc[index] else "")
                for index, value in enumerate(analyzed)
            ]
            table[(group_label, "Findings / analyzed")] = [
                "N/A" if pd.isna(value) else (
                    f"{value:.2f}" + ("*" if partial.iloc[index] else "")
                )
                for index, value in enumerate(category_data["rate"])
            ]

            total_findings = findings.sum(min_count=1)
            total_analyzed = analyzed.sum()
            total_expected = expected.sum()
            total_runs_used = runs_used.sum()
            total_expected_runs = expected_runs.sum()
            total_partial = (
                total_analyzed < total_expected
                or total_runs_used < total_expected_runs
            )
            total_values[(group_label, "Total findings")] = (
                "N/A" if pd.isna(total_findings) else (
                    _format_metric(total_findings) + ("*" if total_partial else "")
                )
            )
            total_values[(group_label, "Analyzed")] = (
                f"{_format_metric(total_analyzed)}/{int(total_expected)}"
                + (
                    f" ({int(total_runs_used)}/{int(total_expected_runs)} runs)"
                    if average_runs else ""
                )
                + ("*" if total_partial else "")
            )
            total_rate = (
                total_findings / total_analyzed if total_analyzed else float("nan")
            )
            total_values[(group_label, "Findings / analyzed")] = (
                "N/A" if pd.isna(total_rate) else (
                    f"{total_rate:.2f}" + ("*" if total_partial else "")
                )
            )
    total_index = pd.MultiIndex.from_tuples([("Total", "")], names=("client", "model"))
    table = pd.concat([
        table,
        pd.DataFrame([total_values], index=total_index),
    ])
    table.columns = pd.MultiIndex.from_tuples(table.columns)
    run_name = "average over runs 1-3" if average_runs else "run 1"
    tools = " and ".join(tool.title() for tool in summaries)
    title = f"{tools} findings per analyzed program ({run_name})"
    footer = (
        "Rates are findings divided by successful analyses. "
        "* Partial coverage; average values use available runs."
    )
    return _plot_grouped_table(table, title=title, footer=footer)


def plot_metric_heatmap(
    matrix: pd.DataFrame,
    *,
    domain: str,
    metric: str,
    average_runs: bool,
    category: str | None = None,
    non_compilable: pd.DataFrame | None = None,
) -> Figure:
    matrix = matrix.astype("float64")
    labels = matrix.map(_format_metric)
    if non_compilable is None:
        non_compilable = pd.DataFrame(
            False, index=matrix.index, columns=matrix.columns,
        )
    else:
        non_compilable = non_compilable.reindex(
            index=matrix.index, columns=matrix.columns, fill_value=True,
        ).fillna(True)
    labels = labels.mask(non_compilable, "X")
    heatmap_values = matrix.mask(non_compilable, 0).fillna(0)
    mask = matrix.isna() & ~non_compilable
    category_title = f" — {category.replace('_', ' ').title()}" if category else ""
    if isinstance(matrix.index, pd.MultiIndex):
        yticklabels = [
            f"{category_name.replace('_', ' ').title()} / {problem}"
            for category_name, problem in matrix.index
        ]
    else:
        yticklabels = matrix.index.tolist()
    height = max(3.5, 0.32 * len(matrix) + 1.5)
    width = max(8, 1.3 * len(matrix.columns) + 3)
    with sns.axes_style("white"), sns.plotting_context("paper", font_scale=1.1):
        figure = Figure(figsize=(width, height), layout="constrained")
        axes = figure.subplots()
        maximum = heatmap_values.max().max() if not heatmap_values.empty else 0
        if matrix.empty:
            axes.text(
                0.5, 0.5, "No metric results available",
                ha="center", va="center", transform=axes.transAxes,
            )
            axes.set_axis_off()
        else:
            sns.heatmap(
                heatmap_values, annot=labels, fmt="", cmap="Blues", mask=mask,
                norm=PowerNorm(
                    gamma=0.5, vmin=0, vmax=maximum if maximum > 0 else 1,
                ),
                linewidths=0.35,
                cbar_kws={"label": _metric_title(domain, metric)}, ax=axes,
                xticklabels=[
                    heatmap_model_label(client, model)
                    for client, model in matrix.columns
                ],
                yticklabels=yticklabels,
            )
            axes.tick_params(axis="x", rotation=45)
            axes.tick_params(axis="y", rotation=0)
        axes.set(
            title=f"{_metric_title(domain, metric)} — {_run_label(average_runs)}"
            f"{category_title}",
            xlabel="", ylabel="",
        )
    return figure


def plot_metric_bar_panels(
    matrix: pd.DataFrame,
    *,
    domain: str,
    metric: str,
    average_runs: bool,
    panel: str,
) -> Figure:
    if panel not in ("problem", "category"):
        raise ValueError("panel must be 'problem' or 'category'")
    if isinstance(matrix.index, pd.MultiIndex):
        labels = [
            f"{category.replace('_', ' ').title()} / {problem}"
            for category, problem in matrix.index
        ]
    else:
        labels = [
            (
                "Uncategorised" if value == ""
                else str(value).replace("_", " ").title()
            )
            for value in matrix.index
        ]
    if matrix.empty:
        labels = ["No results"]
    panel_columns = min(3, max(1, len(labels)))
    panel_rows = (len(labels) + panel_columns - 1) // panel_columns
    with sns.axes_style("white"), sns.plotting_context("paper", font_scale=1.1):
        figure = Figure(
            figsize=(max(5.5, 5 * panel_columns), max(3.2, 3.2 * panel_rows)),
            layout="constrained",
        )
        axes_grid = figure.subplots(
            panel_rows, panel_columns, squeeze=False, sharey=True,
        )
        model_labels = [
            heatmap_model_label(client, model) for client, model in matrix.columns
        ]
        colors = sns.color_palette("deep", n_colors=max(1, len(matrix.columns)))
        for index, (axis, label) in enumerate(
            zip(axes_grid.flat, labels, strict=False),
        ):
            if not matrix.empty:
                values = pd.to_numeric(
                    matrix.iloc[index], errors="coerce",
                ).to_numpy(dtype=float)
                axis.bar(range(len(values)), values, color=colors[:len(values)])
                axis.set_xticks(range(len(model_labels)))
                axis.set_xticklabels(
                    model_labels, rotation=45, ha="right", fontsize=7,
                )
                axis.grid(axis="y", alpha=0.25)
                axis.set_axisbelow(True)
            axis.set_title(label)
            axis.set_ylabel(_metric_title(domain, metric))
        for axis in axes_grid.flat[len(labels):]:
            axis.set_visible(False)
        figure.suptitle(
            f"{_metric_title(domain, metric)} by {_run_label(average_runs)} "
            f"({panel} panels)",
        )
    return figure


def _save_figure(figure: Figure, output_dir: Path, name: str) -> None:
    destination_dir = _figure_directory(output_dir, name)
    destination_dir.mkdir(parents=True, exist_ok=True)
    for extension in ("png", "pdf"):
        figure.savefig(destination_dir / f"{name}.{extension}", dpi=200)
    figure.clear()


def run(errors_file: Path) -> Path:
    outcomes = load_results(errors_file)
    summary = passing_rates(outcomes)
    errors = error_counts(outcomes)
    average_errors = average_error_counts(outcomes)
    for row in summary.itertuples(index=False):
        if row.observed_attempts < row.expected_attempts:
            print(
                f"Warning: {row.client}/{row.model}: k={row.k} has "
                f"{row.observed_attempts}/{row.expected_attempts} attempts; "
                "absent attempts cannot pass",
                file=sys.stderr,
            )
    partial = errors.loc[
        errors["observed_attempts"] < errors["expected_attempts"]
    ].drop_duplicates(["client", "model", "tool"])
    for row in partial.itertuples(index=False):
        print(
            f"Warning: {row.client}/{row.model}: run 1 {row.tool} has "
            f"{row.observed_attempts}/{row.expected_attempts} evaluated attempts; "
            "error counts are incomplete",
            file=sys.stderr,
        )
    unclassified = errors.loc[
        errors["error_type"].eq("unclassified") & errors["count"].gt(0)
    ]
    for row in unclassified.itertuples(index=False):
        print(
            f"Warning: {row.client}/{row.model}: {row.count} unclassified "
            f"{row.tool} findings; re-extract reports for individual error types",
            file=sys.stderr,
        )
    output_dir = errors_file.parent
    _move_legacy_figures(output_dir)
    outcomes.to_csv(output_dir / "outcomes.csv", index=False)
    summary.to_csv(output_dir / "passing_rates.csv", index=False)
    errors.to_csv(output_dir / "error_counts_run_1.csv", index=False)
    average_errors.to_csv(output_dir / "error_counts_avg_runs_1_3.csv", index=False)
    for name, plot, data in (
        ("passing_rates_table", plot_passing_rate_table, summary),
        ("passing_rates_heatmap", plot_passing_rate_heatmap, summary),
        ("error_counts_run_1_table", plot_error_table, errors),
        ("error_counts_avg_runs_1_3_table", plot_error_table, average_errors),
    ):
        figure = plot(data)
        _save_figure(figure, output_dir, name)

    for average_runs in (False, True):
        run_slug = _run_slug(average_runs)
        rate_summaries = {}
        for tool in ("codeql", "infer"):
            rates = category_error_rates(
                outcomes, tool, average_runs=average_runs,
            )
            rate_summaries[tool] = rates
            rates.to_csv(
                output_dir / f"{tool}_error_rates_{run_slug}.csv", index=False,
            )
            _save_figure(
                plot_category_error_rate_table(
                    rates, tool=tool, average_runs=average_runs,
                ),
                output_dir,
                f"{tool}_errors_rate_table_{run_slug}",
            )
        combined_rates = rate_summaries["codeql"].merge(
            rate_summaries["infer"],
            on=["client", "model", "category"],
            suffixes=("_codeql", "_infer"),
            validate="one_to_one",
        )
        combined_rates.to_csv(
            output_dir / f"combined_error_rates_{run_slug}.csv", index=False,
        )
        _save_figure(
            plot_combined_category_error_rate_table(
                rate_summaries, average_runs=average_runs,
            ),
            output_dir,
            f"combined_error_rates_table_{run_slug}",
        )

    categories = [
        category for category in CATEGORY_ORDER
        if outcomes["category"].eq(category).any()
    ]
    categories.extend(sorted(set(outcomes["category"]) - set(categories)))
    scopes = [(None, None), *((category, category) for category in categories)]
    metric_columns_by_domain = {
        "compilation": ["compilation_errors"],
        "correctness": ["correctness_probability"],
        "sokrates": [
            column for column in outcomes
            if column.startswith("sokrates_") and column != "sokrates_status"
        ],
        "codeql": [
            column for column in outcomes
            if column == "codeql_issues"
            or column.startswith("codeql_") and column != "codeql_status"
        ],
        "infer": [
            column for column in outcomes
            if column == "infer_issues"
            or column.startswith("infer_") and column != "infer_status"
        ],
    }
    domains = [
        domain for domain, columns in metric_columns_by_domain.items()
        if any(column in outcomes for column in columns)
    ]
    for domain in domains:
        for average_runs in (False, True):
            run_slug = _run_slug(average_runs)
            for category, category_slug in scopes:
                table = metric_summary(
                    outcomes, domain, average_runs=average_runs, category=category,
                )
                suffix = f"_{category_slug}" if category_slug else ""
                _save_figure(
                    plot_metric_table(
                        table, domain=domain, average_runs=average_runs,
                        category=category,
                    ),
                    output_dir,
                    f"{_metric_plot_name(domain)}_table_{run_slug}{suffix}",
                )

                metric_columns = metric_columns_by_domain[domain]
                non_compilable = non_compilable_problem_table(
                    outcomes, average_runs=average_runs, category=category,
                )
                for column in metric_columns:
                    matrix = metric_problem_table(
                        outcomes, domain, column,
                        average_runs=average_runs, category=category,
                    )
                    if category is None:
                        metric_slug = re.sub(
                            r"[^a-z0-9]+", "_",
                            column.removeprefix(f"{domain}_").lower(),
                        ).strip("_")
                        _save_figure(
                            plot_metric_bar_panels(
                                matrix, domain=domain, metric=column,
                                average_runs=average_runs, panel="problem",
                            ),
                            output_dir,
                            f"{_metric_plot_name(domain)}_bars_by_problem_"
                            f"{metric_slug}_{run_slug}",
                        )
                        category_title = _metric_title(domain, column)
                        category_matrix = pd.DataFrame(
                            index=pd.Index(categories, name="category"),
                            columns=matrix.columns,
                            dtype="float64",
                        )
                        for category_name in categories:
                            label = (
                                category_name.replace("_", " ").title()
                                if category_name else "Uncategorised"
                            )
                            summary_column = (label, category_title)
                            if summary_column in table.columns:
                                category_matrix.loc[category_name] = (
                                    table.loc[
                                        matrix.columns,
                                        summary_column,
                                    ].to_numpy()
                                )
                        _save_figure(
                            plot_metric_bar_panels(
                                category_matrix, domain=domain, metric=column,
                                average_runs=average_runs, panel="category",
                            ),
                            output_dir,
                            f"{_metric_plot_name(domain)}_bars_by_category_"
                            f"{metric_slug}_{run_slug}",
                        )
                    metric_slug = re.sub(
                        r"[^a-z0-9]+", "_",
                        column.removeprefix(f"{domain}_").lower(),
                    ).strip("_")
                    _save_figure(
                        plot_metric_heatmap(
                            matrix, domain=domain, metric=column,
                            average_runs=average_runs, category=category,
                            non_compilable=(
                                None if domain == "compilation" else non_compilable
                            ),
                        ),
                        output_dir,
                        f"{_metric_plot_name(domain)}_heatmap_{metric_slug}_"
                        f"{run_slug}{suffix}",
                    )
    return output_dir


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Build evaluation summaries and metric figures from errors.csv.",
    )
    parser.add_argument("errors_file", type=Path, help="Extracted per-problem CSV.")
    args = parser.parse_args(argv)
    try:
        output = run(args.errors_file)
    except (OSError, RuntimeError, ValueError) as error:
        print(f"Plotting failed: {error}", file=sys.stderr)
        return 1
    print(
        "Wrote passing-rate, error-count, and category-error-rate CSVs plus "
        f"table, metric, and bar-chart PNGs and PDFs in {output}",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
