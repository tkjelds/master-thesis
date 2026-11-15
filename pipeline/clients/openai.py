from __future__ import annotations

from dataclasses import dataclass
from json import JSONDecodeError

from openai import APIError, APITimeoutError, OpenAI
from openai.types.responses import (
    Response,
    ResponseOutputMessage,
    ResponseOutputRefusal,
    ResponseOutputText,
    ResponseReasoningItem,
)

from pipeline.clients._api import (
    api_key,
    output_model_name,
    sdk_error_detail,
    validate_configuration,
)
from pipeline.clients.interface import ClientTimeoutError


class OpenAIClientError(RuntimeError):
    pass


class OpenAITimeoutError(ClientTimeoutError, OpenAIClientError):
    pass


@dataclass(frozen=True)
class OpenAIClient:
    model: str
    name: str = "openai"
    runs: int = 1
    max_output_tokens: int = 20_000
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
            key = api_key("OPENAI_API_KEY")
        except (RuntimeError, ValueError) as error:
            raise OpenAIClientError(str(error)) from None
        try:
            with OpenAI(
                api_key=key, timeout=self.timeout, max_retries=self.max_retries,
            ) as client:
                response = client.responses.create(
                    model=self.model, input=prompt, max_output_tokens=self.max_output_tokens,
                    stream=False, store=False,
                )
        except APITimeoutError as error:
            raise OpenAITimeoutError(
                f"OpenAI {self.model}: {sdk_error_detail(error)}; "
                f"exhausted {self.max_retries} retries ({self.max_retries + 1} attempts)",
                attempts=self.max_retries + 1,
            ) from None
        except APIError as error:
            raise OpenAIClientError(
                f"OpenAI {self.model}: {sdk_error_detail(error)}"
            ) from None
        except JSONDecodeError:
            raise OpenAIClientError("OpenAI returned invalid JSON") from None
        if (
            not isinstance(response, Response)
            or getattr(response, "status", None) != "completed"
            or getattr(response, "error", None) is not None
            or getattr(response, "incomplete_details", None) is not None
        ):
            raise OpenAIClientError("OpenAI returned an incomplete or failed response")
        if not isinstance(getattr(response, "output", None), list):
            raise OpenAIClientError("OpenAI returned an invalid output list")
        messages = []
        for item in response.output:
            if isinstance(item, ResponseReasoningItem):
                continue
            if (
                not isinstance(item, ResponseOutputMessage)
                or getattr(item, "status", None) != "completed"
                or getattr(item, "role", None) != "assistant"
                or not isinstance(getattr(item, "content", None), list)
            ):
                raise OpenAIClientError("OpenAI returned unexpected or incomplete output")
            parts = []
            for block in item.content:
                if isinstance(block, ResponseOutputRefusal):
                    raise OpenAIClientError("OpenAI refused the request")
                if (
                    not isinstance(block, ResponseOutputText)
                    or not isinstance(getattr(block, "text", None), str)
                ):
                    raise OpenAIClientError("OpenAI returned non-text message content")
                parts.append(block.text)
            messages.append("".join(parts))
        text = "\n".join(messages).strip()
        if not text:
            raise OpenAIClientError("OpenAI returned no nonempty text content")
        return text
