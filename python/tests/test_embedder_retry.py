from __future__ import annotations

import unittest
from types import SimpleNamespace
from unittest.mock import patch

import httpx

from fish_worker.chunker.embedder import Embedder


class FakeResponse:
    def __init__(
        self,
        status_code: int,
        *,
        headers: dict[str, str] | None = None,
        json_data: dict | None = None,
    ) -> None:
        self.status_code = status_code
        self.headers = headers or {}
        self._json_data = json_data or {}
        self.request = httpx.Request("POST", "https://example.test/embedding")

    def raise_for_status(self) -> None:
        if self.status_code >= 400:
            response = httpx.Response(self.status_code, request=self.request)
            raise httpx.HTTPStatusError("HTTP error", request=self.request, response=response)

    def json(self) -> dict:
        return self._json_data


class FakeClient:
    def __init__(self, outcomes: list[FakeResponse | Exception]) -> None:
        self._outcomes = outcomes
        self.calls = 0
        self.trust_env = None

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        return False

    def post(self, *args, **kwargs) -> FakeResponse:
        self.calls += 1
        outcome = self._outcomes.pop(0)
        if isinstance(outcome, Exception):
            raise outcome
        return outcome

    def get(self, *args, **kwargs) -> FakeResponse:
        outcome = self._outcomes.pop(0)
        if isinstance(outcome, Exception):
            raise outcome
        return outcome


def retry_settings(**overrides):
    defaults = {
        "ollama_base_url": "http://localhost:11434",
        "ollama_embedding_model": "bge-m3",
        "fish_worker_embed_max_retries": 3,
        "fish_worker_embed_backoff_base": 0.0,
        "fish_worker_embed_backoff_max": 0.0,
    }
    defaults.update(overrides)
    return SimpleNamespace(**defaults)


class EmbedderRetryTest(unittest.TestCase):
    @patch("fish_worker.chunker.embedder.time.sleep")
    @patch("fish_worker.chunker.embedder.random.uniform", return_value=0.0)
    def test_retries_429_then_returns_success(self, _jitter, sleep) -> None:
        client = FakeClient([
            FakeResponse(429, headers={"Retry-After": "0"}),
            FakeResponse(200),
        ])

        response = Embedder(retry_settings())._post_with_retry(client, "https://example.test")

        self.assertEqual(200, response.status_code)
        self.assertEqual(2, client.calls)
        sleep.assert_called_once_with(0.0)

    @patch("fish_worker.chunker.embedder.time.sleep")
    def test_does_not_retry_400(self, sleep) -> None:
        client = FakeClient([FakeResponse(400)])

        with self.assertRaises(httpx.HTTPStatusError):
            Embedder(retry_settings())._post_with_retry(client, "https://example.test")

        self.assertEqual(1, client.calls)
        sleep.assert_not_called()

    @patch("fish_worker.chunker.embedder.time.sleep")
    @patch("fish_worker.chunker.embedder.random.uniform", return_value=0.0)
    def test_retries_network_errors(self, _jitter, sleep) -> None:
        request = httpx.Request("POST", "https://example.test/embedding")
        client = FakeClient([
            httpx.ConnectError("temporary network error", request=request),
            FakeResponse(200),
        ])

        response = Embedder(retry_settings())._post_with_retry(client, "https://example.test")

        self.assertEqual(200, response.status_code)
        self.assertEqual(2, client.calls)
        sleep.assert_called_once_with(0.0)

    def test_resolve_ollama_model_normalizes_latest_alias(self) -> None:
        client = FakeClient([
            FakeResponse(
                200,
                json_data={
                    "models": [
                        {"name": "bge-m3:latest"},
                    ]
                },
            )
        ])

        model = Embedder(retry_settings())._resolve_ollama_model(client)

        self.assertEqual("bge-m3:latest", model)

    @patch("fish_worker.chunker.embedder.httpx.Client")
    def test_ollama_client_disables_env_proxy(self, client_cls) -> None:
        fake_client = FakeClient([
            FakeResponse(200, json_data={"models": [{"name": "bge-m3:latest"}]}),
            FakeResponse(200, json_data={"embedding": [0.1, 0.2]}),
            FakeResponse(200, json_data={"embedding": [0.3, 0.4]}),
        ])
        client_cls.return_value = fake_client

        vectors = Embedder(retry_settings()).embed_batch(["hello"])

        _, kwargs = client_cls.call_args
        self.assertEqual([[0.1, 0.2]], vectors)
        self.assertFalse(kwargs["trust_env"])


if __name__ == "__main__":
    unittest.main()
