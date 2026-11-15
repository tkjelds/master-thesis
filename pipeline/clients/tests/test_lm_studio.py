import json
import subprocess
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

from pipeline.clients.interface import Client, ClientTimeoutError
from pipeline.clients.lm_studio import LMStudioClient, LMStudioClientError


def response(output):
    return subprocess.CompletedProcess(
        args=[], returncode=0, stdout=json.dumps({"output": output}), stderr=""
    )


class LMStudioClientTests(unittest.TestCase):
    def test_skip_runs_defaults_to_zero_and_validates_range(self):
        self.assertEqual(LMStudioClient("model").skip_runs, 0)
        self.assertEqual(LMStudioClient("model", runs=3, skip_runs=2).skip_runs, 2)
        for skip_runs in (-1, True, False, 1.5, "1", None, 1):
            with self.subTest(skip_runs=skip_runs):
                with self.assertRaisesRegex(ValueError, "skip_runs"):
                    LMStudioClient("model", skip_runs=skip_runs)

    @patch("pipeline.clients.lm_studio.subprocess.run")
    def test_explicit_reasoning_is_sent_without_changing_api_model(self, run):
        run.return_value = response([{"type": "message", "content": "response"}])
        for reasoning in ("off", "on"):
            with self.subTest(reasoning=reasoning):
                run.reset_mock()
                client = LMStudioClient("publisher/model", reasoning=reasoning)
                self.assertEqual(client.call("prompt"), "response")
                run.assert_called_once()
                self.assertEqual(json.loads(run.call_args.kwargs["input"]), {
                    "model": "publisher/model", "input": "prompt",
                    "stream": False, "store": False, "reasoning": reasoning,
                })
                self.assertEqual(
                    client.output_model_name, f"publisher%2Fmodel@reasoning-{reasoning}"
                )

    def test_reasoning_defaults_to_server_setting_and_rejects_unsupported_values(self):
        self.assertIsNone(LMStudioClient("model").reasoning)
        for value in ("", "low", "medium", "high", True, 0, [], {}):
            with self.subTest(value=value):
                with self.assertRaisesRegex(ValueError, "reasoning"):
                    LMStudioClient("model", reasoning=value)

    def test_reasoning_output_names_do_not_collide_with_literal_model_identifiers(self):
        names = {
            LMStudioClient("publisher/model").output_model_name,
            LMStudioClient("publisher/model", reasoning="off").output_model_name,
            LMStudioClient("publisher/model", reasoning="on").output_model_name,
            LMStudioClient("publisher/model@reasoning-off").output_model_name,
            LMStudioClient("publisher/model@reasoning-on").output_model_name,
        }
        self.assertEqual(len(names), 5)
        self.assertEqual(
            LMStudioClient("..", reasoning="off").output_model_name,
            "%2E%2E@reasoning-off",
        )

    @patch("builtins.print")
    @patch("pipeline.clients.lm_studio.subprocess.run")
    def test_timeout_retries_preserve_reasoning_setting(self, run, output):
        run.side_effect = [
            subprocess.CompletedProcess(
                args=[], returncode=28, stdout="", stderr="timed out"
            ),
            response([{"type": "message", "content": "response"}]),
        ]
        self.assertEqual(
            LMStudioClient("model", reasoning="off").call("prompt"), "response"
        )
        self.assertEqual(run.call_count, 2)
        self.assertEqual(run.call_args_list[0], run.call_args_list[1])
        self.assertEqual(json.loads(run.call_args.kwargs["input"])["reasoning"], "off")

    def test_timeout_retries_default_to_five_and_validate_configuration(self):
        self.assertEqual(LMStudioClient("model").timeout_retries, 5)
        for count in (0, 1, 5):
            with self.subTest(count=count):
                self.assertEqual(
                    LMStudioClient("model", timeout_retries=count).timeout_retries,
                    count,
                )
        for count in (-1, 6, 1.5, "5", None, True, False):
            with self.subTest(count=count):
                with self.assertRaisesRegex(ValueError, "timeout_retries"):
                    LMStudioClient("model", timeout_retries=count)

    @patch("builtins.print")
    @patch("pipeline.clients.lm_studio.subprocess.run")
    def test_retries_identical_request_after_timeout_and_returns_success(self, run, output):
        run.side_effect = [
            subprocess.CompletedProcess(
                args=[], returncode=28, stdout="partial response", stderr="timed out"
            ),
            response([{"type": "message", "content": "complete response"}]),
        ]
        self.assertEqual(LMStudioClient("model").call("prompt"), "complete response")
        self.assertEqual(run.call_count, 2)
        self.assertEqual(run.call_args_list[0], run.call_args_list[1])
        output.assert_called_once_with(
            "LM Studio model: request timed out after 815 seconds; retrying (1/5).",
            flush=True,
        )

    @patch("builtins.print")
    @patch("pipeline.clients.lm_studio.subprocess.run")
    def test_stops_after_five_timeout_retries_and_reports_exhaustion(self, run, output):
        run.return_value = subprocess.CompletedProcess(
            args=[], returncode=28, stdout="partial", stderr="operation timed out"
        )
        with self.assertRaisesRegex(
            LMStudioClientError, "status 28 after 6 attempt.*operation timed out"
        ):
            LMStudioClient("model").call("prompt")
        self.assertEqual(run.call_count, 6)
        self.assertEqual(output.call_count, 5)
        self.assertIn("(5/5)", output.call_args.args[0])
        self.assertTrue(all(call == run.call_args_list[0] for call in run.call_args_list))

    @patch("builtins.print")
    @patch("pipeline.clients.lm_studio.subprocess.run")
    def test_timeout_retry_budget_can_be_lowered_or_disabled(self, run, output):
        run.return_value = subprocess.CompletedProcess(
            args=[], returncode=28, stdout="", stderr="timed out"
        )
        for retries in (0, 1, 3):
            with self.subTest(retries=retries):
                run.reset_mock()
                output.reset_mock()
                with self.assertRaises(LMStudioClientError) as raised:
                    LMStudioClient("model", timeout_retries=retries).call("prompt")
                self.assertIsInstance(raised.exception, ClientTimeoutError)
                self.assertEqual(raised.exception.attempts, retries + 1)
                self.assertEqual(run.call_count, retries + 1)
                self.assertEqual(output.call_count, retries)

    @patch("builtins.print")
    @patch("pipeline.clients.lm_studio.subprocess.run")
    def test_final_allowed_retry_can_succeed(self, run, output):
        timeout = subprocess.CompletedProcess(
            args=[], returncode=28, stdout="", stderr="timed out"
        )
        run.side_effect = [
            *([timeout] * 5),
            response([{"type": "message", "content": "response"}]),
        ]
        self.assertEqual(LMStudioClient("model").call("prompt"), "response")
        self.assertEqual(run.call_count, 6)
        self.assertEqual(output.call_count, 5)

    @patch("builtins.print")
    @patch("pipeline.clients.lm_studio.subprocess.run")
    def test_each_call_has_its_own_timeout_retry_budget(self, run, output):
        timeout = subprocess.CompletedProcess(
            args=[], returncode=28, stdout="", stderr="timed out"
        )
        success = response([{"type": "message", "content": "response"}])
        run.side_effect = [timeout, success, timeout, success]
        client = LMStudioClient("model", timeout_retries=1)
        self.assertEqual(client.call("first"), "response")
        self.assertEqual(client.call("second"), "response")
        self.assertEqual(run.call_count, 4)
        self.assertEqual(output.call_count, 2)

    @patch("builtins.print")
    @patch("pipeline.clients.lm_studio.subprocess.run")
    def test_non_timeout_error_after_timeout_fails_without_further_retries(self, run, output):
        run.side_effect = [
            subprocess.CompletedProcess(
                args=[], returncode=28, stdout="", stderr="timed out"
            ),
            subprocess.CompletedProcess(
                args=[], returncode=22, stdout="", stderr="HTTP 500"
            ),
        ]
        with self.assertRaisesRegex(LMStudioClientError, "HTTP 500"):
            LMStudioClient("model").call("prompt")
        self.assertEqual(run.call_count, 2)
        self.assertEqual(output.call_count, 1)

    def test_skip_chats_defaults_to_zero_and_accepts_nonnegative_integers(self):
        self.assertEqual(LMStudioClient("model").skip_chats, 0)
        for count in (0, 1, 14, 100):
            with self.subTest(count=count):
                self.assertEqual(LMStudioClient("model", skip_chats=count).skip_chats, count)

    def test_rejects_invalid_skip_chats(self):
        for count in (-1, 1.5, "14", None, True, False):
            with self.subTest(count=count):
                with self.assertRaisesRegex(ValueError, "skip_chats"):
                    LMStudioClient("model", skip_chats=count)

    @patch("pipeline.clients.lm_studio.subprocess.run")
    def test_skip_chats_does_not_change_direct_calls_or_api_payload(self, run):
        run.return_value = response([{"type": "message", "content": "response"}])
        client = LMStudioClient("model", skip_chats=14)
        self.assertEqual(client.call("prompt"), "response")
        run.assert_called_once()
        self.assertEqual(json.loads(run.call_args.kwargs["input"]), {
            "model": "model", "input": "prompt", "stream": False, "store": False,
        })

    @patch("pipeline.clients.lm_studio.subprocess.run")
    def test_defaults_and_exact_request(self, run):
        run.return_value = response([{"type": "message", "content": "response\n"}])
        client: Client = LMStudioClient("publisher/model")

        self.assertEqual(client.call("Explain this code"), "response")
        run.assert_called_once_with(
            [
                "curl", "--disable", "--silent", "--show-error", "--fail-with-body",
                "--max-time", "815",
                "--header", "Content-Type: application/json",
                "--data-binary", "@-", "--url", "http://localhost:1234/api/v1/chat",
            ],
            input=json.dumps({
                "model": "publisher/model",
                "input": "Explain this code",
                "stream": False,
                "store": False,
            }),
            capture_output=True,
            text=True,
            encoding="utf-8",
            check=False,
        )

    @patch("pipeline.clients.lm_studio.subprocess.run")
    def test_custom_host_and_independent_calls_with_815_second_timeout(self, run):
        run.return_value = response([{"type": "message", "content": "response"}])
        client = LMStudioClient("model", api_host="127.0.0.1:4321")
        for prompt in ("first", "second"):
            client.call(prompt)
        self.assertEqual(run.call_count, 2)
        for prompt, invocation in zip(("first", "second"), run.call_args_list):
            command = invocation.args[0]
            self.assertEqual(command[-1], "http://127.0.0.1:4321/api/v1/chat")
            self.assertEqual(command[command.index("--max-time") + 1], "815")
            self.assertNotIn("--connect-timeout", command)
            self.assertNotIn("timeout", invocation.kwargs)
            self.assertEqual(json.loads(invocation.kwargs["input"]), {
                "model": "model", "input": prompt, "stream": False, "store": False,
            })

    @patch("pipeline.clients.lm_studio.subprocess.run")
    def test_returns_only_messages_in_order(self, run):
        run.return_value = response([
            {"type": "reasoning", "content": "private reasoning"},
            {"type": "tool_call", "output": "tool result"},
            {"type": "message", "content": "public class Map {"},
            {"type": "invalid_tool_call", "reason": "invalid"},
            {"type": "reasoning", "content": "more reasoning"},
            {"type": "message", "content": "}\n"},
        ])
        self.assertEqual(
            LMStudioClient("model").call("prompt"), "public class Map {\n}"
        )

    def test_rejects_invalid_configuration(self):
        configurations = [
            {"model": ""},
            {"model": " \n"},
            {"model": None},
            *({"runs": runs} for runs in (0, -1, True, False, 1.5, "3", None)),
            *({"api_host": host} for host in (
                "", None, "localhost", "localhost:0", "localhost:bad",
                "localhost:99999", "http://localhost:1234",
                "https://localhost:1234", "localhost:1234/v1",
                "user@localhost:1234", "localhost:1234?key=value",
                "localhost:1234?", "localhost:1234#fragment", "local host:1234",
            )),
        ]
        for configuration in configurations:
            with self.subTest(configuration=configuration):
                with self.assertRaises(ValueError):
                    LMStudioClient(**({"model": "model"} | configuration))

    def test_accepts_ipv6_host(self):
        self.assertEqual(
            LMStudioClient("model", api_host="[::1]:1234").api_host, "[::1]:1234"
        )

    def test_safe_collision_free_model_names(self):
        models = {
            "model": "model",
            "publisher/model": "publisher%2Fmodel",
            "publisher%2Fmodel": "publisher%252Fmodel",
            "/model": "%2Fmodel",
            "../model": "..%2Fmodel",
            ".": "%2E",
            "..": "%2E%2E",
            "%2E": "%252E",
            "a\\b": "a%5Cb",
            "a:b": "a%3Ab",
            "a b": "a%20b",
            "m\u00f8del": "m%C3%B8del",
        }
        for model, expected in models.items():
            with self.subTest(model=model):
                client = LMStudioClient(model)
                self.assertEqual(client.model, model)
                self.assertEqual(client.output_model_name, expected)
        self.assertEqual(len(set(models.values())), len(models))

    @patch("pipeline.clients.lm_studio.subprocess.run")
    def test_rejects_missing_empty_or_nontext_messages(self, run):
        outputs = [
            [],
            [{"type": "reasoning", "content": "not an answer"}],
            [{"type": "tool_call", "output": "not an answer"}],
            *([{"type": "message", "content": content}]
              for content in (None, "", " \n", [], 42)),
            [{"type": "message"}],
            [None],
            [{}],
        ]
        for output in outputs:
            with self.subTest(output=output):
                run.reset_mock()
                run.return_value = response(output)
                with self.assertRaises(LMStudioClientError):
                    LMStudioClient("model").call("prompt")
                run.assert_called_once()

    @patch("pipeline.clients.lm_studio.subprocess.run")
    def test_rejects_invalid_json_or_response_shape(self, run):
        for body in ("not JSON", "null", "[]", "{}", '{"output": {}}'):
            with self.subTest(body=body):
                run.reset_mock()
                run.return_value = subprocess.CompletedProcess(
                    args=[], returncode=0, stdout=body, stderr=""
                )
                with self.assertRaises(LMStudioClientError):
                    LMStudioClient("model").call("prompt")
                run.assert_called_once()

    @patch("pipeline.clients.lm_studio.subprocess.run")
    def test_reports_curl_http_connection_and_timeout_errors(self, run):
        for code, detail in (
            (22, "HTTP 404"), (7, "connection refused"), (28, "operation timed out"),
        ):
            with self.subTest(code=code):
                run.reset_mock()
                run.return_value = subprocess.CompletedProcess(
                    args=[], returncode=code, stdout="", stderr=detail
                )
                with self.assertRaisesRegex(LMStudioClientError, detail):
                    LMStudioClient("model").call("prompt")
                self.assertEqual(run.call_count, 6 if code == 28 else 1)

    @patch("pipeline.clients.lm_studio.subprocess.run")
    def test_translates_missing_executable(self, run):
        error = FileNotFoundError("curl")
        run.side_effect = error
        with self.assertRaises(LMStudioClientError) as raised:
            LMStudioClient("model").call("prompt")
        self.assertIs(raised.exception.__cause__, error)

    def test_real_curl_against_local_http_server(self):
        requests = []
        replies = [
            (200, json.dumps({"output": [
                {"type": "reasoning", "content": "do not return this"},
                {"type": "message", "content": "public class Map {}\n"},
            ]})),
            (404, '{"error":"model not found"}'),
            (200, "not JSON"),
        ]

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                length = int(self.headers["Content-Length"])
                requests.append((self.path, json.loads(self.rfile.read(length))))
                status, body = replies.pop(0)
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(body.encode("utf-8"))

            def log_message(self, format, *args):
                pass

        with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
            thread = threading.Thread(target=server.serve_forever)
            thread.start()
            try:
                client = LMStudioClient(
                    "publisher/model", api_host=f"127.0.0.1:{server.server_port}",
                )
                self.assertEqual(client.call('prompt "\n'), "public class Map {}")
                with self.assertRaisesRegex(LMStudioClientError, "404"):
                    client.call("prompt")
                with self.assertRaisesRegex(LMStudioClientError, "invalid JSON"):
                    client.call("prompt")
            finally:
                server.shutdown()
                thread.join()
        self.assertEqual(len(requests), 3)
        for path, payload in requests:
            self.assertEqual(path, "/api/v1/chat")
            self.assertEqual(payload["model"], "publisher/model")
            self.assertFalse(payload["stream"])
            self.assertFalse(payload["store"])
            self.assertNotIn("previous_response_id", payload)
        self.assertEqual(requests[0][1]["input"], 'prompt "\n')


if __name__ == "__main__":
    unittest.main()
