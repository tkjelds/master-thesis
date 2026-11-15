import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from pipeline.preprocessor.thread_safe import (
    annotate_source,
    main,
    run,
    thread_safe_aliases,
)
from pipeline.verifier.java_verifier import compile_file, run as verify


class ThreadSafeTests(unittest.TestCase):
    def test_repeated_annotation_returns_identical_preprocessed_source(self):
        sources = (
            "",
            "public class Map {}",
            "package example;\r\npublic final class Map<T> {}\r\n",
            "class Map { static class Node {} }\nclass Helper {}",
            "class Map { @interface ThreadSafe {} }",
            "@ThreadSafe\nclass Map {}",
            "@example.ThreadSafe\nclass Map {}",
            '@SuppressWarnings("unchecked")\nclass Map {}',
            'class Map { String text = "@ThreadSafe class Fake {}"; }',
        )
        for source in sources:
            with self.subTest(source=source):
                processed = annotate_source(source)
                for _ in range(3):
                    self.assertEqual(annotate_source(processed), processed)

    def test_repeated_annotation_does_not_rewrite_preprocessed_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = root / "client" / "model"
            model.mkdir(parents=True)
            source = model / "Map.java"
            source.write_text("public class Map {}\n")
            self.assertEqual(run(root), 1)
            contents = source.read_bytes()
            modified = source.stat().st_mtime_ns
            with patch.object(Path, "write_text") as write:
                for _ in range(3):
                    self.assertEqual(run(root), 0)
                write.assert_not_called()
            self.assertEqual(source.read_bytes(), contents)
            self.assertEqual(source.stat().st_mtime_ns, modified)

    def test_adds_self_contained_annotation_and_is_idempotent(self):
        source = "package example;\nimport java.util.List;\n\npublic final class Map {}"
        annotated = annotate_source(source)
        self.assertEqual(
            annotated,
            "package example;\nimport java.util.List;\n\n"
            "@Map.ThreadSafe\npublic final class Map {\n"
            "    @interface ThreadSafe {}\n}",
        )
        self.assertEqual(annotate_source(annotated), annotated)

    def test_preserves_existing_simple_and_qualified_annotations(self):
        for annotation in (
            "@ThreadSafe", "@example.ThreadSafe", "@Map.ThreadSafe",
            '@example.ThreadSafe(value = "checked")',
        ):
            with self.subTest(annotation=annotation):
                source = f"{annotation}\npublic class Map {{}}"
                self.assertEqual(annotate_source(source), source)

    def test_reuses_existing_nested_annotation_definition(self):
        source = "class Map { @interface ThreadSafe {} }"
        annotated = annotate_source(source)
        self.assertEqual(annotated, "@Map.ThreadSafe\n" + source)
        self.assertEqual(annotate_source(annotated), annotated)

    def test_infer_aliases_use_binary_names_and_ignore_comments_and_literals(self):
        source = (
            "/* @Fake.ThreadSafe */\n"
            "@pkg.Map.ThreadSafe class Map {"
            'String s = "@Other.ThreadSafe"; }\n'
            "@ Counter . ThreadSafe class Counter {}\n"
            "@Counter.ThreadSafe class Helper {}\n"
            "@ThreadSafe class Existing {}"
        )
        self.assertEqual(
            thread_safe_aliases(source), ["Counter$ThreadSafe", "Map$ThreadSafe"]
        )

    def test_ignores_comments_strings_characters_and_text_blocks(self):
        source = '''// class Fake {} @ThreadSafe
/* @ThreadSafe class Other {} */
public class Map {
    String text = "class Fake {} \\" @ThreadSafe";
    char brace = '}';
    String block = """
        class Fake {}
        @ThreadSafe
        """;
}
'''
        annotated = annotate_source(source)
        self.assertEqual(annotated.count("@Map.ThreadSafe"), 1)
        self.assertEqual(annotated.count("@interface ThreadSafe {}"), 1)
        self.assertTrue(annotated.startswith("// class Fake"))
        self.assertEqual(annotate_source(annotated), annotated)

    def test_annotates_each_top_level_class_but_not_nested_or_local_classes(self):
        source = (
            "public class Map { static class Node {} "
            "void method() { class Local {} } }\n"
            "class Helper {}"
        )
        annotated = annotate_source(source)
        self.assertIn("@Map.ThreadSafe\npublic class Map", annotated)
        self.assertIn("@Helper.ThreadSafe\nclass Helper", annotated)
        self.assertNotIn("@Node.ThreadSafe", annotated)
        self.assertNotIn("@Local.ThreadSafe", annotated)
        self.assertEqual(annotated.count("@interface ThreadSafe {}"), 2)
        self.assertEqual(annotate_source(annotated), annotated)

    def test_preserves_javadoc_modifiers_and_other_annotations(self):
        source = (
            "/** Documentation. */\n"
            '@SuppressWarnings({"unchecked", "rawtypes"})\n'
            "public abstract class Map<T> {}"
        )
        self.assertEqual(
            annotate_source(source),
            "/** Documentation. */\n@Map.ThreadSafe\n"
            '@SuppressWarnings({"unchecked", "rawtypes"})\n'
            "public abstract class Map<T> {\n    @interface ThreadSafe {}\n}",
        )

    def test_class_literals_and_annotation_arguments_are_not_declarations(self):
        source = (
            "@Example(type = String.class, text = \"@ThreadSafe\", "
            "values = {1, 2})\nclass Map {}"
        )
        annotated = annotate_source(source)
        self.assertTrue(annotated.startswith("@Map.ThreadSafe\n@Example"))
        self.assertEqual(annotated.count("@interface ThreadSafe {}"), 1)

    def test_leaves_interfaces_enums_records_and_annotation_types_alone(self):
        for source in (
            "interface Map {}", "enum Choice { A }", "record Pair(int x) {}",
            "@interface Marker { Class<?> value(); }",
        ):
            with self.subTest(source=source):
                self.assertEqual(annotate_source(source), source)

    def test_rejects_colliding_member_type_and_unclosed_class(self):
        for source in (
            "class Map { class ThreadSafe {} }",
            "class ThreadSafe {}",
            "class Map {",
            "class Map",
            "class",
        ):
            with self.subTest(source=source), self.assertRaises(ValueError):
                annotate_source(source)

    def test_prepares_all_models_before_verification_and_skips_results(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for client, model in (("client-a", "model"), ("client-b", "other")):
                model_dir = root / client / model
                model_dir.mkdir(parents=True)
                (model_dir / "Map.java").write_text("public class Map {}")
                results = model_dir / "results"
                results.mkdir()
                (results / "Snapshot.java").write_text("class Snapshot {}")
            self.assertEqual(run(root), 2)
            self.assertEqual(run(root), 0)
            self.assertTrue(verify(root))
            for snapshot in root.rglob("Snapshot.java"):
                self.assertEqual(snapshot.read_text(), "class Snapshot {}")
            for report in root.rglob("*.results.json"):
                self.assertEqual(len(json.loads(report.read_text())["files"]), 1)

    def test_each_annotated_source_compiles_independently(self):
        sources = {
            "Map": (
                "package example;\npublic class Map<T> {"
                "static class Node {} void f() { class Local {} } }\n"
                "class Helper {}"
            ),
            "Counter": "public class Counter { public int value; }",
        }
        with tempfile.TemporaryDirectory() as directory:
            for name, source in sources.items():
                with self.subTest(name=name):
                    path = Path(directory) / f"{name}.java"
                    path.write_text(annotate_source(source))
                    result = compile_file(path)
                    self.assertTrue(result["compiled"], result["stderr"])

    def test_missing_directory_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(NotADirectoryError):
                run(Path(directory) / "missing")

    def test_errors_identify_the_source_file(self):
        with tempfile.TemporaryDirectory() as directory:
            model = Path(directory) / "client" / "model"
            model.mkdir(parents=True)
            source = model / "Map.java"
            source.write_text("class Map {")
            with self.assertRaisesRegex(ValueError, "Map.java"):
                run(Path(directory))
            self.assertEqual(source.read_text(), "class Map {")

    def test_cli_returns_success_even_when_no_files_change(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(main([directory]), 0)

if __name__ == "__main__":
    unittest.main()
