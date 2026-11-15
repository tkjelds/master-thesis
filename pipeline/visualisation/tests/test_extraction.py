import csv
import io
import json
import tempfile
import unittest
from contextlib import chdir, redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

from pipeline.visualisation.extraction import REPOSITORY_ROOT, main, run


class ExtractionTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.generation = self.root / "date:test"
        self.generation.mkdir()
        self.collection_file = self.root / "visualisation/date:test/collection.json"
        self.collection_file.parent.mkdir(parents=True)
        self.collection = {}
        self.entries = []
        self.documents = {}

    def add_program(
        self, *, client="client", model="model", category="datastructures",
        run_name="run-1", filename="Counter.java", problem="counter", records=None,
    ):
        parts = [client, model]
        if run_name:
            parts.append(run_name)
        if category:
            parts.append(category)
        relative = Path(*parts) / filename
        entry = {
            "client": client, "model": model, "category": category,
            "problem": problem, "java_file": relative.as_posix(),
        }
        if run_name:
            entry["run"] = run_name
        self.entries.append(entry)
        if records is None:
            records = {
                "compilation": {"compiled": True},
                "codeql": {"analyzed": True, "issue_count": 2, "issues": [
                    {"rule_id": "java/safe-publication"}, {"rule_id": "java/escaping"},
                ]},
                "infer": {"analyzed": True, "issue_count": 3, "issues": [
                    {"bug_type": "THREAD_SAFETY_VIOLATION"},
                    {"bug_type": "THREAD_SAFETY_VIOLATION"},
                    {"bug_type": "INTERFACE_NOT_THREAD_SAFE"},
                ]},
                "correctness": {"analyzed": True, "probability": 0.75},
                "sokrates": {
                    "analyzed": True,
                    "metrics": {
                        "lines_of_code": 30,
                        "unit_count": 2,
                        "unit_size_total_loc": 20,
                        "unit_size_max_loc": 12,
                        "unit_size_mean_loc": 10.0,
                        "conditional_complexity_total": 5,
                        "conditional_complexity_max": 3,
                        "conditional_complexity_mean": 2.5,
                    },
                },
            }
        node = self.collection.setdefault(client, {}).setdefault(model, {})
        if category:
            node = node.setdefault(category, {})
        if run_name:
            node = node.setdefault(run_name, {})
        reports = {}
        for tool, record in records.items():
            filename_suffix = (
                f"{model}.sokrates.result.json"
                if tool == "sokrates" else f"{model}.{tool}.json"
            )
            path = self.generation / relative.parent / "results" / filename_suffix
            node[tool] = str(path)
            file = (
                (self.generation / relative).relative_to(
                    REPOSITORY_ROOT, walk_up=True,
                ).as_posix()
                if tool == "correctness" else filename
            )
            document = self.documents.setdefault(path, {"files": []})
            document["files"].append({"file": file, **record})
            reports[tool] = path
        return reports

    def write_inputs(self):
        self.collection_file.write_text(json.dumps(self.collection), encoding="utf-8")
        (self.generation / "manifest.json").write_text(
            json.dumps({"generations": self.entries}), encoding="utf-8",
        )
        for path, document in self.documents.items():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps(document), encoding="utf-8")

    def read_rows(self):
        with self.collection_file.with_name("errors.csv").open(
            encoding="utf-8", newline="",
        ) as stream:
            return list(csv.DictReader(stream))

    def extract(self):
        self.write_inputs()
        with redirect_stderr(io.StringIO()) as errors:
            output = run(self.collection_file)
        self.assertEqual(output, self.collection_file.with_name("errors.csv"))
        return self.read_rows(), errors.getvalue()

    def test_extracts_separate_counts_and_probability_using_manifest_problem(self):
        reports = self.add_program(filename="GeneratedClass.java", problem="array_queue")
        self.documents[reports["correctness"]]["files"][0]["problem"] = "not-authoritative"
        rows, errors = self.extract()
        self.assertEqual(errors, "")
        self.assertEqual(rows, [{
            "client": "client", "model": "model",
            "category": "datastructures", "run": "1", "problem": "array_queue",
            "compilation_errors": "0", "compilation_status": "success",
            "codeql_issues": "2", "codeql_status": "success",
            "codeql_safe_publication": "1", "codeql_escaping": "1",
            "codeql_not_threadsafe": "0",
            "infer_issues": "3", "infer_status": "success",
            "infer_thread_safety_violation": "2", "infer_interface_not_thread_safe": "1",
            "correctness_probability": "0.75", "correctness_status": "success",
            "sokrates_lines_of_code": "30", "sokrates_unit_count": "2",
            "sokrates_unit_size_total_loc": "20", "sokrates_unit_size_max_loc": "12",
            "sokrates_unit_size_mean_loc": "10.0",
            "sokrates_conditional_complexity_total": "5",
            "sokrates_conditional_complexity_max": "3",
            "sokrates_conditional_complexity_mean": "2.5",
            "sokrates_status": "success",
        }])
        self.assertFalse(any(self.generation.rglob("*.java")))

    def test_sorts_all_runs_numerically_and_keeps_same_filenames_separate(self):
        for number in (10, 3, 1, 2):
            for client in ("z-client", "a-client"):
                self.add_program(client=client, run_name=f"run-{number}")
        self.add_program(client="a-client", model="other", category="novel_problems")
        self.add_program(client="a-client", category="advanced_datastructures")
        self.add_program(client="legacy", category="", run_name=None)
        rows, _ = self.extract()
        self.assertEqual(len(rows), 11)
        keys = [
            (int(row["run"] or 0), row["client"], row["model"], row["category"], row["problem"])
            for row in rows
        ]
        self.assertEqual(keys, sorted(keys))
        self.assertEqual(rows[0]["run"], "")
        self.assertEqual(rows[0]["category"], "")
        self.assertTrue(all(row["correctness_probability"] == "0.75" for row in rows))

    def test_zero_findings_failures_and_unavailable_results_are_distinct(self):
        self.add_program(records={
            "compilation": {"compiled": False, "stderr": "multiple diagnostics"},
            "codeql": {"analyzed": True, "issue_count": 0, "issues": []},
            "infer": {"analyzed": False, "issue_count": 0},
            "correctness": {"analyzed": False, "probability": None},
            "sokrates": {"analyzed": False, "metrics": None},
        })
        self.add_program(filename="Other.java", problem="other", records={})
        rows, warnings = self.extract()
        first, second = rows
        self.assertEqual((first["compilation_errors"], first["compilation_status"]), ("1", "failed"))
        self.assertEqual((first["codeql_issues"], first["codeql_status"]), ("0", "success"))
        self.assertEqual((first["infer_issues"], first["infer_status"]), ("", "failed"))
        self.assertEqual(
            (first["correctness_probability"], first["correctness_status"]), ("", "failed"),
        )
        for tool in ("compilation", "codeql", "infer", "correctness", "sokrates"):
            self.assertEqual(second[f"{tool}_status"], "missing")
        self.assertIn("Other.java: missing results", warnings)

    def test_splits_codeql_rules_and_preserves_zero_failed_and_missing_measurements(self):
        self.add_program(records={"codeql": {
            "analyzed": True, "issue_count": 5, "issues": [
                {"rule_id": "java/safe-publication"},
                {"rule_id": "java/safe-publication"},
                {"rule_id": "java/escaping"},
                {"rule_id": "java/not-threadsafe"},
                {"rule_id": "java/additional-check"},
            ],
        }})
        self.add_program(filename="Zero.java", problem="zero", records={"codeql": {
            "analyzed": True, "issue_count": 0, "issues": [],
        }})
        self.add_program(filename="Failed.java", problem="failed", records={"codeql": {
            "analyzed": False, "issue_count": 0, "issues": [],
        }})
        self.add_program(filename="Missing.java", problem="missing", records={})
        rows, _ = self.extract()
        by_problem = {row["problem"]: row for row in rows}
        counts = {
            "codeql_safe_publication": "2", "codeql_escaping": "1",
            "codeql_not_threadsafe": "1", "codeql_additional_check": "1",
        }
        for column, count in counts.items():
            self.assertEqual(by_problem["counter"][column], count)
            self.assertEqual(by_problem["zero"][column], "0")
            self.assertEqual(by_problem["failed"][column], "")
            self.assertEqual(by_problem["missing"][column], "")
        self.assertEqual(sum(int(by_problem["counter"][column]) for column in counts), 5)
        self.assertTrue(all("java_file" not in row for row in rows))

    def test_invalid_codeql_breakdowns_do_not_replace_previous_csv(self):
        reports = self.add_program()
        output = self.collection_file.with_name("errors.csv")
        output.write_text("previous CSV\n", encoding="utf-8")
        cases = [
            {"analyzed": True, "issue_count": 2},
            {"analyzed": True, "issue_count": 2, "issues": {}},
            {"analyzed": True, "issue_count": 2, "issues": []},
            {"analyzed": True, "issue_count": 1, "issues": [{}]},
            {"analyzed": True, "issue_count": 1, "issues": [{"rule_id": ""}]},
            {"analyzed": True, "issue_count": 2, "issues": [
                {"rule_id": "java/foo-bar"}, {"rule_id": "java/foo_bar"},
            ]},
        ]
        for record in cases:
            with self.subTest(record=record):
                self.write_inputs()
                reports["codeql"].write_text(
                    json.dumps({"files": [{"file": "Counter.java", **record}]}),
                    encoding="utf-8",
                )
                with redirect_stderr(io.StringIO()), self.assertRaises(ValueError):
                    run(self.collection_file)
                self.assertEqual(output.read_text(encoding="utf-8"), "previous CSV\n")

    def test_splits_infer_types_and_keeps_failed_or_missing_values_blank(self):
        self.add_program()
        self.add_program(filename="Zero.java", problem="zero", records={"infer": {
            "analyzed": True, "issue_count": 0, "issues": [],
        }})
        self.add_program(filename="Failed.java", problem="failed", records={"infer": {
            "analyzed": False, "issue_count": 0, "issues": [],
        }})
        self.add_program(filename="Missing.java", problem="missing", records={})
        rows, _ = self.extract()
        by_problem = {row["problem"]: row for row in rows}
        for column, count in (
            ("infer_thread_safety_violation", "2"),
            ("infer_interface_not_thread_safe", "1"),
        ):
            self.assertEqual(by_problem["counter"][column], count)
            self.assertEqual(by_problem["zero"][column], "0")
            self.assertEqual(by_problem["failed"][column], "")
            self.assertEqual(by_problem["missing"][column], "")

    def test_splits_thread_safety_violations_by_qualifier_prefix_dynamically(self):
        issues = [
            {"bug_type": "THREAD_SAFETY_VIOLATION", "qualifier": "Read/Write race. ..."},
            {"bug_type": "THREAD_SAFETY_VIOLATION", "qualifier": "Unprotected write. ..."},
            {"bug_type": "THREAD_SAFETY_VIOLATION", "qualifier": "Unsafe read. ..."},
        ]
        self.add_program(records={"infer": {
            "analyzed": True, "issue_count": len(issues), "issues": issues,
        }})
        rows, _ = self.extract()
        self.assertEqual(rows[0]["infer_issues"], "3")
        self.assertEqual(rows[0]["infer_thread_safety_violation_read_write_race"], "1")
        self.assertEqual(rows[0]["infer_thread_safety_violation_unprotected_write"], "1")
        self.assertEqual(rows[0]["infer_thread_safety_violation_unsafe_read"], "1")

    def test_invalid_infer_breakdowns_preserve_the_previous_csv(self):
        reports = self.add_program()
        output = self.collection_file.with_name("errors.csv")
        output.write_text("previous CSV\n", encoding="utf-8")
        cases = [
            {"analyzed": True, "issue_count": 1},
            {"analyzed": True, "issue_count": 1, "issues": []},
            {"analyzed": True, "issue_count": 1, "issues": [{}]},
            {"analyzed": True, "issue_count": 1, "issues": [{"bug_type": ""}]},
            {"analyzed": True, "issue_count": 2, "issues": [
                {"bug_type": "FOO-BAR"}, {"bug_type": "FOO_BAR"},
            ]},
        ]
        for record in cases:
            with self.subTest(record=record):
                self.write_inputs()
                reports["infer"].write_text(
                    json.dumps({"files": [{"file": "Counter.java", **record}]}),
                    encoding="utf-8",
                )
                with redirect_stderr(io.StringIO()), self.assertRaises(ValueError):
                    run(self.collection_file)
                self.assertEqual(output.read_text(encoding="utf-8"), "previous CSV\n")

    def test_missing_group_and_disappeared_report_keep_manifest_rows(self):
        reports = self.add_program()
        self.add_program(client="no-evaluations", records={})
        self.write_inputs()
        reports["codeql"].unlink()
        with redirect_stderr(io.StringIO()) as warnings:
            run(self.collection_file)
        rows = self.read_rows()
        self.assertEqual(len(rows), 2)
        self.assertEqual(rows[0]["codeql_status"], "missing")
        self.assertEqual(rows[0]["codeql_issues"], "")
        self.assertEqual(rows[1]["compilation_status"], "missing")
        self.assertIn("missing report", warnings.getvalue())

    def test_mixed_legacy_and_repeated_categories_and_encoded_model(self):
        self.add_program(model="encoded-model", run_name=None)
        self.add_program(model="encoded-model", run_name="run-2")
        for entry in self.entries:
            entry["model"] = "raw/model"
        rows, _ = self.extract()
        self.assertEqual([row["run"] for row in rows], ["", "2"])
        self.assertTrue(all(row["model"] == "encoded-model" for row in rows))

    def test_unmapped_codeql_findings_warn_without_changing_problem_counts(self):
        reports = self.add_program()
        self.documents[reports["codeql"]]["unmapped_issues"] = [{"file": None}]
        rows, warnings = self.extract()
        self.assertEqual(rows[0]["codeql_issues"], "2")
        self.assertIn("excluding 1 unmapped issues", warnings)

    def test_requires_manifest_mapping_and_preserves_existing_csv_on_invalid_data(self):
        reports = self.add_program()
        self.write_inputs()
        output = self.collection_file.with_name("errors.csv")
        output.write_text("previous CSV\n", encoding="utf-8")
        manifest = self.generation / "manifest.json"
        cases = [
            (manifest, {"generations": []}, "no generated problems"),
            (manifest, {"generations": [*self.entries, *self.entries]}, "duplicate manifest"),
            (manifest, {"generations": [{**self.entries[0], "problem": None}]}, "problem"),
            (reports["codeql"], {"files": [{"file": "Unmapped.java"}]}, "no manifest mapping"),
            (reports["infer"], {"files": self.documents[reports["infer"]]["files"] * 2}, "duplicate infer"),
            (reports["correctness"], {"files": {}}, "files list"),
        ]
        for path, document, message in cases:
            with self.subTest(message=message):
                self.write_inputs()
                path.write_text(json.dumps(document), encoding="utf-8")
                with self.assertRaisesRegex(ValueError, message):
                    run(self.collection_file)
                self.assertEqual(output.read_text(encoding="utf-8"), "previous CSV\n")
        self.write_inputs()
        manifest.unlink()
        with self.assertRaises(FileNotFoundError):
            run(self.collection_file)

    def test_rejects_invalid_consumed_fields(self):
        reports = self.add_program()
        cases = [
            ("compilation", "compiled", 0),
            ("infer", "analyzed", "true"),
            ("codeql", "issue_count", True),
            ("codeql", "issue_count", -1),
            ("codeql", "issue_count", 1.5),
            ("correctness", "probability", True),
            ("correctness", "probability", None),
            ("correctness", "probability", float("nan")),
            ("correctness", "probability", float("inf")),
            ("correctness", "probability", -0.1),
            ("correctness", "probability", 1.1),
            ("sokrates", "metrics", {}),
        ]
        for tool, field, value in cases:
            with self.subTest(tool=tool, field=field, value=value):
                self.write_inputs()
                document = json.loads(reports[tool].read_text())
                document["files"][0][field] = value
                reports[tool].write_text(json.dumps(document), encoding="utf-8")
                with self.assertRaises(ValueError):
                    run(self.collection_file)

    def test_rejects_malformed_json_wrong_groups_and_multiple_generations(self):
        reports = self.add_program()
        self.write_inputs()
        reports["infer"].write_text("{broken", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Invalid JSON.*infer"):
            run(self.collection_file)
        self.write_inputs()
        self.collection["client"]["model"]["datastructures"]["run-1"]["infer"] = str(
            self.root / "other/client/model/run-1/datastructures/results/model.infer.json"
        )
        self.collection_file.write_text(json.dumps(self.collection), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "one generation"):
            run(self.collection_file)
        self.collection_file.write_text(
            json.dumps({"wrong-client": self.collection["client"]}), encoding="utf-8",
        )
        with self.assertRaisesRegex(ValueError, "disagrees"):
            run(self.collection_file)

    def test_rejects_correctness_record_from_another_group(self):
        first = self.add_program(client="first")
        second = self.add_program(client="second")
        self.documents[first["correctness"]]["files"] = self.documents[second["correctness"]]["files"]
        self.write_inputs()
        with self.assertRaisesRegex(ValueError, "another group"):
            run(self.collection_file)

    def test_output_failure_preserves_old_csv_and_cleans_up_temporary_files(self):
        self.add_program()
        self.write_inputs()
        output = self.collection_file.with_name("errors.csv")
        output.write_text("previous\n", encoding="utf-8")
        with (
            patch.object(Path, "replace", side_effect=PermissionError("cannot replace CSV")),
            self.assertRaisesRegex(PermissionError, "cannot replace CSV"),
        ):
            run(self.collection_file)
        self.assertEqual(output.read_text(encoding="utf-8"), "previous\n")
        self.assertEqual(sorted(path.name for path in output.parent.iterdir()), [
            "collection.json", "errors.csv",
        ])

    def test_cli_works_outside_repository_and_reports_read_failures(self):
        self.add_program()
        self.write_inputs()
        with chdir(self.root), redirect_stdout(io.StringIO()) as output:
            self.assertEqual(main([str(self.collection_file)]), 0)
        self.assertIn("errors.csv", output.getvalue())
        with (
            patch.object(Path, "read_text", side_effect=PermissionError("cannot read report")),
            redirect_stderr(io.StringIO()) as errors,
            redirect_stdout(io.StringIO()) as output,
        ):
            self.assertEqual(main([str(self.collection_file)]), 1)
        self.assertIn("Extraction failed: cannot read report", errors.getvalue())
        self.assertEqual(output.getvalue(), "")


if __name__ == "__main__":
    unittest.main()
