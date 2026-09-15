"""Interfaces implemented by prompt-generation clients."""

from __future__ import annotations

from typing import Protocol


class Client(Protocol):
    """Minimal interface required by the generation runner."""

    model: str

    def call(self, prompt: str) -> str:
        """Send a prompt to the client and return its response."""
