"""Ollama embedding client used by fish_worker."""

from __future__ import annotations

import logging
import random
import time
from typing import Any

import httpx

from fish_worker.config import Settings
from fish_worker.trace_context import current_trace_headers

log = logging.getLogger(__name__)

_RETRYABLE_STATUS = {429, 500, 502, 503, 504}


class _RetryableHttpStatus(Exception):
    """标记 HTTP 层可重试状态码，保留原响应用于最终抛出标准异常。"""

    def __init__(self, response: httpx.Response) -> None:
        self.response = response
        super().__init__(f"retryable HTTP status: {response.status_code}")


class Embedder:
    """Ollama embedding 封装。"""

    def __init__(self, settings: Settings) -> None:
        self._s = settings
        self._resolved_ollama_model: str | None = None

    def warmup(self) -> None:
        """Worker 启动前预热 Ollama，尽早暴露模型名或服务可用性问题。"""
        base = self._s.ollama_base_url.rstrip("/")
        with httpx.Client(timeout=120.0, trust_env=False) as client:
            model = self._resolve_ollama_model(client)
            response = self._post_with_retry(
                client,
                f"{base}/api/embeddings",
                headers=current_trace_headers(),
                json={"model": model, "prompt": "fish-worker embedding warmup"},
            )
            data = response.json()
            emb = data.get("embedding")
            if not isinstance(emb, list) or not emb:
                raise RuntimeError(f"Unexpected Ollama warmup response: {data!r}")
            self._resolved_ollama_model = model
            log.info(
                "ollama embedding ready base_url=%s model=%s dimension=%s",
                base,
                model,
                len(emb),
            )

    def embed_batch(self, texts: list[str]) -> list[list[float]]:
        """逐条调用 Ollama embedding 接口，返回与输入等长的向量列表。"""
        if not texts:
            return []
        return self._ollama_embed(texts)

    def _ollama_embed(self, texts: list[str]) -> list[list[float]]:
        """Ollama 本地 embedding。"""
        base = self._s.ollama_base_url.rstrip("/")
        model = self._resolved_ollama_model or self._s.ollama_embedding_model

        vecs: list[list[float]] = []
        # 直连 localhost，避免残留代理把本地请求转发出去并制造 502。
        with httpx.Client(timeout=120.0, trust_env=False) as client:
            if self._resolved_ollama_model is None:
                model = self._resolve_ollama_model(client)
                self._resolved_ollama_model = model
            for text in texts:
                response = self._post_with_retry(
                    client,
                    f"{base}/api/embeddings",
                    headers=current_trace_headers(),
                    json={"model": model, "prompt": text},
                )
                data = response.json()
                emb = data.get("embedding")
                if not isinstance(emb, list):
                    raise RuntimeError(f"Unexpected Ollama embedding response: {data!r}")
                vecs.append([float(x) for x in emb])
        return vecs

    def _resolve_ollama_model(self, client: httpx.Client) -> str:
        """解析配置里的模型名，优先把裸模型名补全到已安装的 `:latest` 标签。"""
        configured = self._s.ollama_embedding_model.strip()
        if not configured:
            raise ValueError("OLLAMA_EMBEDDING_MODEL 不能为空")

        base = self._s.ollama_base_url.rstrip("/")
        response = client.get(f"{base}/api/tags", headers=current_trace_headers() or None)
        response.raise_for_status()
        data = response.json()
        models = data.get("models")
        if not isinstance(models, list):
            raise RuntimeError(f"Unexpected Ollama tags response: {data!r}")

        installed = {
            str(item.get("name", "")).strip()
            for item in models
            if isinstance(item, dict) and item.get("name")
        }
        if configured in installed:
            return configured

        if ":" not in configured:
            latest_alias = f"{configured}:latest"
            if latest_alias in installed:
                log.info(
                    "ollama embedding model normalized configured=%s resolved=%s",
                    configured,
                    latest_alias,
                )
                return latest_alias

        available = ", ".join(sorted(installed)) or "(none)"
        raise ValueError(
            f"Configured Ollama embedding model not found: {configured}. Available models: {available}"
        )

    def _post_with_retry(
        self,
        client: httpx.Client,
        url: str,
        *,
        headers: dict[str, str] | None = None,
        json: dict[str, Any] | None = None,
    ) -> httpx.Response:
        """执行可重试 POST。"""
        max_retries = max(0, int(self._s.fish_worker_embed_max_retries))
        attempt = 0
        request_headers = dict(headers or {})
        request_headers.update(current_trace_headers())

        while True:
            try:
                response = client.post(url, headers=request_headers or None, json=json)
                if response.status_code in _RETRYABLE_STATUS:
                    raise _RetryableHttpStatus(response)
                response.raise_for_status()
                return response
            except _RetryableHttpStatus as exc:
                attempt += 1
                if attempt > max_retries:
                    exc.response.raise_for_status()
                    raise
                delay = self._retry_delay(exc.response.headers.get("Retry-After"), attempt)
                log.warning(
                    "embedding HTTP status=%s retry=%s/%s delay=%.2fs",
                    exc.response.status_code,
                    attempt,
                    max_retries,
                    delay,
                )
                time.sleep(delay)
            except httpx.RequestError as exc:
                attempt += 1
                if attempt > max_retries:
                    raise
                delay = self._retry_delay(None, attempt)
                log.warning(
                    "embedding request error=%s retry=%s/%s delay=%.2fs",
                    type(exc).__name__,
                    attempt,
                    max_retries,
                    delay,
                )
                time.sleep(delay)

    def _retry_delay(self, retry_after: str | None, attempt: int) -> float:
        """计算指数退避时间，优先尊重 Retry-After。"""
        cap = max(0.0, float(self._s.fish_worker_embed_backoff_max))
        if cap <= 0:
            return 0.0
        if retry_after:
            try:
                return min(cap, max(0.0, float(retry_after)))
            except ValueError:
                pass

        base = max(0.0, float(self._s.fish_worker_embed_backoff_base))
        delay = min(cap, base * (2 ** max(0, attempt - 1)))
        jitter = random.uniform(0.0, min(0.5, cap))
        return min(cap, delay + jitter)
