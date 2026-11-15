from __future__ import annotations

import json
import os
import signal
import subprocess
from dataclasses import dataclass
from pathlib import Path

from pipeline.clients.interface import (
    ClientTimeoutError,
    validate_runs,
    validate_skip_runs,
)

TIMEOUT_SECONDS = 300
TIMEOUT_RETRIES = 5


class CopilotClientError(RuntimeError):
    pass


class CopilotTimeoutError(ClientTimeoutError, CopilotClientError):
    pass


def _json_response(output: str, *, final_only: bool = False) -> str:
    messages = []
    pending = ""
    pending_turn = None
    final = ""
    succeeded = False
    for line in output.splitlines():
        if not line.strip():
            continue
        try:
            event = json.loads(line)
        except json.JSONDecodeError as error:
            raise CopilotClientError("Invalid Copilot JSONL response") from error
        if not isinstance(event, dict) or not isinstance(event.get("type"), str):
            raise CopilotClientError("Expected a typed object in Copilot JSONL response")
        if event["type"] == "session.error":
            data = event.get("data")
            detail = data.get("message") if isinstance(data, dict) else None
            raise CopilotClientError(f"Copilot session error: {detail or 'unknown error'}")
        if event["type"] == "result":
            exit_code = event.get("exitCode")
            if not isinstance(exit_code, int) or isinstance(exit_code, bool):
                raise CopilotClientError("Invalid exitCode in Copilot JSONL result")
            if exit_code != 0:
                raise CopilotClientError(f"Copilot session exited with status {exit_code}")
            succeeded = True
        data = event.get("data")
        if final_only:
            if event.get("agentId") or (
                isinstance(data, dict) and data.get("parentToolCallId")
            ):
                continue
            if event["type"] == "abort":
                raise CopilotClientError("Copilot session was aborted")
            if event["type"] in ("assistant.turn_start", "tool.execution_start"):
                pending = final = ""
                pending_turn = None
                succeeded = False
            if event["type"] == "assistant.turn_end":
                if (
                    pending_turn is not None
                    and isinstance(data, dict)
                    and data.get("turnId") == pending_turn
                ):
                    final = pending
                else:
                    final = ""
                pending = ""
                pending_turn = None
        if event["type"] != "assistant.message":
            continue
        if not isinstance(data, dict) or not isinstance(data.get("content"), str):
            raise CopilotClientError("Missing content in Copilot assistant message")
        if final_only:
            pending = final = ""
            pending_turn = None
            succeeded = False
            requests = data.get("toolRequests", [])
            if not isinstance(requests, list):
                raise CopilotClientError("Invalid toolRequests in Copilot assistant message")
            if not requests and data.get("phase") in (None, "final_answer"):
                turn_id = data.get("turnId")
                if not isinstance(turn_id, str):
                    raise CopilotClientError("Missing turnId in Copilot assistant message")
                pending = data["content"].strip()
                pending_turn = turn_id
            continue
        if data["content"].strip():
            messages.append(data["content"])
    if final_only:
        if not succeeded or not final:
            raise CopilotClientError("Copilot returned no completed final assistant message")
        return final
    response = "\n".join(messages).strip()
    if not response:
        raise CopilotClientError("Copilot returned no nonempty assistant message")
    return response


def _run_in_workspace(command: list[str], cwd: Path) -> subprocess.CompletedProcess[str]:
    if os.name != "posix":
        raise CopilotClientError("The tool-enabled Copilot client requires macOS or Linux")
    with subprocess.Popen(
        command,
        cwd=cwd,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        start_new_session=True,
    ) as process:
        try:
            stdout, stderr = process.communicate(timeout=TIMEOUT_SECONDS)
        finally:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.communicate()
        return subprocess.CompletedProcess(command, process.returncode, stdout, stderr)


def _call_copilot(
    command: list[str], *, json_output: bool = False,
    cwd: Path | None = None, final_only: bool = False,
) -> str:
    for attempt in range(TIMEOUT_RETRIES + 1):
        try:
            if cwd is None:
                result = subprocess.run(
                    command,
                    capture_output=True,
                    text=True,
                    check=False,
                    timeout=TIMEOUT_SECONDS,
                )
            else:
                result = _run_in_workspace(command, cwd)
        except subprocess.TimeoutExpired as error:
            if attempt == TIMEOUT_RETRIES:
                raise CopilotTimeoutError(
                    f"Copilot CLI timed out after {TIMEOUT_SECONDS} seconds per attempt; "
                    f"exhausted {TIMEOUT_RETRIES} retries ({attempt + 1} attempts)",
                    attempts=attempt + 1,
                ) from error
            print(
                f"Copilot CLI: request timed out after {TIMEOUT_SECONDS} seconds; "
                f"retrying ({attempt + 1}/{TIMEOUT_RETRIES}).",
                flush=True,
            )
        except OSError as error:
            raise CopilotClientError(f"Copilot CLI error: {error}") from error
        else:
            break

    if result.returncode != 0:
        details = result.stderr.strip() or result.stdout.strip()
        if json_output and not result.stderr.strip():
            try:
                _json_response(result.stdout)
            except CopilotClientError as error:
                details = str(error)
            else:
                details = "No error detail reported in Copilot JSONL output"
        message = f"Copilot CLI exited with status {result.returncode}"
        if details:
            message += f": {details}"
        raise CopilotClientError(message)

    if json_output:
        return _json_response(result.stdout, final_only=final_only)
    response = result.stdout.strip()
    if not response:
        raise CopilotClientError("Copilot returned an empty response")
    return response


@dataclass(frozen=True)
class CopilotClient:
    model: str
    name: str = "copilot"
    runs: int = 1
    skip_runs: int = 0

    def __post_init__(self) -> None:
        validate_runs(self.runs)
        validate_skip_runs(self.skip_runs, self.runs)

    def call(self, prompt: str) -> str:
        return _call_copilot(["copilot", "-p", prompt, "--model", self.model])


@dataclass(frozen=True)
class CopilotClientNoTools(CopilotClient):
    name: str = "copilot-no-tools"

    def call(self, prompt: str) -> str:
        return _call_copilot(
            [
                "copilot",
                "-p",
                prompt,
                "--model",
                self.model,
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
            json_output=True,
        )
