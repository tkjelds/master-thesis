import json
import unittest
from unittest.mock import patch

import httpx2
import openai
from openai.types.responses import (
    Response,
    ResponseOutputMessage,
    ResponseOutputRefusal,
    ResponseOutputText,
    ResponseReasoningItem,
)

from pipeline.clients.interface import Client, ClientTimeoutError
from pipeline.clients.openai import OpenAIClient, OpenAIClientError


def response(text="public class Example {}", **changes):
    message = ResponseOutputMessage.model_construct(
        id="msg-test", type="message", role="assistant", status="completed",
        content=[ResponseOutputText.model_construct(type="output_text", text=text)],
    )
    return Response.model_construct(**({
        "status": "completed", "error": None, "incomplete_details": None, "output": [message],
    } | changes))


class OpenAIClientTests(unittest.TestCase):
    @patch("pipeline.clients.openai.api_key", return_value="synthetic-key")
    @patch("pipeline.clients.openai.OpenAI")
    def test_passes_settings_and_makes_independent_nonstreaming_requests(self, sdk, key):
        backend = sdk.return_value.__enter__.return_value
        backend.responses.create.return_value = response("  public class Example {}\n")
        client: Client = OpenAIClient(
            "publisher/model", runs=3, max_output_tokens=1234, timeout=60, max_retries=0,
        )
        for prompt in ("first prompt", "second prompt"):
            self.assertEqual(client.call(prompt), "public class Example {}")
            backend.responses.create.assert_called_with(
                model="publisher/model", input=prompt, max_output_tokens=1234,
                stream=False, store=False,
            )
        self.assertEqual(backend.responses.create.call_count, 2)
        self.assertEqual(sdk.return_value.__exit__.call_count, 2)
        sdk.assert_called_with(api_key="synthetic-key", timeout=60, max_retries=0)
        key.assert_called_with("OPENAI_API_KEY")

    @patch("pipeline.clients.openai.api_key", return_value="synthetic-key")
    def test_default_settings_with_real_sdk_and_mock_http_transport(self, key):
        requests = []

        def handle(request):
            requests.append(request)
            return httpx2.Response(200, json={
                "id": "resp-test", "object": "response", "created_at": 0,
                "model": "model", "status": "completed", "error": None,
                "incomplete_details": None, "parallel_tool_calls": False,
                "tool_choice": "auto", "tools": [],
                "output": [{
                    "id": "msg-test", "type": "message", "role": "assistant",
                    "status": "completed",
                    "content": [{"type": "output_text", "text": "source", "annotations": []}],
                }],
            })

        http = httpx2.Client(transport=httpx2.MockTransport(handle))
        with patch("pipeline.clients.openai.OpenAI", wraps=openai.OpenAI) as sdk:
            sdk.side_effect = lambda **kwargs: openai.OpenAI(http_client=http, **kwargs)
            self.assertEqual(OpenAIClient("model").call("prompt"), "source")
            sdk.assert_called_once_with(
                api_key="synthetic-key", timeout=815.0, max_retries=2,
            )
        self.assertTrue(http.is_closed)
        self.assertEqual(str(requests[0].url), "https://api.openai.com/v1/responses")
        self.assertEqual(json.loads(requests[0].content), {
            "model": "model", "input": "prompt", "max_output_tokens": 20_000,
            "stream": False, "store": False,
        })

    @patch("pipeline.clients.openai.api_key", return_value="synthetic-key")
    @patch("pipeline.clients.openai.OpenAI")
    def test_extracts_only_text_preserving_fragments_and_message_order(self, sdk, key):
        first = response().output[0]
        first.content = [
            ResponseOutputText.model_construct(type="output_text", text="public class "),
            ResponseOutputText.model_construct(type="output_text", text="Example {"),
        ]
        sdk.return_value.__enter__.return_value.responses.create.return_value = response(
            output=[
                ResponseReasoningItem.model_construct(type="reasoning", summary=[]),
                first, response("}").output[0],
            ],
        )
        self.assertEqual(OpenAIClient("model").call("prompt"), "public class Example {\n}")

    @patch("pipeline.clients.openai.api_key", return_value="synthetic-key")
    @patch("pipeline.clients.openai.OpenAI")
    def test_rejects_failed_truncated_refused_empty_and_unexpected_output(self, sdk, key):
        refused = response()
        refused.output[0].content = [
            ResponseOutputRefusal.model_construct(type="refusal", refusal="private detail"),
        ]
        unfinished = response()
        unfinished.output[0].status = "incomplete"
        invalid_text = response()
        invalid_text.output[0].content = [
            ResponseOutputText.model_construct(type="output_text", text=None),
        ]
        documents = [
            *(response(status=status) for status in (
                "incomplete", "failed", "in_progress", "cancelled", "queued", None,
            )),
            response(error={"message": "private detail"}),
            response(incomplete_details={"reason": "max_output_tokens"}),
            response(" \n"), response(output=[]), response(output=None),
            response(output=[{"type": "function_call"}]),
            Response.model_construct(status="completed"),
            response(output=[ResponseOutputMessage.model_construct(status="completed")]),
            response(output=[ResponseOutputMessage.model_construct(
                status="completed", role="assistant", content=[
                    ResponseOutputText.model_construct(type="output_text"),
                ],
            )]),
            refused, unfinished, invalid_text, None,
        ]
        for document in documents:
            with self.subTest(response=document):
                sdk.return_value.__enter__.return_value.responses.create.return_value = document
                with self.assertRaises(OpenAIClientError) as raised:
                    OpenAIClient("model").call("prompt")
                self.assertNotIn("private detail", str(raised.exception))

    @patch("pipeline.clients.openai.api_key", return_value="synthetic-key")
    @patch("pipeline.clients.openai.OpenAI")
    def test_wraps_sdk_failures_without_leaking_body_or_credential(self, sdk, key):
        request = httpx2.Request("POST", "https://api.openai.com/v1/responses")
        errors = [
            openai.APIStatusError(
                "synthetic-key private prompt", response=httpx2.Response(429, request=request),
                body={"secret": "synthetic-key"},
            ),
            openai.APIConnectionError(request=request, message="synthetic-key"),
            openai.APITimeoutError(request),
            json.JSONDecodeError("synthetic-key", "", 0),
        ]
        for error in errors:
            with self.subTest(error=type(error)):
                sdk.return_value.__enter__.return_value.responses.create.side_effect = error
                with self.assertRaises(OpenAIClientError) as raised:
                    OpenAIClient("model").call("private prompt")
                message = str(raised.exception)
                self.assertNotIn("synthetic-key", message)
                self.assertNotIn("private prompt", message)
                self.assertTrue(raised.exception.__suppress_context__)
                if isinstance(error, openai.APITimeoutError):
                    self.assertIsInstance(raised.exception, ClientTimeoutError)
                    self.assertEqual(raised.exception.attempts, 3)
                else:
                    self.assertNotIsInstance(raised.exception, ClientTimeoutError)
                if isinstance(error, openai.APIStatusError):
                    self.assertIn("HTTP 429", message)
