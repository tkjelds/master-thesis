import json
import signal
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from pipeline.clients.copilot import (
    CopilotClientError,
    _json_response,
    _run_in_workspace,
)
from pipeline.clients.harness import HarnessClient
from pipeline.clients.interface import Client, ClientTimeoutError


def message(content, *, turn="0", **data):
    return {
        "type": "assistant.message",
        "data": {
            "content": content, "turnId": turn,
            "phase": "final_answer", "toolRequests": [], **data,
        },
    }


def turn_end(turn="0"):
    return {"type": "assistant.turn_end", "data": {"turnId": turn}}


def jsonl(*events):
    return "\n".join(json.dumps(event) for event in events)


def completed(content="public class Example {}"):
    return subprocess.CompletedProcess(
        [], 0,
        jsonl(message(content), turn_end(), {"type": "result", "exitCode": 0}),
        "",
    )


class HarnessClientTests(unittest.TestCase):
    @patch("pipeline.clients.copilot._run_in_workspace")
    def test_scopes_enabled_tools_to_fresh_workspaces_and_cleans_up(self, execute):
        directories = []

        def respond(command, cwd):
            self.assertTrue(cwd.is_dir())
            self.assertEqual(list(cwd.iterdir()), [])
            directories.append(cwd)
            (cwd / "Scratch.java").write_text("scratch")
            return completed()

        execute.side_effect = respond
        client: Client = HarnessClient("model", runs=2)
        for prompt in ("first prompt", "second prompt"):
            self.assertEqual(client.call(prompt), "public class Example {}")
            command = execute.call_args.args[0]
            self.assertEqual(command[:5], ["copilot", "-p", prompt, "--model", "model"])
            self.assertIn(
                "--available-tools=bash,read_bash,stop_bash,list_bash,"
                "view,rg,glob,apply_patch,web_fetch",
                command,
            )
            for flag in (
                "--allow-all-tools", "--allow-all-urls", "--disallow-temp-dir",
                "--disable-builtin-mcps", "--no-custom-instructions", "--no-ask-user",
                "--sync-session=off", "--no-auto-update",
            ):
                self.assertIn(flag, command)
            for flag in (
                "--allow-all", "--yolo", "--allow-all-paths", "--add-dir",
                "--enable-memory", "--resume", "--continue", "--fleet",
            ):
                self.assertNotIn(flag, command)
        self.assertNotEqual(*directories)
        self.assertTrue(all(not path.exists() for path in directories))

    def test_rejects_invalid_run_counts(self):
        for runs in (0, -1, True, 1.5, "3", None):
            with self.subTest(runs=runs):
                with self.assertRaisesRegex(ValueError, "positive integer"):
                    HarnessClient("model", runs=runs)

    def test_returns_only_completed_top_level_answer_after_tools(self):
        source = 'public class Example { String value = "' + "a" * 160 + '"; }'
        for phase in ("final_answer", None):
            with self.subTest(phase=phase):
                output = jsonl(
                    message(
                        "I will compile this.", phase="commentary",
                        toolRequests=[{"toolCallId": "shell-1", "name": "bash"}],
                    ),
                    {"type": "tool.execution_start", "data": {"toolCallId": "shell-1"}},
                    {"type": "tool.execution_complete", "data": {"result": "not Java"}},
                    turn_end(),
                    message("nested text", parentToolCallId="nested-1"),
                    {**message("agent text"), "agentId": "agent-1"},
                    {"type": "assistant.reasoning", "data": {"content": "not Java"}},
                    message(source, turn="1", phase=phase),
                    turn_end("1"),
                    {"type": "result", "exitCode": 0},
                )
                self.assertEqual(_json_response(output, final_only=True), source)

    def test_rejects_progress_partial_failed_and_missing_final_responses(self):
        success = {"type": "result", "exitCode": 0}
        outputs = [
            "",
            "not JSON",
            jsonl(success),
            jsonl(message("progress", phase="commentary"), turn_end(), success),
            jsonl(message(""), turn_end(), success),
            jsonl(message("unfinished"), success),
            jsonl(message("unfinished"), turn_end("other"), success),
            jsonl(message("unfinished"), turn_end()),
            jsonl(message("old"), turn_end(), message(""), turn_end(), success),
            jsonl(
                message("old"), turn_end(),
                {"type": "assistant.turn_start", "data": {"turnId": "1"}},
                success,
            ),
            jsonl(
                message("old"), turn_end(),
                {"type": "tool.execution_start", "data": {}},
                success,
            ),
            jsonl(message("progress", toolRequests=[{"name": "bash"}]), turn_end(), success),
            jsonl(message("source", toolRequests="invalid"), turn_end(), success),
            jsonl(message("source", turn=None), turn_end(), success),
            jsonl(message("source"), turn_end(), {"type": "result", "exitCode": 1}),
            jsonl(message("source"), turn_end(), {"type": "result", "exitCode": True}),
            jsonl(message("source"), turn_end(), {"type": "session.error", "data": {}}),
            jsonl(message("source"), turn_end(), {"type": "abort"}, success),
        ]
        for output in outputs:
            with self.subTest(output=output):
                with self.assertRaises(CopilotClientError):
                    _json_response(output, final_only=True)

    @patch("builtins.print")
    @patch("pipeline.clients.copilot._run_in_workspace")
    def test_retries_timeouts_with_existing_budget_and_cleans_failed_workspace(
        self, execute, output,
    ):
        directories = []

        def respond(command, cwd):
            directories.append(cwd)
            if len(directories) <= 6:
                raise subprocess.TimeoutExpired(command, 300, output="partial source")
            self.assertEqual(list(cwd.iterdir()), [])
            return completed()

        execute.side_effect = respond
        client = HarnessClient("model")
        with self.assertRaisesRegex(CopilotClientError, "exhausted 5 retries") as raised:
            client.call("first")
        self.assertIsInstance(raised.exception, ClientTimeoutError)
        self.assertEqual(raised.exception.attempts, 6)
        self.assertEqual(execute.call_count, 6)
        self.assertEqual(output.call_count, 5)
        self.assertEqual(len(set(directories)), 1)
        self.assertFalse(directories[0].exists())
        self.assertEqual(client.call("second"), "public class Example {}")
        self.assertNotEqual(directories[0], directories[-1])
        self.assertFalse(directories[-1].exists())

    @patch("builtins.print")
    @patch("pipeline.clients.copilot._run_in_workspace")
    def test_accepts_last_timeout_retry(self, execute, output):
        execute.side_effect = [
            subprocess.TimeoutExpired("copilot", 300) for _ in range(5)
        ] + [completed()]
        self.assertEqual(HarnessClient("model").call("prompt"), "public class Example {}")
        self.assertEqual(execute.call_count, 6)
        self.assertTrue(all(
            call == execute.call_args_list[0] for call in execute.call_args_list
        ))

    @patch("pipeline.clients.copilot._run_in_workspace")
    def test_does_not_retry_other_failures_and_removes_workspace(self, execute):
        for failure in (
            FileNotFoundError("copilot"),
            subprocess.CompletedProcess([], 1, "", "authentication failed"),
            subprocess.CompletedProcess([], 0, "invalid JSON", ""),
        ):
            with self.subTest(failure=failure):
                execute.reset_mock()
                execute.side_effect = [failure]
                with self.assertRaises(CopilotClientError):
                    HarnessClient("model").call("prompt")
                execute.assert_called_once()
                self.assertFalse(execute.call_args.args[1].exists())

    @unittest.skipUnless(sys.platform != "win32", "POSIX process groups")
    @patch("pipeline.clients.copilot.os.killpg")
    @patch("pipeline.clients.copilot.subprocess.Popen")
    def test_timeout_kills_only_its_process_group_and_drains_output(self, popen, killpg):
        process = popen.return_value.__enter__.return_value
        process.pid = 12345
        process.communicate.side_effect = [
            subprocess.TimeoutExpired("copilot", 300), ("partial", ""),
        ]
        with self.assertRaises(subprocess.TimeoutExpired):
            _run_in_workspace(["copilot"], Path("/scratch"))
        killpg.assert_called_once_with(12345, signal.SIGKILL)
        self.assertEqual(process.communicate.call_count, 2)
        self.assertTrue(popen.call_args.kwargs["start_new_session"])
        self.assertEqual(popen.call_args.kwargs["stdin"], subprocess.DEVNULL)

    @unittest.skipUnless(sys.platform != "win32", "POSIX process groups")
    def test_workspace_process_captures_output_and_terminates_on_timeout(self):
        with tempfile.TemporaryDirectory() as directory:
            cwd = Path(directory)
            result = _run_in_workspace(
                [sys.executable, "-c", "import sys; print('ok'); print('detail', file=sys.stderr)"],
                cwd,
            )
            self.assertEqual((result.returncode, result.stdout, result.stderr), (0, "ok\n", "detail\n"))
            with patch("pipeline.clients.copilot.TIMEOUT_SECONDS", 0.1):
                with self.assertRaises(subprocess.TimeoutExpired):
                    _run_in_workspace(
                        [sys.executable, "-c", "import time; time.sleep(60)"], cwd,
                    )
