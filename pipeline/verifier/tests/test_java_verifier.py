import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from pipeline.verifier.java_verifier import compile_file, run


class JavaVerifierTests(unittest.TestCase):
    def test_writes_one_report_per_model_with_compile_results(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model_dir = root / "copilot" / "gpt-5.6-luna"
            model_dir.mkdir(parents=True)
            (model_dir / "Good.java").write_text(
                "class Good {}\n", encoding="utf-8"
            )
            (model_dir / "Bad.java").write_text(
                "class Bad { }\nthis is invalid\n", encoding="utf-8"
            )

            self.assertFalse(run(root))

            report_path = (
                root
                / "copilot"
                / "gpt-5.6-luna"
                / "results"
                / "gpt-5.6-luna.results.json"
            )
            report = json.loads(report_path.read_text(encoding="utf-8"))
            self.assertEqual(report["model"], "gpt-5.6-luna")
            self.assertRegex(
                report["timestamp"],
                r"^date:\d{4}-\d{2}-\d{2}T\d{2}-\d{2}$",
            )
            self.assertEqual(
                {record["file"] for record in report["files"]},
                {"Bad.java", "Good.java"},
            )
            statuses = {
                record["file"]: record["compiled"] for record in report["files"]
            }
            self.assertTrue(statuses["Good.java"])
            self.assertFalse(statuses["Bad.java"])
            self.assertTrue(
                next(
                    record["stderr"]
                    for record in report["files"]
                    if record["file"] == "Bad.java"
                )
            )

    def test_empty_model_directory_gets_empty_successful_report(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "client" / "model").mkdir(parents=True)

            self.assertTrue(run(root))

            report = json.loads(
                (root / "client" / "model" / "results" / "model.results.json").read_text(
                    encoding="utf-8"
                )
            )
            self.assertEqual(report["files"], [])

    def test_missing_input_directory_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(NotADirectoryError):
                run(Path(directory) / "missing")

    @patch("pipeline.verifier.java_verifier.subprocess.run")
    def test_compile_file_uses_argument_list_without_shell(self, mocked_run):
        mocked_run.return_value.returncode = 0
        mocked_run.return_value.stdout = ""
        mocked_run.return_value.stderr = ""

        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "A file.java"
            source.write_text("class A {}\n", encoding="utf-8")
            result = compile_file(source)

        command = mocked_run.call_args.args[0]
        self.assertEqual(command[0], "javac")
        self.assertEqual(command[-1], str(source))
        self.assertFalse(mocked_run.call_args.kwargs["shell"])
        self.assertTrue(result["compiled"])


if __name__ == "__main__":
    unittest.main()
