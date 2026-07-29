"""Python worker 侧的轻量 trace 上下文。"""

from __future__ import annotations

import contextlib
import contextvars
import logging
from collections.abc import Iterator

TRACE_HEADER_NAME = "X-Request-Id"
TRACE_FIELD_NAME = "trace_id"
_TRACE_ID: contextvars.ContextVar[str] = contextvars.ContextVar("fish_worker_trace_id", default="")
_INSTALLED = False


def install_logging_trace_context() -> None:
    """给所有日志记录注入 trace_id 字段，便于和 Java 侧日志对齐检索。"""
    global _INSTALLED
    if _INSTALLED:
        return

    previous_factory = logging.getLogRecordFactory()

    def trace_record_factory(*args, **kwargs):  # type: ignore[no-untyped-def]
        record = previous_factory(*args, **kwargs)
        record.trace_id = current_trace_id() or "-"
        return record

    logging.setLogRecordFactory(trace_record_factory)
    _INSTALLED = True


@contextlib.contextmanager
def bind_trace_id(trace_id: str | None) -> Iterator[None]:
    """在当前线程/协程作用域内绑定 traceId，退出时自动恢复。"""
    token = _TRACE_ID.set((trace_id or "").strip())
    try:
        yield
    finally:
        _TRACE_ID.reset(token)


def current_trace_id() -> str:
    """返回当前上下文里的 traceId。"""
    return _TRACE_ID.get().strip()


def current_trace_headers() -> dict[str, str]:
    """把当前 traceId 转成对外 HTTP 透传头。"""
    trace_id = current_trace_id()
    if not trace_id:
        return {}
    return {TRACE_HEADER_NAME: trace_id}
