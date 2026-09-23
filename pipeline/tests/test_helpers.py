import tempfile
import unittest
from pathlib import Path

from pipeline.helpers import find_latest_file, timestamp_now


class HelperTests(unittest.TestCase):
    def test_timestamp_uses_date_hour_and_minute(self):
        self.assertRegex(
            timestamp_now(),
            r"^date:\d{4}-\d{2}-\d{2}T\d{2}-\d{2}$",
        )

    def test_finds_latest_matching_file_and_ignores_directories(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            older = root / "date:older.json"
            newer = root / "date:newer.json"
            ignored = root / "date:ignored.txt"
            older.write_text("older", encoding="utf-8")
            newer.write_text("newer", encoding="utf-8")
            ignored.write_text("ignored", encoding="utf-8")
            (root / "date:directory.json").mkdir()
            older.touch()
            newer.touch()

            self.assertEqual(find_latest_file(root, "date:*.json"), newer)

    def test_raises_when_no_matching_files_exist(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(FileNotFoundError):
                find_latest_file(Path(directory), "date:*.json")


if __name__ == "__main__":
    unittest.main()
