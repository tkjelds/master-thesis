import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from pipeline.clients._api import api_key
from pipeline.clients.anthropic import AnthropicClient, AnthropicClientError
from pipeline.clients.openai import OpenAIClient, OpenAIClientError


class APIConfigurationTests(unittest.TestCase):
    def test_reads_dotenv_without_mutating_environment_and_environment_wins(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env"
            path.write_text(
                "OPENAI_API_KEY=file-openai\nANTHROPIC_API_KEY=file-anthropic\n",
                encoding="utf-8",
            )
            with patch("pipeline.clients._api.DOTENV_PATH", path), patch.dict(
                os.environ, {}, clear=True,
            ):
                self.assertEqual(api_key("OPENAI_API_KEY"), "file-openai")
                self.assertEqual(api_key("ANTHROPIC_API_KEY"), "file-anthropic")
                self.assertNotIn("OPENAI_API_KEY", os.environ)
                self.assertNotIn("ANTHROPIC_API_KEY", os.environ)
                with patch.dict(os.environ, {"OPENAI_API_KEY": "env-openai"}):
                    self.assertEqual(api_key("OPENAI_API_KEY"), "env-openai")
                    self.assertEqual(api_key("ANTHROPIC_API_KEY"), "file-anthropic")
                with patch.dict(os.environ, {"OPENAI_API_KEY": "  "}):
                    with self.assertRaisesRegex(ValueError, "OPENAI_API_KEY"):
                        api_key("OPENAI_API_KEY")

    def test_missing_and_blank_keys_raise_provider_errors_without_sdk_calls(self):
        cases = (
            (OpenAIClient, OpenAIClientError, "openai.OpenAI", "OPENAI_API_KEY"),
            (AnthropicClient, AnthropicClientError, "anthropic.Anthropic", "ANTHROPIC_API_KEY"),
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env"
            with patch("pipeline.clients._api.DOTENV_PATH", path), patch.dict(
                os.environ, {}, clear=True,
            ):
                for client_type, error_type, sdk, variable in cases:
                    for content in (None, "", f"{variable}=\n", f"{variable}='  '\n"):
                        with self.subTest(provider=variable, content=content):
                            if content is None:
                                path.unlink(missing_ok=True)
                            else:
                                path.write_text(content, encoding="utf-8")
                            with patch(f"pipeline.clients.{sdk}") as constructor:
                                with self.assertRaisesRegex(error_type, variable):
                                    client_type("model").call("prompt")
                                constructor.assert_not_called()

    @patch("pipeline.clients._api.dotenv_values", side_effect=OSError("private detail"))
    @patch.dict(os.environ, {}, clear=True)
    def test_dotenv_read_errors_are_explicit_and_sanitized(self, values):
        for client_type, error_type in (
            (OpenAIClient, OpenAIClientError), (AnthropicClient, AnthropicClientError),
        ):
            with self.subTest(client=client_type):
                with self.assertRaisesRegex(error_type, "Could not read") as raised:
                    client_type("model").call("prompt")
                self.assertNotIn("private detail", str(raised.exception))

    @patch("pipeline.clients._api.dotenv_values")
    def test_construction_needs_no_credentials_and_repr_contains_no_keys(self, values):
        with patch.dict(os.environ, {
            "OPENAI_API_KEY": "synthetic-openai-key",
            "ANTHROPIC_API_KEY": "synthetic-anthropic-key",
        }):
            for client_type in (OpenAIClient, AnthropicClient):
                client = client_type("model", runs=3)
                self.assertNotIn("synthetic-", repr(client))
                self.assertEqual(client.runs, 3)
                self.assertEqual(
                    client_type("model", runs=3, skip_runs=1).skip_runs, 1,
                )
        values.assert_not_called()

    def test_rejects_invalid_configuration(self):
        invalid = [
            *({"model": value} for value in ("", " \n", None, 1)),
            *({"runs": value} for value in (0, -1, True, 1.5, "3", None)),
            *({"skip_runs": value} for value in (-1, True, 1.5, "1", None, 1)),
            *({"max_output_tokens": value} for value in (0, -1, True, 1.5, "20", None)),
            *({"max_retries": value} for value in (-1, True, 1.5, "2", None)),
            *({"timeout": value} for value in (
                0, -1, True, None, "815", float("inf"), float("-inf"), float("nan"),
            )),
        ]
        for client_type in (OpenAIClient, AnthropicClient):
            for configuration in invalid:
                with self.subTest(client=client_type, configuration=configuration):
                    with self.assertRaises(ValueError):
                        client_type(**({"model": "model"} | configuration))

    def test_encodes_unsafe_model_names_without_collisions(self):
        models = ("publisher/model", "publisher%2Fmodel", ".", "..", "../model", "model@x")
        for client_type in (OpenAIClient, AnthropicClient):
            with self.subTest(client=client_type):
                names = [client_type(model).output_model_name for model in models]
                self.assertEqual(len(set(names)), len(models))
                self.assertEqual(names[:4], [
                    "publisher%2Fmodel", "publisher%252Fmodel", "%2E", "%2E%2E",
                ])
                for name in names:
                    self.assertNotIn("/", name)
