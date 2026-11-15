import io
import tempfile
import unittest
from contextlib import redirect_stderr
from pathlib import Path
from unittest.mock import patch

import pandas as pd
from matplotlib.colors import PowerNorm

from pipeline.visualisation.dataframes import (
    error_counts,
    heatmap_model_label,
    outcomes_dataframe,
)
from pipeline.visualisation.plotting import (
    main,
    plot_error_table,
    plot_metric_bar_panels,
    plot_metric_heatmap,
    run,
)
from pipeline.visualisation.tests.test_dataframes import result


class PlottingTests(unittest.TestCase):
    def test_heatmap_model_labels_are_compact(self):
        self.assertEqual(
            heatmap_model_label("copilot-tools", "claude-sonnet-5.5"),
            "Harness: claude-sonnet-5.5",
        )
        self.assertEqual(
            heatmap_model_label(
                "lm-studio", "google%2Fgemma-4-e4b@reasoning-off",
            ),
            "Local: gemma-4-e4b",
        )

    def test_error_table_labels_thread_safety_qualifier_categories(self):
        outcomes = outcomes_dataframe(pd.DataFrame([
            result(
                infer_issues=2,
                infer_thread_safety_violation_read_write_race=1,
                infer_thread_safety_violation_unprotected_write=1,
            ),
        ]))
        figure = plot_error_table(error_counts(outcomes))
        self.addCleanup(figure.clear)
        labels = [text.get_text() for text in figure.axes[0].texts]
        self.assertIn("Read/Write Race", labels)
        self.assertIn("Unprotected Write", [label.replace("\n", " ") for label in labels])

    def test_metric_heatmap_enhances_low_values_with_power_normalization(self):
        index = pd.Index(["counter"], name="problem")
        columns = pd.MultiIndex.from_tuples(
            [("client", "zero"), ("client", "typical"), ("client", "outlier")],
            names=("client", "model"),
        )
        values = pd.DataFrame([[0, 4, 30]], index=index, columns=columns)
        figure = plot_metric_heatmap(
            values, domain="codeql", metric="codeql_issues", average_runs=False,
        )
        self.addCleanup(figure.clear)
        norm = figure.axes[0].collections[0].norm
        self.assertIsInstance(norm, PowerNorm)
        self.assertGreater(float(norm(4)), 4 / 30)

    def test_metric_heatmap_marks_non_compilable_cells_with_x(self):
        index = pd.MultiIndex.from_tuples(
            [("datastructures", "counter")], names=("category", "problem"),
        )
        columns = pd.MultiIndex.from_tuples(
            [("client", "missing"), ("client", "compiled")],
            names=("client", "model"),
        )
        values = pd.DataFrame([[float("nan"), 20]], index=index, columns=columns)
        non_compilable = pd.DataFrame(
            [[True, False]], index=index, columns=columns,
        )
        figure = plot_metric_heatmap(
            values, domain="sokrates", metric="sokrates_lines_of_code",
            average_runs=False, non_compilable=non_compilable,
        )
        self.addCleanup(figure.clear)
        self.assertIn("X", [text.get_text() for text in figure.axes[0].texts])

    def test_metric_bar_panels_show_each_problem_and_client_model_value(self):
        index = pd.MultiIndex.from_tuples(
            [
                ("datastructures", "counter"),
                ("advanced_datastructures", "tree"),
            ],
            names=("category", "problem"),
        )
        columns = pd.MultiIndex.from_tuples(
            [("openai", "model-a"), ("anthropic", "model-b")],
            names=("client", "model"),
        )
        values = pd.DataFrame([[2, 4], [3, 5]], index=index, columns=columns)
        figure = plot_metric_bar_panels(
            values, domain="codeql", metric="codeql_issues",
            average_runs=False, panel="problem",
        )
        self.addCleanup(figure.clear)
        self.assertEqual(len(figure.axes), 2)
        self.assertEqual(
            [axis.get_title() for axis in figure.axes],
            ["Datastructures / counter", "Advanced Datastructures / tree"],
        )
        self.assertEqual(
            [patch.get_height() for patch in figure.axes[0].patches],
            [2, 4],
        )
        self.assertEqual(figure.axes[0].get_ylabel(), "Total findings")
        self.assertEqual(
            [tick.get_text() for tick in figure.axes[0].get_xticklabels()],
            ["OpenAI: model-a", "Anthropic: model-b"],
        )

    def test_exports_dataframes_and_nonempty_figures_with_coverage_warning(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            errors_file = root / "errors.csv"
            pd.DataFrame([
                result(run=1, infer_issues=2, infer_thread_safety_violation=2,
                       codeql_escaping=0, sokrates_status="success",
                       sokrates_lines_of_code=30),
                result(run=3, infer_thread_safety_violation=0, codeql_escaping=0,
                       sokrates_status="success", sokrates_lines_of_code=60),
            ]).to_csv(errors_file, index=False)
            (root / "passing_rates_table.png").write_bytes(b"legacy figure")
            with redirect_stderr(io.StringIO()) as warnings:
                self.assertEqual(run(errors_file), root)
            self.assertFalse((root / "passing_rates_table.png").exists())
            self.assertTrue(
                (root / "tables/passing_rates/passing_rates_table.png").is_file(),
            )
            self.assertIn("k=3 has 2/3 attempts", warnings.getvalue())
            outcomes = pd.read_csv(root / "outcomes.csv")
            summary = pd.read_csv(root / "passing_rates.csv")
            errors = pd.read_csv(root / "error_counts_run_1.csv")
            average_errors = pd.read_csv(root / "error_counts_avg_runs_1_3.csv")
            self.assertEqual(outcomes["passed"].tolist(), [False, True])
            self.assertEqual(summary["passed"].tolist(), [0, 1])
            self.assertEqual(
                errors.loc[errors["error_type"].eq("thread_safety_violation"), "count"].tolist(),
                [2],
            )
            self.assertTrue(average_errors["run"].eq("avg_runs_1_3").all())
            for run_slug in ("run_1", "avg_runs_1_3"):
                combined_rates = pd.read_csv(
                    root / f"combined_error_rates_{run_slug}.csv",
                )
                self.assertEqual(
                    combined_rates["category"].tolist(), ["datastructures"],
                )
                self.assertIn("findings_codeql", combined_rates)
                self.assertIn("findings_infer", combined_rates)
                figure_name = f"combined_error_rates_table_{run_slug}"
                figure_directory = root / "tables/error_rates"
                self.assertTrue((figure_directory / f"{figure_name}.png").is_file())
                self.assertTrue((figure_directory / f"{figure_name}.pdf").is_file())
            for tool in ("codeql", "infer"):
                for run_slug in ("run_1", "avg_runs_1_3"):
                    rates = pd.read_csv(root / f"{tool}_error_rates_{run_slug}.csv")
                    self.assertEqual(rates["category"].tolist(), ["datastructures"])
                    figure_directory = root / "tables" / tool
                    figure_name = f"{tool}_errors_rate_table_{run_slug}"
                    self.assertTrue((figure_directory / f"{figure_name}.png").is_file())
                    self.assertTrue((figure_directory / f"{figure_name}.pdf").is_file())
            for directory, name in (
                ("tables/passing_rates", "passing_rates_table"),
                ("heatmaps/passing_rates", "passing_rates_heatmap"),
                ("tables/error_counts", "error_counts_run_1_table"),
                ("tables/error_counts", "error_counts_avg_runs_1_3_table"),
                ("tables/sokrates", "sokrates_quality_table_run_1"),
                ("tables/sokrates", "sokrates_quality_table_avg_runs_1_3"),
                (
                    "heatmaps/sokrates",
                    "sokrates_quality_heatmap_lines_of_code_run_1",
                ),
                ("heatmaps/codeql", "codeql_errors_heatmap_escaping_run_1"),
                (
                    "heatmaps/infer",
                    "infer_errors_heatmap_thread_safety_violation_avg_runs_1_3",
                ),
                (
                    "bar_charts/sokrates",
                    "sokrates_quality_bars_by_problem_lines_of_code_run_1",
                ),
                (
                    "bar_charts/codeql",
                    "codeql_errors_bars_by_category_escaping_avg_runs_1_3",
                ),
                (
                    "bar_charts/compilation",
                    "compilation_errors_bars_by_problem_errors_run_1",
                ),
                (
                    "bar_charts/correctness",
                    "correctness_bars_by_problem_probability_run_1",
                ),
            ):
                png = (root / directory / f"{name}.png").read_bytes()
                pdf = (root / directory / f"{name}.pdf").read_bytes()
                self.assertTrue(png.startswith(b"\x89PNG\r\n\x1a\n"))
                self.assertTrue(pdf.startswith(b"%PDF-"))
                self.assertGreater(len(png), 1000)
                self.assertGreater(len(pdf), 1000)

    def test_legacy_csv_warns_instead_of_guessing_individual_error_types(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "errors.csv"
            pd.DataFrame([result(infer_issues=2)]).to_csv(path, index=False)
            with redirect_stderr(io.StringIO()) as warnings:
                run(path)
            self.assertIn("2 unclassified infer findings", warnings.getvalue())

    def test_cli_reports_invalid_input_without_claiming_success(self):
        with patch("sys.stderr") as errors:
            self.assertEqual(main(["nonexistent/errors.csv"]), 1)
        self.assertIn("Plotting failed", str(errors.write.call_args_list))


if __name__ == "__main__":
    unittest.main()
