import subprocess
import unittest
from unittest.mock import patch

from pipeline.clients.copilot import call_copilot


class CopilotClientTests(unittest.TestCase):
    @patch("pipeline.clients.copilot.subprocess.run")
    def test_passes_prompt_and_model_without_using_a_shell(self, run):
        run.return_value = subprocess.CompletedProcess(
            args=[], returncode=0, stdout="response\n", stderr=""
        )

        response = call_copilot("Explain this code", "gpt-5")

        self.assertEqual(response, "response")
        run.assert_called_once_with(
            ["copilot", "-p", "Explain this code", "--model", "gpt-5"],
            capture_output=True,
            text=True,
            check=False,
        )

    @patch("pipeline.clients.copilot.subprocess.run")
    def test_returns_nonzero_exit_error(self, run):
        run.return_value = subprocess.CompletedProcess(
            args=[], returncode=2, stdout="", stderr="authentication failed\n"
        )

        response = call_copilot("Hello", "gpt-5")

        self.assertEqual(
            response,
            "Copilot CLI exited with status 2: authentication failed",
        )

    @patch("pipeline.clients.copilot.subprocess.run")
    def test_returns_missing_executable_error(self, run):
        run.side_effect = FileNotFoundError("copilot")

        response = call_copilot("Hello", "gpt-5")

        self.assertEqual(response, "Copilot CLI error: copilot")


if __name__ == "__main__":
    unittest.main()
