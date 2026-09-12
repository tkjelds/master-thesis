import json
import tempfile
import unittest
from pathlib import Path

from pipeline.prompt_generator.generator import build_prompts, write_prompts


class PromptGeneratorTests(unittest.TestCase):
    def test_builds_one_prompt_per_data_structure(self):
        prompts = build_prompts()

        self.assertEqual(len(prompts), 12)
        self.assertTrue(all("$datastructure" not in prompt for prompt in prompts))
        self.assertIn("the map data structure", prompts[0])

    def test_writes_timestamped_structured_output(self):
        with tempfile.TemporaryDirectory() as directory:
            output = write_prompts(Path(directory))
            document = json.loads(output.read_text(encoding="utf-8"))

            self.assertRegex(output.name, r"^data:\d{4}-\d{2}-\d{2}T")
            self.assertIn("date", document["header"])
            self.assertIn("timestamp", document["header"])
            self.assertEqual(document["header"]["count"], len(document["prompts"]))
            self.assertEqual(document["prompts"], build_prompts())


if __name__ == "__main__":
    unittest.main()
