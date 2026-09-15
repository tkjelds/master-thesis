import subprocess
import unittest
from unittest.mock import patch

from pipeline.clients.copilot import (
    CopilotClient,
    CopilotClientError,
    CopilotClientNoTools,
)


class CopilotClientTests(unittest.TestCase):
    @patch("pipeline.clients.copilot.subprocess.run")
    def test_passes_prompt_and_model_without_using_a_shell(self, run):
        run.return_value = subprocess.CompletedProcess(
            args=[], returncode=0, stdout="response\n", stderr=""
        )

        response = CopilotClient("gpt-5").call("Explain this code")

        self.assertEqual(response, "response")
        run.assert_called_once_with(
            ["copilot", "-p", "Explain this code", "--model", "gpt-5"],
            capture_output=True,
            text=True,
            check=False,
        )

    @patch("pipeline.clients.copilot.subprocess.run")
    def test_raises_nonzero_exit_error(self, run):
        run.return_value = subprocess.CompletedProcess(
            args=[], returncode=2, stdout="", stderr="authentication failed\n"
        )

        with self.assertRaisesRegex(
            CopilotClientError,
            "Copilot CLI exited with status 2: authentication failed",
        ):
            CopilotClient("gpt-5").call("Hello")

    @patch("pipeline.clients.copilot.subprocess.run")
    def test_raises_missing_executable_error(self, run):
        run.side_effect = FileNotFoundError("copilot")

        with self.assertRaisesRegex(CopilotClientError, "Copilot CLI error: copilot"):
            CopilotClient("gpt-5").call("Hello")

    @patch("pipeline.clients.copilot.subprocess.run")
    def test_disables_tools_and_agent_features(self, run):
        run.return_value = subprocess.CompletedProcess(
            args=[], returncode=0, stdout="response\n", stderr=""
        )

        response = CopilotClientNoTools("gpt-5").call("Return plain text")

        self.assertEqual(response, "response")
        run.assert_called_once_with(
            [
                "copilot",
                "-p",
                "Return plain text",
                "--model",
                "gpt-5",
                "--silent",
                "--output-format",
                "text",
                "--stream",
                "off",
                "--available-tools=",
                "--disable-builtin-mcps",
                "--no-custom-instructions",
                "--no-ask-user",
                "--no-remote",
                "--no-remote-export",
                "--no-auto-update",
            ],
            capture_output=True,
            text=True,
            check=False,
        )

if __name__ == "__main__":
    unittest.main()
