import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from pipeline.visualisation.visualisation import main, run


class VisualisationTests(unittest.TestCase):
    def setUp(self):
        self.generation = Path("Generations/date:test")
        self.collection_file = Path("visualisation/date:test/collection.json")
        self.collect = self.enterContext(patch(
            "pipeline.visualisation.visualisation.collection.run",
        ))
        self.extract = self.enterContext(patch(
            "pipeline.visualisation.visualisation.extraction.run",
            return_value=self.collection_file.with_name("errors.csv"),
        ))
        self.plot = self.enterContext(patch(
            "pipeline.visualisation.visualisation.plotting.run",
        ))
        self.enterContext(patch(
            "pipeline.visualisation.visualisation.collection.report_path",
            return_value=self.collection_file,
        ))

    def test_collects_extracts_and_plots_the_new_collection(self):
        order = Mock()
        order.attach_mock(self.collect, "collect")
        order.attach_mock(self.extract, "extract")
        order.attach_mock(self.plot, "plot")
        self.assertTrue(run(self.generation))
        self.assertEqual([call[0] for call in order.mock_calls], ["collect", "extract", "plot"])
        self.collect.assert_called_once_with(self.generation)
        self.extract.assert_called_once_with(self.collection_file)
        self.plot.assert_called_once_with(self.collection_file.with_name("errors.csv"))

    def test_collection_failure_does_not_extract_a_stale_index(self):
        self.collect.side_effect = FileNotFoundError("no reports")
        with self.assertRaisesRegex(FileNotFoundError, "no reports"):
            run(self.generation)
        self.extract.assert_not_called()
        self.plot.assert_not_called()

    def test_extraction_failure_propagates_and_cli_returns_nonzero(self):
        self.extract.side_effect = ValueError("missing manifest mapping")
        with self.assertRaisesRegex(ValueError, "missing manifest mapping"):
            run(self.generation)
        with patch("sys.stderr") as errors:
            self.assertEqual(main([str(self.generation)]), 1)
        self.assertIn("missing manifest mapping", str(errors.write.call_args_list))
        self.plot.assert_not_called()

    def test_plotting_failure_propagates_and_cli_returns_nonzero(self):
        self.plot.side_effect = ValueError("invalid results")
        with self.assertRaisesRegex(ValueError, "invalid results"):
            run(self.generation)
        with patch("sys.stderr") as errors:
            self.assertEqual(main([str(self.generation)]), 1)
        self.assertIn("invalid results", str(errors.write.call_args_list))


if __name__ == "__main__":
    unittest.main()
