import json
import unittest
from unittest.mock import patch

import anthropic
import httpx2
from anthropic.types import Message, RedactedThinkingBlock, TextBlock, ThinkingBlock, ToolUseBlock

from pipeline.clients.anthropic import AnthropicClient, AnthropicClientError
from pipeline.clients.interface import Client, ClientTimeoutError


def response(text="public class Example {}", **changes):
    return Message.model_construct(**({
        "type": "message", "role": "assistant", "stop_reason": "end_turn",
        "content": [TextBlock.model_construct(type="text", text=text)],
    } | changes))


class AnthropicClientTests(unittest.TestCase):
    @patch("pipeline.clients.anthropic.api_key", return_value="synthetic-key")
    @patch("pipeline.clients.anthropic.Anthropic")
    def test_passes_settings_and_makes_independent_nonstreaming_requests(self, sdk, key):
        backend = sdk.return_value.__enter__.return_value
        backend.messages.create.return_value = response("  public class Example {}\n")
        client: Client = AnthropicClient(
            "publisher/model", runs=3, max_output_tokens=1234, timeout=60, max_retries=0,
        )
        for prompt in ("first prompt", "second prompt"):
            self.assertEqual(client.call(prompt), "public class Example {}")
            backend.messages.create.assert_called_with(
                model="publisher/model", messages=[{"role": "user", "content": prompt}],
                max_tokens=1234, stream=False, timeout=60,
            )
        self.assertEqual(backend.messages.create.call_count, 2)
        self.assertEqual(sdk.return_value.__exit__.call_count, 2)
        sdk.assert_called_with(api_key="synthetic-key", timeout=60, max_retries=0)
        key.assert_called_with("ANTHROPIC_API_KEY")

    @patch("pipeline.clients.anthropic.api_key", return_value="synthetic-key")
    def test_default_settings_with_real_sdk_and_mock_http_transport(self, key):
        requests = []

        def handle(request):
            requests.append(request)
            return httpx2.Response(200, json={
                "id": "msg-test", "type": "message", "role": "assistant",
                "model": "model", "stop_reason": "end_turn", "stop_sequence": None,
                "usage": {"input_tokens": 1, "output_tokens": 1},
                "content": [{"type": "text", "text": "source"}],
            })

        http = httpx2.Client(transport=httpx2.MockTransport(handle))
        with patch("pipeline.clients.anthropic.Anthropic", wraps=anthropic.Anthropic) as sdk:
            sdk.side_effect = lambda **kwargs: anthropic.Anthropic(http_client=http, **kwargs)
            self.assertEqual(AnthropicClient("model").call("prompt"), "source")
            sdk.assert_called_once_with(
                api_key="synthetic-key", timeout=815.0, max_retries=2,
            )
        self.assertTrue(http.is_closed)
        self.assertEqual(str(requests[0].url), "https://api.anthropic.com/v1/messages")
        self.assertEqual(json.loads(requests[0].content), {
            "model": "model", "messages": [{"role": "user", "content": "prompt"}],
            "max_tokens": 128_000, "stream": False,
        })

    @patch("pipeline.clients.anthropic.api_key", return_value="synthetic-key")
    @patch("pipeline.clients.anthropic.Anthropic")
    def test_returns_text_in_order_without_thinking_content(self, sdk, key):
        sdk.return_value.__enter__.return_value.messages.create.return_value = response(
            content=[
                ThinkingBlock.model_construct(type="thinking", thinking="private", signature="s"),
                TextBlock.model_construct(type="text", text="public class Example {"),
                RedactedThinkingBlock.model_construct(type="redacted_thinking", data="private"),
                TextBlock.model_construct(type="text", text="}\n"),
            ],
        )
        self.assertEqual(AnthropicClient("model").call("prompt"), "public class Example {\n}")

    @patch("pipeline.clients.anthropic.api_key", return_value="synthetic-key")
    @patch("pipeline.clients.anthropic.Anthropic")
    def test_rejects_failed_truncated_refused_empty_and_unexpected_output(self, sdk, key):
        documents = [
            *(response(stop_reason=reason) for reason in (
                "max_tokens", "refusal", "tool_use", "pause_turn",
                "model_context_window_exceeded", "stop_sequence", None,
            )),
            response(" \n"), response(content=[]), response(content=None),
            response(role="user"), response(type="unexpected"),
            response(content=[TextBlock.model_construct(type="text", text=None)]),
            Message.model_construct(type="message", stop_reason="end_turn"),
            response(content=[TextBlock.model_construct(type="text")]),
            response(content=[ToolUseBlock.model_construct(
                type="tool_use", id="tool-test", name="tool", input={},
            )]),
            None,
        ]
        for document in documents:
            with self.subTest(response=document):
                sdk.return_value.__enter__.return_value.messages.create.return_value = document
                with self.assertRaises(AnthropicClientError):
                    AnthropicClient("model").call("prompt")

    @patch("pipeline.clients.anthropic.api_key", return_value="synthetic-key")
    @patch("pipeline.clients.anthropic.Anthropic")
    def test_wraps_sdk_failures_without_leaking_body_or_credential(self, sdk, key):
        request = httpx2.Request("POST", "https://api.anthropic.com/v1/messages")
        errors = [
            anthropic.APIStatusError(
                "synthetic-key private prompt", response=httpx2.Response(401, request=request),
                body={"secret": "synthetic-key"},
            ),
            anthropic.APIConnectionError(request=request, message="synthetic-key"),
            anthropic.APITimeoutError(request),
            json.JSONDecodeError("synthetic-key", "", 0),
        ]
        for error in errors:
            with self.subTest(error=type(error)):
                sdk.return_value.__enter__.return_value.messages.create.side_effect = error
                with self.assertRaises(AnthropicClientError) as raised:
                    AnthropicClient("model").call("private prompt")
                message = str(raised.exception)
                self.assertNotIn("synthetic-key", message)
                self.assertNotIn("private prompt", message)
                self.assertTrue(raised.exception.__suppress_context__)
                if isinstance(error, anthropic.APITimeoutError):
                    self.assertIsInstance(raised.exception, ClientTimeoutError)
                    self.assertEqual(raised.exception.attempts, 3)
                else:
                    self.assertNotIsInstance(raised.exception, ClientTimeoutError)
                if isinstance(error, anthropic.APIStatusError):
                    self.assertIn("HTTP 401", message)
