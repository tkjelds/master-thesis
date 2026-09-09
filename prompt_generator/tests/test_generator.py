import json
import tempfile
import unittest
from pathlib import Path

from prompt_generator.generator import build_output, read_csv_values


class PromptGeneratorTests(unittest.TestCase):
    def test_generates_cartesian_product_for_multiple_wildcards(self):
        document = build_output(
            "Use $LANG with $DATASTRUCTURE.",
            {"LANG": ["Java", "C++"], "DATASTRUCTURE": ["a stack", "a queue"]},
        )

        self.assertEqual(document["count"], 4)
        self.assertEqual(
            [item["prompt"] for item in document["prompts"]],
            [
                "Use Java with a stack.",
                "Use Java with a queue.",
                "Use C++ with a stack.",
                "Use C++ with a queue.",
            ],
        )

    def test_reads_named_and_single_column_csv_files(self):
        with tempfile.TemporaryDirectory() as directory:
            directory_path = Path(directory)
            named = directory_path / "languages.csv"
            named.write_text("LANG\nJava\nPython\n", encoding="utf-8")
            single = directory_path / "structures.csv"
            single.write_text("stack\nqueue\n", encoding="utf-8")

            self.assertEqual(read_csv_values(named, "LANG"), ["Java", "Python"])
            self.assertEqual(read_csv_values(single, "DATASTRUCTURE"), ["stack", "queue"])

    def test_output_is_json_serializable(self):
        document = build_output("Say $WORD", {"WORD": ["hello"]})
        self.assertEqual(
            json.loads(json.dumps(document))["prompts"][0]["prompt"], "Say hello"
        )

    def test_rejects_missing_mapping(self):
        with self.assertRaisesRegex(ValueError, "Missing CSV mapping"):
            build_output("Use $LANG", {})


if __name__ == "__main__":
    unittest.main()
