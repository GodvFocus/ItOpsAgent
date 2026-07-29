from __future__ import annotations

import io
import logging
import sys
import types
import unittest

fake_pymilvus = types.ModuleType("pymilvus")
fake_pymilvus.Collection = object
fake_pymilvus.connections = types.SimpleNamespace(connect=lambda *args, **kwargs: None)
fake_pymilvus.utility = types.SimpleNamespace(has_collection=lambda *args, **kwargs: False)
fake_pymilvus.DataType = object
fake_pymilvus.FieldSchema = object
fake_pymilvus.CollectionSchema = object
sys.modules.setdefault("pymilvus", fake_pymilvus)

fake_minio = types.ModuleType("minio")
fake_minio.Minio = object
sys.modules.setdefault("minio", fake_minio)

from fish_worker.consumer import _fields_to_task
from fish_worker.trace_context import bind_trace_id, install_logging_trace_context


class TraceContextTest(unittest.TestCase):
    def test_fields_to_task_reads_trace_id(self) -> None:
        task = _fields_to_task({
            "task_id": "task-1",
            "minio_path": "docs/a.pdf",
            "workspace_id": "default",
            "visibility": "PRIVATE",
            "user_id": "user-1",
            "file_name": "a.pdf",
            "trace_id": "trace-java-python-1",
        })

        self.assertEqual("trace-java-python-1", task.trace_id)

    def test_logging_record_contains_bound_trace_id(self) -> None:
        install_logging_trace_context()
        logger = logging.getLogger("fish_worker.trace_test")
        logger.setLevel(logging.INFO)
        logger.propagate = False
        stream = io.StringIO()
        handler = logging.StreamHandler(stream)
        handler.setFormatter(logging.Formatter("[traceId=%(trace_id)s] %(message)s"))
        logger.handlers = [handler]

        with bind_trace_id("trace-log-1"):
            logger.info("worker log")

        self.assertEqual("[traceId=trace-log-1] worker log", stream.getvalue().strip())


if __name__ == "__main__":
    unittest.main()
