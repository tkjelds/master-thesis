import tempfile
import unittest
from pathlib import Path

import pandas as pd

from pipeline.visualisation.dataframes import (
    average_error_counts,
    category_error_rates,
    error_count_table,
    error_counts,
    load_results,
    metric_problem_table,
    metric_summary,
    non_compilable_problem_table,
    outcomes_dataframe,
    passing_rate_table,
    passing_rates,
)


def result(*, model="model", client="client", category="datastructures",
           problem="counter", run=1, **measurements):
    return {
        "client": client, "model": model, "category": category,
        "problem": problem, "run": run,
        "compilation_errors": 0, "compilation_status": "success",
        "codeql_issues": 0, "codeql_status": "success",
        "infer_issues": 0, "infer_status": "success",
        "correctness_probability": 0.1, "correctness_status": "failed",
        **measurements,
    }


class DataFrameTests(unittest.TestCase):
    def test_any_error_or_unsuccessful_analysis_prevents_passing(self):
        cases = [
            ({}, True),
            ({"compilation_errors": 1, "compilation_status": "failed"}, False),
            ({"codeql_issues": 1}, False),
            ({"infer_issues": 1}, False),
            ({"infer_status": "failed", "infer_issues": ""}, False),
            ({"codeql_status": "missing", "codeql_issues": ""}, False),
            ({"compilation_status": "missing", "compilation_errors": ""}, False),
            ({"codeql_status": "failed", "codeql_issues": 0}, False),
        ]
        for measurements, expected in cases:
            with self.subTest(measurements=measurements):
                source = pd.DataFrame([result(**measurements)])
                outcomes = outcomes_dataframe(source)
                self.assertEqual(outcomes.loc[0, "passed"], expected)
                self.assertNotIn("passed", source)

    def test_first_k_runs_count_problems_once_with_a_fixed_shared_denominator(self):
        records = [
            result(problem="counter", run=1, infer_issues=1),
            result(problem="counter", run=2),
            result(problem="counter", run=3),
            result(problem="queue", run=1),
            result(problem="queue", run=2, codeql_issues=1),
            result(problem="queue", run=3, codeql_issues=1),
            result(problem="late", run=10),
            result(model="partial", problem="counter", run=3),
            result(model="partial", category="novel_problems", problem="counter", run=1),
        ]
        outcomes = outcomes_dataframe(pd.DataFrame(records))
        summary = passing_rates(outcomes).set_index(["client", "model", "k"])
        first = summary.loc[("client", "model", 1)]
        third = summary.loc[("client", "model", 3)]
        self.assertEqual((first["passed"], first["total"], first["pass_rate"]), (1, 4, 0.25))
        self.assertEqual((third["passed"], third["total"], third["pass_rate"]), (2, 4, 0.5))
        self.assertEqual((third["observed_attempts"], third["expected_attempts"]), (6, 12))
        self.assertEqual(summary.loc[("client", "partial", 1), "passed"], 1)
        self.assertEqual(summary.loc[("client", "partial", 3), "passed"], 2)
        formatted = passing_rate_table(summary.reset_index())
        self.assertEqual(formatted.loc[("client", "model"), 3], "2/4 (50.00%)")

    def test_missing_first_run_is_not_replaced_by_the_first_available_run(self):
        outcomes = outcomes_dataframe(pd.DataFrame([result(run=3)]))
        summary = passing_rates(outcomes)
        self.assertEqual(summary["passed"].tolist(), [0, 1])
        self.assertEqual(summary["observed_attempts"].tolist(), [0, 1])
        self.assertEqual(summary["total"].tolist(), [1, 1])

    def test_csv_loading_preserves_identifiers_and_maps_legacy_runs_to_one(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "errors.csv"
            pd.DataFrame([result(model="NA", category="", run="")]).to_csv(path, index=False)
            outcomes = load_results(path)
        self.assertEqual(outcomes.loc[0, "model"], "NA")
        self.assertEqual(outcomes.loc[0, "category"], "")
        self.assertEqual(outcomes.loc[0, "run"], 1)
        self.assertTrue(outcomes.loc[0, "passed"])

    def test_invalid_or_duplicate_measurements_are_rejected(self):
        for changes in (
            {"run": 0}, {"run": 1.5}, {"run": "run-1"},
            {"infer_issues": -1}, {"codeql_issues": 0.5},
            {"compilation_errors": float("inf")},
            {"codeql_issues": ""}, {"infer_status": "unknown"}, {"problem": ""},
        ):
            with self.subTest(changes=changes), self.assertRaises((ValueError, TypeError)):
                outcomes_dataframe(pd.DataFrame([result(**changes)]))
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            outcomes_dataframe(pd.DataFrame([result(run=""), result(run=1)]))
        with self.assertRaisesRegex(ValueError, "Missing evaluation columns"):
            outcomes_dataframe(pd.DataFrame([{"model": "model"}]))
        with self.assertRaisesRegex(ValueError, "No evaluation results"):
            outcomes_dataframe(pd.DataFrame([result()]).iloc[:0])

    def test_table_uses_stable_client_order_and_keeps_clients_separate(self):
        outcomes = outcomes_dataframe(pd.DataFrame([
            result(client="a", run=1, codeql_issues=1),
            result(client="a", run=3),
            result(client="b", run=1),
        ]))
        summary = passing_rates(outcomes)
        table = passing_rate_table(summary)
        self.assertEqual(table.index.tolist(), [("a", "model"), ("b", "model")])
        self.assertEqual(table.loc[("a", "model"), 1], "0/1 (0.00%)")
        self.assertEqual(table.loc[("a", "model"), 3], "1/1 (100.00%)")
        for k_values in ((), (0,), (True,), (1.5,)):
            with self.subTest(k_values=k_values), self.assertRaises(ValueError):
                passing_rates(outcomes, k_values)

    def test_model_order_groups_harness_apis_and_local_clients(self):
        outcomes = outcomes_dataframe(pd.DataFrame([
            result(client="lm-studio", model="local"),
            result(client="anthropic", model="z-model"),
            result(client="openai", model="gpt-z"),
            result(client="copilot-tools", model="sonnet"),
            result(client="openai", model="gpt-a"),
            result(client="anthropic", model="a-model"),
        ]))
        table = passing_rate_table(passing_rates(outcomes))
        self.assertEqual(table.index.tolist(), [
            ("copilot-tools", "sonnet"),
            ("openai", "gpt-a"),
            ("openai", "gpt-z"),
            ("anthropic", "a-model"),
            ("anthropic", "z-model"),
            ("lm-studio", "local"),
        ])

    def test_metric_tables_average_three_runs_per_problem_and_keep_domains_separate(self):
        records = [
            result(run=1, problem="counter", sokrates_status="success",
                   sokrates_lines_of_code=10, codeql_issues=1, codeql_escaping=1),
            result(run=2, problem="counter", sokrates_status="success",
                   sokrates_lines_of_code=20, codeql_issues=3, codeql_escaping=3),
            result(run=3, problem="counter", sokrates_status="success",
                   sokrates_lines_of_code=30, codeql_issues=5, codeql_escaping=5),
            result(run=1, problem="queue", sokrates_status="success",
                   sokrates_lines_of_code=40, codeql_issues=4, codeql_escaping=4),
            result(run=2, problem="queue", sokrates_status="success",
                   sokrates_lines_of_code=60, codeql_issues=6, codeql_escaping=6),
            result(run=3, problem="queue", sokrates_status="success",
                   sokrates_lines_of_code=80, codeql_issues=8, codeql_escaping=8),
            result(category="advanced_datastructures", problem="tree",
                   sokrates_status="success", sokrates_lines_of_code=100,
                   codeql_escaping=0),
        ]
        outcomes = outcomes_dataframe(pd.DataFrame(records))
        quality_first = metric_summary(outcomes, "sokrates")
        quality_average = metric_summary(outcomes, "sokrates", average_runs=True)
        codeql_first = metric_summary(outcomes, "codeql")
        codeql_average = metric_summary(outcomes, "codeql", average_runs=True)
        self.assertEqual(
            quality_first.loc[("client", "model"), ("Datastructures", "Lines of code")],
            25,
        )
        self.assertEqual(
            quality_average.loc[("client", "model"), ("Datastructures", "Lines of code")],
            40,
        )
        self.assertEqual(
            quality_average.loc[
                ("client", "model"),
                ("Advanced Datastructures", "Lines of code"),
            ],
            100,
        )
        self.assertEqual(
            codeql_first.loc[
                ("client", "model"), ("Datastructures", "Total findings"),
            ],
            5,
        )
        self.assertEqual(
            codeql_average.loc[
                ("client", "model"), ("Datastructures", "Total findings"),
            ],
            9,
        )
        self.assertEqual(
            codeql_average.index[-1], ("Total", ""),
        )
        self.assertEqual(
            codeql_average.loc[
                ("Total", ""), ("Datastructures", "Total findings"),
            ],
            9,
        )
        self.assertNotIn("sokrates_lines_of_code", codeql_average.columns)
        first_heatmap = metric_problem_table(
            outcomes, "sokrates", "sokrates_lines_of_code",
        )
        average_heatmap = metric_problem_table(
            outcomes, "sokrates", "sokrates_lines_of_code", average_runs=True,
        )
        self.assertEqual(first_heatmap.loc[("datastructures", "counter"), ("client", "model")], 10)
        self.assertEqual(average_heatmap.loc[("datastructures", "counter"), ("client", "model")], 20)
        self.assertEqual(
            list(average_heatmap.index.get_level_values("category").unique()),
            ["datastructures", "advanced_datastructures"],
        )

    def test_compilation_and_correctness_metrics_include_run_one_and_three_run_average(self):
        outcomes = outcomes_dataframe(pd.DataFrame([
            result(
                problem="counter", run=1, compilation_errors=1,
                compilation_status="failed", correctness_status="success",
                correctness_probability=0.3,
            ),
            result(
                problem="counter", run=2, correctness_status="success",
                correctness_probability=0.5,
            ),
            result(
                problem="counter", run=3, correctness_status="success",
                correctness_probability=0.7,
            ),
        ]))
        compilation = metric_problem_table(
            outcomes, "compilation", "compilation_errors", average_runs=True,
        )
        correctness = metric_problem_table(
            outcomes, "correctness", "correctness_probability", average_runs=True,
        )
        self.assertAlmostEqual(
            compilation.loc[("datastructures", "counter"), ("client", "model")],
            1 / 3,
        )
        self.assertAlmostEqual(
            correctness.loc[("datastructures", "counter"), ("client", "model")],
            0.5,
        )
        self.assertEqual(
            metric_summary(outcomes, "compilation").loc[
                ("client", "model"), ("Datastructures", "Compilation errors"),
            ],
            1,
        )
        self.assertAlmostEqual(
            metric_summary(outcomes, "correctness", average_runs=True).loc[
                ("client", "model"),
                ("Datastructures", "Correctness probability"),
            ],
            0.5,
        )

    def test_metric_heatmap_keeps_models_when_a_category_has_no_first_run(self):
        outcomes = outcomes_dataframe(pd.DataFrame([
            result(
                category="novel_problems", run=3,
                sokrates_status="success", sokrates_lines_of_code=30,
            ),
        ]))
        matrix = metric_problem_table(
            outcomes, "sokrates", "sokrates_lines_of_code",
            category="novel_problems",
        )
        missing = non_compilable_problem_table(
            outcomes, category="novel_problems",
        )
        self.assertEqual(matrix.index.tolist(), ["counter"])
        self.assertTrue(pd.isna(matrix.loc["counter", ("client", "model")]))
        self.assertEqual(matrix.columns.tolist(), [("client", "model")])
        self.assertTrue(missing.loc["counter", ("client", "model")])

    def test_non_compilable_problem_table_marks_failed_and_missing_files(self):
        outcomes = outcomes_dataframe(pd.DataFrame([
            result(
                client="compiled", problem="counter", run=run,
                sokrates_status="success", sokrates_lines_of_code=20 + run,
            )
            for run in (1, 2, 3)
        ] + [
            result(
                client="failed", problem="counter", compilation_errors=1,
                compilation_status="failed", sokrates_status="failed",
                sokrates_lines_of_code="",
            ),
            result(client="absent", problem="queue"),
        ]))
        first_run = non_compilable_problem_table(outcomes)
        average = non_compilable_problem_table(outcomes, average_runs=True)
        counter = ("datastructures", "counter")
        self.assertFalse(first_run.loc[counter, ("compiled", "model")])
        self.assertTrue(first_run.loc[counter, ("failed", "model")])
        self.assertTrue(first_run.loc[counter, ("absent", "model")])
        self.assertFalse(average.loc[counter, ("compiled", "model")])
        self.assertTrue(average.loc[counter, ("failed", "model")])

    def test_error_metric_table_ends_with_whole_number_totals(self):
        outcomes = outcomes_dataframe(pd.DataFrame([
            result(client="openai", model="first", codeql_issues=2, codeql_escaping=2),
            result(client="anthropic", model="second", codeql_issues=3, codeql_escaping=3),
        ]))
        table = metric_summary(outcomes, "codeql")
        self.assertEqual(table.index[-1], ("Total", ""))
        self.assertEqual(
            table.loc[("Total", ""), ("Datastructures", "Total findings")],
            5,
        )
        self.assertEqual(
            table.loc[("Total", ""), ("Datastructures", "Escaping")],
            5,
        )

    def test_average_error_counts_averages_run_totals_and_marks_partial_coverage(self):
        records = [
            result(problem="counter", run=1, codeql_issues=1, codeql_escaping=1),
            result(problem="queue", run=1, codeql_issues=2, codeql_escaping=2),
            result(problem="counter", run=2, codeql_issues=2, codeql_escaping=2),
            result(
                problem="queue", run=2, codeql_status="missing",
                codeql_issues="", codeql_escaping="",
            ),
            result(problem="counter", run=3, codeql_issues=3, codeql_escaping=3),
            result(problem="queue", run=3, codeql_issues=3, codeql_escaping=3),
        ]
        outcomes = outcomes_dataframe(pd.DataFrame(records))
        average = average_error_counts(outcomes)
        codeql = average.loc[
            average["tool"].eq("codeql") & average["error_type"].eq("escaping")
        ].iloc[0]
        self.assertAlmostEqual(codeql["count"], 11 / 3)
        self.assertEqual((codeql["observed_attempts"], codeql["expected_attempts"]), (5, 6))
        table = error_count_table(average)
        self.assertEqual(
            table.loc[("client", "model"), ("codeql", "escaping")],
            "3.67*",
        )

    def test_average_error_counts_skips_a_missing_run(self):
        outcomes = outcomes_dataframe(pd.DataFrame([
            result(run=2, codeql_issues=2, codeql_escaping=2),
            result(run=3, codeql_issues=4, codeql_escaping=4),
        ]))
        average = average_error_counts(outcomes)
        codeql = average.loc[
            average["tool"].eq("codeql") & average["error_type"].eq("escaping")
        ].iloc[0]
        self.assertEqual(codeql["count"], 3)
        self.assertEqual((codeql["observed_attempts"], codeql["expected_attempts"]), (2, 3))
        table = error_count_table(average)
        self.assertEqual(
            table.loc[("client", "model"), ("codeql", "escaping")],
            "3*",
        )

    def test_category_error_rates_use_successful_analyses_and_available_runs(self):
        outcomes = outcomes_dataframe(pd.DataFrame([
            result(problem="counter", run=2, codeql_issues=4),
            result(problem="queue", run=2, codeql_issues=6),
            result(problem="counter", run=3, codeql_issues=6),
            result(problem="queue", run=3, codeql_issues=8),
        ]))
        first = category_error_rates(outcomes, "codeql")
        average = category_error_rates(outcomes, "codeql", average_runs=True)
        first_row = first.iloc[0]
        average_row = average.iloc[0]
        self.assertTrue(pd.isna(first_row["findings"]))
        self.assertEqual(first_row["analyzed"], 0)
        self.assertEqual(average_row["findings"], 12)
        self.assertEqual(average_row["analyzed"], 2)
        self.assertEqual(average_row["rate"], 6)
        self.assertEqual(
            (average_row["runs_used"], average_row["expected_runs"]),
            (2, 3),
        )

    def test_infer_metric_table_displays_thread_safety_qualifier_categories(self):
        outcomes = outcomes_dataframe(pd.DataFrame([
            result(
                infer_issues=2,
                infer_thread_safety_violation_read_write_race=1,
                infer_thread_safety_violation_unprotected_write=1,
                infer_interface_not_thread_safe=0,
            ),
        ]))
        table = metric_summary(outcomes, "infer")
        self.assertIn(("Datastructures", "Read/Write Race"), table.columns)
        self.assertIn(("Datastructures", "Unprotected Write"), table.columns)
        self.assertEqual(
            table.loc[
                ("client", "model"), ("Datastructures", "Read/Write Race"),
            ],
            1,
        )

    def test_error_counts_split_types_sum_findings_and_use_only_run_one(self):
        defaults = {
            "codeql_safe_publication": 0, "codeql_escaping": 0,
            "infer_thread_safety_violation": 0, "infer_deadlock": 0,
        }
        records = [
            result(client="a", codeql_issues=3, infer_issues=2,
                   **{**defaults, "codeql_safe_publication": 2, "codeql_escaping": 1,
                      "infer_thread_safety_violation": 2}),
            result(client="a", category="novel_problems", codeql_issues=1,
                   compilation_errors=1, compilation_status="failed",
                   **{**defaults, "codeql_safe_publication": 1}),
            result(client="b", infer_issues=1, **{**defaults, "infer_deadlock": 1}),
            result(client="a", run=2, codeql_issues=100,
                   **{**defaults, "codeql_safe_publication": 100}),
        ]
        summary = error_counts(outcomes_dataframe(pd.DataFrame(records)))
        table = error_count_table(summary)
        self.assertEqual(table.loc[("a", "model"), ("compilation", "errors")], "1")
        self.assertEqual(table.loc[("a", "model"), ("codeql", "safe_publication")], "3")
        self.assertEqual(table.loc[("a", "model"), ("codeql", "escaping")], "1")
        self.assertEqual(table.loc[("a", "model"), ("infer", "thread_safety_violation")], "2")
        self.assertEqual(table.loc[("b", "model"), ("infer", "deadlock")], "1*")
        self.assertEqual(table.loc[("Total", ""), ("codeql", "safe_publication")], "3*")
        self.assertEqual(table.loc[("Total", ""), ("infer", "deadlock")], "1*")
        self.assertNotIn("unclassified", summary["error_type"].tolist())
        self.assertTrue(summary["expected_attempts"].eq(2).all())

    def test_error_counts_do_not_treat_missing_or_failed_analysis_as_zero(self):
        outcomes = outcomes_dataframe(pd.DataFrame([
            result(client="a", compilation_errors=1, compilation_status="failed",
                   codeql_issues="", codeql_status="missing", codeql_escaping="",
                   infer_issues="", infer_status="failed", infer_deadlock=""),
            result(client="b", run=3, codeql_escaping=0, infer_deadlock=0),
        ]))
        summary = error_counts(outcomes)
        table = error_count_table(summary)
        self.assertEqual(table.loc[("a", "model"), ("compilation", "errors")], "1")
        self.assertEqual(table.loc[("a", "model"), ("codeql", "escaping")], "N/A")
        self.assertTrue(table.loc[("b", "model")].eq("N/A").all())
        self.assertEqual(table.loc[("Total", ""), ("compilation", "errors")], "1*")
        self.assertEqual(table.loc[("Total", ""), ("infer", "deadlock")], "N/A")
        self.assertTrue(summary.loc[summary["tool"].eq("infer"), "count"].isna().all())

    def test_legacy_or_partial_breakdowns_preserve_unclassified_findings(self):
        outcomes = outcomes_dataframe(pd.DataFrame([
            result(infer_issues=3, codeql_issues=2, codeql_escaping=1),
        ]))
        table = error_count_table(error_counts(outcomes))
        self.assertEqual(table.loc[("client", "model"), ("infer", "unclassified")], "3")
        self.assertEqual(table.loc[("client", "model"), ("codeql", "escaping")], "1")
        self.assertEqual(table.loc[("client", "model"), ("codeql", "unclassified")], "1")
        self.assertEqual(error_counts(outcomes, run=2)["observed_attempts"].sum(), 0)
        for run in (0, True, 1.5):
            with self.subTest(run=run), self.assertRaises(ValueError):
                error_counts(outcomes, run=run)

    def test_invalid_breakdown_counts_are_rejected(self):
        for changes in (
            {"infer_deadlock": -1},
            {"infer_deadlock": 0.5},
            {"infer_deadlock": ""},
            {"infer_issues": 1, "infer_deadlock": 2},
            {"codeql_issues": 1, "codeql_escaping": 1, "codeql_safe_publication": 1},
        ):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                outcomes_dataframe(pd.DataFrame([result(**changes)]))


if __name__ == "__main__":
    unittest.main()
