from __future__ import annotations

import json
from dataclasses import dataclass
from urllib.error import HTTPError
from urllib.request import Request, urlopen

DEFAULT_MODEL = "kev:4b-mlx-bf16"
DEFAULT_HOST = "localhost:11434"


class System1Error(RuntimeError):
    pass


@dataclass(frozen=True)
class System1Client:
    model: str = DEFAULT_MODEL
    api_host: str = DEFAULT_HOST

    def _request(
        self, endpoint: str, payload: dict[str, object] | None = None,
    ) -> dict[str, object]:
        request = Request(
            f"http://{self.api_host}{endpoint}",
            data=json.dumps(payload, allow_nan=False).encode() if payload is not None else None,
            headers={"Content-Type": "application/json"},
        )
        try:
            with urlopen(request, timeout=815) as response:
                document = json.load(response)
        except HTTPError as error:
            with error:
                details = error.read().decode("utf-8", errors="replace")
            raise System1Error(f"Ollama HTTP {error.code}: {details}") from error
        if not isinstance(document, dict):
            raise System1Error("Ollama response must be a JSON object")
        if "error" in document:
            raise System1Error(f"Ollama API error: {document['error']}")
        return document

    def model_digest(self) -> str:
        name = self.model if ":" in self.model.rsplit("/", 1)[-1] else f"{self.model}:latest"
        models = self._request("/api/tags").get("models")
        if isinstance(models, list):
            for model in models:
                if isinstance(model, dict) and model.get("name") == name:
                    digest = model.get("digest")
                    if isinstance(digest, str) and digest.strip():
                        return digest
        raise System1Error(f"Ollama model has no local digest: {name}")

    def probability(self, state: dict[str, str], *, instructions: str) -> float:
        document = self._request("/v1/systemone", {
            "model": self.model,
            "state": state,
            "questions": {
                "correctness": {
                    "type": "noul",
                    "instructions": instructions,
                    "criteria": {
                        "true": "The implementation satisfies the functional specification.",
                        "false": "The implementation has a functional defect or omits required behavior.",
                    },
                },
            },
        })
        answers = document.get("answers")
        answer = answers.get("correctness") if isinstance(answers, dict) else None
        if not isinstance(answer, dict) or answer.get("type") != "noul":
            raise System1Error("Ollama response has no correctness noul answer")
        probability = answer.get("noul")
        if (
            isinstance(probability, bool)
            or not isinstance(probability, (int, float))
            or not 0 <= probability <= 1
        ):
            raise System1Error("Ollama probability must be a finite number from 0 to 1")
        return float(probability)
