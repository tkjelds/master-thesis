from __future__ import annotations

import json
import subprocess
from dataclasses import dataclass
from typing import Literal
from urllib.parse import quote, urlsplit

from pipeline.clients.interface import (
    ClientTimeoutError,
    validate_runs,
    validate_skip_runs,
)


class LMStudioClientError(RuntimeError):
    pass


class LMStudioTimeoutError(ClientTimeoutError, LMStudioClientError):
    pass


@dataclass(frozen=True)
class LMStudioClient:
    model: str
    api_host: str = "localhost:1234"
    name: str = "lm-studio"
    skip_chats: int = 0
    timeout_retries: int = 5
    reasoning: Literal["off", "on"] | None = None
    runs: int = 1
    skip_runs: int = 0

    def __post_init__(self) -> None:
        validate_runs(self.runs)
        validate_skip_runs(self.skip_runs, self.runs)
        if not isinstance(self.model, str) or not self.model.strip():
            raise ValueError("LM Studio model must be a nonblank string")
        if self.reasoning not in (None, "off", "on"):
            raise ValueError("LM Studio reasoning must be 'off', 'on', or None")
        if (
            not isinstance(self.skip_chats, int)
            or isinstance(self.skip_chats, bool)
            or self.skip_chats < 0
        ):
            raise ValueError("LM Studio skip_chats must be a nonnegative integer")
        if (
            not isinstance(self.timeout_retries, int)
            or isinstance(self.timeout_retries, bool)
            or self.timeout_retries < 0
            or self.timeout_retries > 5
        ):
            raise ValueError("LM Studio timeout_retries must be an integer from 0 to 5")
        if not isinstance(self.api_host, str) or any(
            character.isspace() for character in self.api_host
        ):
            raise ValueError("LM Studio API host must be a host:port address")
        url = urlsplit(f"//{self.api_host}")
        if (
            not url.hostname
            or url.username is not None
            or url.password is not None
            or url.path
            or "?" in self.api_host
            or "#" in self.api_host
        ):
            raise ValueError(
                "LM Studio API host must be host:port without a URL scheme, "
                "path, credentials, query parameters, or a fragment"
            )
        try:
            port = url.port
        except ValueError:
            raise ValueError("LM Studio API host has an invalid port") from None
        if port is None or port == 0:
            raise ValueError("LM Studio API host must include a valid port")

    @property
    def output_model_name(self) -> str:
        encoded = quote(self.model, safe="-_.")
        if encoded in {".", ".."}:
            encoded = encoded.replace(".", "%2E")
        if self.reasoning is not None:
            return f"{encoded}@reasoning-{self.reasoning}"
        return encoded

    def call(self, prompt: str) -> str:
        request: dict[str, str | bool] = {
            "model": self.model, "input": prompt, "stream": False, "store": False,
        }
        if self.reasoning is not None:
            request["reasoning"] = self.reasoning
        payload = json.dumps(request)
        for attempt in range(self.timeout_retries + 1):
            try:
                result = subprocess.run(
                    [
                        "curl",
                        "--disable",
                        "--silent",
                        "--show-error",
                        "--fail-with-body",
                        "--max-time",
                        "815",
                        "--header",
                        "Content-Type: application/json",
                        "--data-binary",
                        "@-",
                        "--url",
                        f"http://{self.api_host}/api/v1/chat",
                    ],
                    input=payload,
                    capture_output=True,
                    text=True,
                    encoding="utf-8",
                    check=False,
                )
            except OSError as error:
                raise LMStudioClientError(f"LM Studio curl error: {error}") from error
            if result.returncode != 28 or attempt == self.timeout_retries:
                break
            print(
                f"LM Studio {self.model}: request timed out after 815 seconds; "
                f"retrying ({attempt + 1}/{self.timeout_retries}).",
                flush=True,
            )
        if result.returncode != 0:
            details = result.stderr.strip() or result.stdout.strip()
            message = f"LM Studio curl exited with status {result.returncode}"
            if result.returncode == 28:
                message += f" after {attempt + 1} attempt(s)"
            if details:
                message += f": {details}"
            if result.returncode == 28:
                raise LMStudioTimeoutError(message, attempts=attempt + 1)
            raise LMStudioClientError(message)
        try:
            document = json.loads(result.stdout)
        except json.JSONDecodeError as error:
            raise LMStudioClientError("LM Studio returned invalid JSON") from error
        if not isinstance(document, dict) or not isinstance(document.get("output"), list):
            raise LMStudioClientError("LM Studio response has no output list")
        messages = []
        for item in document["output"]:
            if not isinstance(item, dict) or not isinstance(item.get("type"), str):
                raise LMStudioClientError("LM Studio returned an invalid output item")
            if item["type"] == "message":
                if not isinstance(item.get("content"), str):
                    raise LMStudioClientError("LM Studio returned non-text message content")
                messages.append(item["content"])
        content = "\n".join(messages).strip()
        if not content:
            raise LMStudioClientError("LM Studio returned no nonempty text content")
        return content
