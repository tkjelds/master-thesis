import json
import subprocess
import unittest
from unittest.mock import patch

from pipeline.clients.copilot import (
    CopilotClient,
    CopilotClientError,
    CopilotClientNoTools,
)
from pipeline.clients.interface import ClientTimeoutError


class CopilotClientTests(unittest.TestCase):
    def test_skip_runs_is_configurable_for_both_clients(self):
        for client_type in (CopilotClient, CopilotClientNoTools):
            with self.subTest(client=client_type.__name__):
                self.assertEqual(client_type("model", runs=3, skip_runs=1).skip_runs, 1)
                with self.assertRaisesRegex(ValueError, "skip_runs"):
                    client_type("model", skip_runs=1)

    def test_rejects_invalid_run_counts_for_both_clients(self):
        for client_type in (CopilotClient, CopilotClientNoTools):
            for runs in (0, -1, True, False, 1.5, "3", None):
                with self.subTest(client=client_type.__name__, runs=runs):
                    with self.assertRaisesRegex(ValueError, "runs must be a positive integer"):
                        client_type("model", runs=runs)

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
            timeout=300,
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
            args=[], returncode=0,
            stdout=json.dumps({
                "type": "assistant.message", "data": {"content": "response\n"},
            }),
            stderr="",
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
                "json",
                "--stream",
                "off",
                "--available-tools=",
                "--disable-builtin-mcps",
                "--no-custom-instructions",
                "--no-ask-user",
                "--sync-session=off",
                "--no-auto-update",
            ],
            capture_output=True,
            text=True,
            check=False,
            timeout=300,
        )

    @patch("builtins.print")
    @patch("pipeline.clients.copilot.subprocess.run")
    def test_timeout_retries_identical_request_and_accepts_last_attempt(self, run, output):
        for client_type in (CopilotClient, CopilotClientNoTools):
            with self.subTest(client=client_type.__name__):
                run.reset_mock()
                output.reset_mock()
                response = (
                    json.dumps({"type": "assistant.message", "data": {"content": "response"}})
                    if client_type is CopilotClientNoTools else "response"
                )
                run.side_effect = [
                    subprocess.TimeoutExpired("copilot", 300, output=b"partial")
                    for _ in range(5)
                ] + [
                    subprocess.CompletedProcess(
                        args=[], returncode=0, stdout=response, stderr="",
                    ),
                ]
                self.assertEqual(client_type("model").call("prompt"), "response")
                self.assertEqual(run.call_count, 6)
                first_call = run.call_args_list[0]
                self.assertEqual(first_call.kwargs["timeout"], 300)
                self.assertTrue(all(call == first_call for call in run.call_args_list))
                self.assertEqual(output.call_count, 5)
                output.assert_called_with(
                    "Copilot CLI: request timed out after 300 seconds; retrying (5/5).",
                    flush=True,
                )

    @patch("builtins.print")
    @patch("pipeline.clients.copilot.subprocess.run")
    def test_exhausted_timeout_budget_fails_and_resets_for_next_call(self, run, output):
        run.side_effect = [
            subprocess.TimeoutExpired("copilot", 300, output=b"partial")
            for _ in range(6)
        ] + [
            subprocess.CompletedProcess(
                args=[], returncode=0, stdout="next response", stderr="",
            ),
        ]
        client = CopilotClient("model")
        with self.assertRaisesRegex(
            CopilotClientError, r"300 seconds per attempt; exhausted 5 retries \(6 attempts\)",
        ) as raised:
            client.call("first")
        self.assertIsInstance(raised.exception, ClientTimeoutError)
        self.assertEqual(raised.exception.attempts, 6)
        self.assertEqual(run.call_count, 6)
        self.assertEqual(output.call_count, 5)
        self.assertEqual(client.call("second"), "next response")
        self.assertEqual(run.call_count, 7)

    @patch("builtins.print")
    @patch("pipeline.clients.copilot.subprocess.run")
    def test_non_timeout_failure_after_timeout_is_not_retried(self, run, output):
        for failure, message in (
            (FileNotFoundError("copilot"), "Copilot CLI error"),
            (
                subprocess.CompletedProcess(
                    args=[], returncode=2, stdout="", stderr="authentication failed",
                ),
                "authentication failed",
            ),
            (
                subprocess.CompletedProcess(
                    args=[], returncode=0, stdout="not JSON", stderr="",
                ),
                "Invalid Copilot JSONL response",
            ),
        ):
            with self.subTest(failure=failure):
                run.reset_mock()
                output.reset_mock()
                run.side_effect = [subprocess.TimeoutExpired("copilot", 300), failure]
                with self.assertRaisesRegex(CopilotClientError, message):
                    CopilotClientNoTools("model").call("prompt")
                self.assertEqual(run.call_count, 2)
                output.assert_called_once()

    @patch("pipeline.clients.copilot.subprocess.run")
    def test_preserves_long_java_lines_and_ignores_reasoning_and_metadata(self, run):
        source = 'public class LongLine { String value = "' + "a" * 160 + '"; }'
        events = [
            {"type": "assistant.reasoning", "data": {"content": "not source"}},
            {"type": "assistant.message_delta", "data": {"deltaContent": "not source"}},
            {
                "type": "assistant.message",
                "data": {"content": source, "reasoningText": "not source"},
            },
            {"type": "result", "exitCode": 0, "usage": {}},
        ]
        run.return_value = subprocess.CompletedProcess(
            args=[], returncode=0,
            stdout="\n".join(json.dumps(event) for event in events), stderr="",
        )
        response = CopilotClientNoTools("model").call("generate source")
        self.assertEqual(response, source)
        self.assertNotIn("\n", response)
        self.assertGreater(len(response), 100)

    @patch("pipeline.clients.copilot.subprocess.run")
    def test_joins_assistant_messages_in_order_without_rendering_markdown(self, run):
        contents = ["/**\n * Keep this comment unchanged.\n */", "public class Example {}"]
        run.return_value = subprocess.CompletedProcess(
            args=[], returncode=0,
            stdout="\n".join(
                json.dumps({"type": "assistant.message", "data": {"content": content}})
                for content in contents
            ),
            stderr="",
        )
        self.assertEqual(
            CopilotClientNoTools("model").call("generate source"), "\n".join(contents)
        )

    @patch("pipeline.clients.copilot.subprocess.run")
    def test_rejects_malformed_missing_or_failed_jsonl_responses(self, run):
        outputs = [
            "", "not JSON", "[]", "{}",
            json.dumps({"type": "assistant.message", "data": {"content": ""}}),
            json.dumps({"type": "assistant.message", "data": {"content": 42}}),
            json.dumps({"type": "assistant.reasoning", "data": {"content": "reasoning"}}),
            json.dumps({"type": "session.error", "data": {"message": "model unavailable"}}),
            json.dumps({"type": "result", "exitCode": 1}),
            json.dumps({"type": "result", "exitCode": "0"}),
        ]
        for output in outputs:
            with self.subTest(output=output):
                run.return_value = subprocess.CompletedProcess(
                    args=[], returncode=0, stdout=output, stderr="",
                )
                with self.assertRaises(CopilotClientError):
                    CopilotClientNoTools("model").call("generate source")

    @patch("pipeline.clients.copilot.subprocess.run")
    def test_rejects_empty_text_responses(self, run):
        run.return_value = subprocess.CompletedProcess(
            args=[], returncode=0, stdout=" \n", stderr="",
        )
        with self.assertRaisesRegex(CopilotClientError, "empty response"):
            CopilotClient("model").call("generate source")

    @patch("pipeline.clients.copilot.subprocess.run")
    def test_reports_jsonl_session_error_without_dumping_telemetry(self, run):
        events = [
            {"type": "model.call_failure", "data": {"transport": "telemetry"}},
            {
                "type": "session.error",
                "data": {"message": "connection closed before message completed"},
            },
            {"type": "result", "exitCode": 1},
        ]
        run.return_value = subprocess.CompletedProcess(
            args=[], returncode=1,
            stdout="\n".join(json.dumps(event) for event in events), stderr="",
        )
        with self.assertRaises(CopilotClientError) as raised:
            CopilotClientNoTools("model").call("generate source")
        self.assertEqual(
            str(raised.exception),
            "Copilot CLI exited with status 1: Copilot session error: "
            "connection closed before message completed",
        )

if __name__ == "__main__":
    unittest.main()
