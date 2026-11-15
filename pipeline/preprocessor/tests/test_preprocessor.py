import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from pipeline.preprocessor.preprocessor import main, preprocess_source, run
from pipeline.preprocessor.markdown_fences import strip_markdown_fences
from pipeline.preprocessor.thread_safe import annotate_source
from pipeline.verifier.java_verifier import compile_file


class PreprocessorTests(unittest.TestCase):
    def test_each_step_and_full_pipeline_preserve_fully_preprocessed_source(self):
        for source in (
            "```java\npublic class Map {}\n```\n",
            "```\nclass Map { static class Node {} }\nclass Helper {}\n```",
            "class Map { @interface ThreadSafe {} }",
            "```java\n@Map.ThreadSafe\nclass Map { @interface ThreadSafe {} }\n```",
        ):
            with self.subTest(source=source):
                processed = preprocess_source(source)
                for step in (strip_markdown_fences, annotate_source, preprocess_source):
                    with self.subTest(step=step.__name__):
                        self.assertEqual(step(processed), processed)

    def test_repeated_preprocessing_does_not_rewrite_preprocessed_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = root / "client" / "model" / "datastructures"
            model.mkdir(parents=True)
            source = model / "Map.java"
            source.write_text(
                "Here's the implementation. I'll use one class.\n"
                "```java\npublic class Map {}\n```\nIt's complete.\n"
            )
            self.assertEqual(run(root), 1)
            contents = source.read_bytes()
            modified = source.stat().st_mtime_ns
            with patch.object(Path, "write_text") as write:
                for _ in range(3):
                    self.assertEqual(run(root), 0)
                write.assert_not_called()
            self.assertEqual(source.read_bytes(), contents)
            self.assertEqual(source.stat().st_mtime_ns, modified)

    def test_removes_fences_before_annotation_and_compilation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = root / "client" / "model" / "datastructures"
            model.mkdir(parents=True)
            source = model / "Map.java"
            source.write_text(
                "package-private helper types are top-level; "
                "the file has one public class.\n"
                "```java\npublic class Map {}\n```\nIt's complete.\n"
            )
            self.assertEqual(run(root), 1)
            self.assertEqual(source.read_text(), annotate_source("public class Map {}\n"))
            self.assertEqual(run(root), 0)
            result = compile_file(source)
            self.assertTrue(result["compiled"], result["stderr"])

    def test_runs_steps_in_order(self):
        with (
            patch(
                "pipeline.preprocessor.preprocessor.strip_markdown_fences",
                return_value="cleaned",
            ) as strip,
            patch(
                "pipeline.preprocessor.preprocessor.annotate_source",
                return_value="annotated",
            ) as annotate,
        ):
            self.assertEqual(preprocess_source("original"), "annotated")
            strip.assert_called_once_with("original")
            annotate.assert_called_once_with("cleaned")

    def test_removes_fences_from_already_annotated_sources(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = root / "client" / "model"
            model.mkdir(parents=True)
            annotated = annotate_source("public class Map {}\n")
            source = model / "Map.java"
            source.write_text(f"```java\n{annotated}```\n")
            self.assertEqual(run(root), 1)
            self.assertEqual(source.read_text(), annotated)

    def test_processes_all_categories_and_models_but_skips_results(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for model_name in ("first", "second"):
                for category in ("datastructures", "novel_problems"):
                    model = root / "client" / model_name / category
                    model.mkdir(parents=True)
                    (model / "Map.java").write_text("```java\nclass Map {}\n```")
                    results = model / "results"
                    results.mkdir()
                    (results / "Snapshot.java").write_text("```java\nclass Map {}\n```")
            self.assertEqual(run(root), 4)
            self.assertEqual(run(root), 0)
            for snapshot in root.rglob("Snapshot.java"):
                self.assertEqual(snapshot.read_text(), "```java\nclass Map {}\n```")

    def test_errors_skip_file_and_leave_original_unchanged(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = root / "client" / "model"
            model.mkdir(parents=True)
            source = model / "Map.java"
            original = "```java\nclass Map {\n```\n"
            source.write_text(original)
            self.assertEqual(run(root), 0)
            self.assertEqual(source.read_text(), original)

    def test_missing_directory_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(NotADirectoryError):
                run(Path(directory) / "missing")

    def test_ambiguous_blocks_preserve_original_bytes_and_continue(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = root / "client" / "model"
            model.mkdir(parents=True)
            source = model / "Map.java"
            original = (
                b"Here's one version.\r\n```java\r\nclass Map {}\r\n```\r\n"
                b"Actually, here's another.\r\n```java\r\nclass Map {}\r\n```\r\n"
            )
            source.write_bytes(original)
            valid = model / "Valid.java"
            valid.write_text("```java\nclass Valid {}\n```\n")
            with patch("builtins.print") as output:
                self.assertEqual(run(root), 1)
            output.assert_any_call(
                f"Skipped {source}: Cannot safely combine multiple fenced Java blocks",
                flush=True,
            )
            self.assertEqual(source.read_bytes(), original)
            self.assertEqual(valid.read_text(), annotate_source("class Valid {}\n"))

    def test_incomplete_and_other_language_blocks_skip_annotation(self):
        for original in (
            "```java\nclass Map {}\n",
            "```python\nclass Map {}\n```\n",
        ):
            with self.subTest(original=original), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                model = root / "client" / "model"
                model.mkdir(parents=True)
                source = model / "Map.java"
                source.write_text(original)
                with patch("pipeline.preprocessor.preprocessor.annotate_source") as annotate:
                    self.assertEqual(run(root), 0)
                annotate.assert_not_called()
                self.assertEqual(source.read_text(), original)

    def test_cli_returns_success_even_when_no_files_change(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(main([directory]), 0)


if __name__ == "__main__":
    unittest.main()
