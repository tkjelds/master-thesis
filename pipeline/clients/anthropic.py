from __future__ import annotations

from dataclasses import dataclass
from json import JSONDecodeError

from anthropic import APIError, APITimeoutError, Anthropic
from anthropic.types import Message, RedactedThinkingBlock, TextBlock, ThinkingBlock

from pipeline.clients._api import (
    api_key,
    output_model_name,
    sdk_error_detail,
    validate_configuration,
)
from pipeline.clients.interface import ClientTimeoutError


class AnthropicClientError(RuntimeError):
    pass


class AnthropicTimeoutError(ClientTimeoutError, AnthropicClientError):
    pass


@dataclass(frozen=True)
class AnthropicClient:
    model: str
    name: str = "anthropic"
    runs: int = 1
    max_output_tokens: int = 128_000
    timeout: float = 815.0
    max_retries: int = 2
    skip_runs: int = 0

    def __post_init__(self) -> None:
        validate_configuration(
            self.model, self.runs, self.max_output_tokens, self.timeout,
            self.max_retries, self.skip_runs,
        )

    @property
    def output_model_name(self) -> str:
        return output_model_name(self.model)

    def call(self, prompt: str) -> str:
        try:
            key = api_key("ANTHROPIC_API_KEY")
        except (RuntimeError, ValueError) as error:
            raise AnthropicClientError(str(error)) from None
        try:
            with Anthropic(
                api_key=key, timeout=self.timeout, max_retries=self.max_retries,
            ) as client:
                response = client.messages.create(
                    model=self.model, messages=[{"role": "user", "content": prompt}],
                    max_tokens=self.max_output_tokens, stream=False, timeout=self.timeout,
                )
        except APITimeoutError as error:
            raise AnthropicTimeoutError(
                f"Anthropic {self.model}: {sdk_error_detail(error)}; "
                f"exhausted {self.max_retries} retries ({self.max_retries + 1} attempts)",
                attempts=self.max_retries + 1,
            ) from None
        except APIError as error:
            raise AnthropicClientError(
                f"Anthropic {self.model}: {sdk_error_detail(error)}"
            ) from None
        except JSONDecodeError:
            raise AnthropicClientError("Anthropic returned invalid JSON") from None
        if (
            not isinstance(response, Message)
            or getattr(response, "type", None) != "message"
            or getattr(response, "role", None) != "assistant"
            or getattr(response, "stop_reason", None) != "end_turn"
        ):
            raise AnthropicClientError(
                "Anthropic returned an incomplete, refused, or unexpected response"
            )
        if not isinstance(getattr(response, "content", None), list):
            raise AnthropicClientError("Anthropic returned an invalid content list")
        parts = []
        for block in response.content:
            if isinstance(block, (ThinkingBlock, RedactedThinkingBlock)):
                continue
            if (
                not isinstance(block, TextBlock)
                or not isinstance(getattr(block, "text", None), str)
            ):
                raise AnthropicClientError("Anthropic returned non-text message content")
            parts.append(block.text)
        text = "\n".join(parts).strip()
        if not text:
            raise AnthropicClientError("Anthropic returned no nonempty text content")
        return text
