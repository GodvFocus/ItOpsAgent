"""B2：半批可见性 —— 入库中的文档不应被检索到。

修复设计：chunk 入库时 ready=False（对检索隐藏）；全部 bulk 完成后再
mark_doc_ready 翻成 True。检索侧用 expr 过滤 ready==true。
"""

from __future__ import annotations

import pytest


def _make_fake_collection(monkeypatch):
    """Mock pymilvus 连接与 Collection，捕获 insert / upsert 参数。"""
    monkeypatch.setattr("pymilvus.connections.connect", lambda **kw: None)
    monkeypatch.setattr("pymilvus.utility.has_collection", lambda name: True)

    captured_inserts = []
    captured_upserts = []
    query_results = []

    class FakeCollection:
        def load(self, **kw):
            pass

        def insert(self, rows):
            captured_inserts.extend(rows)

        def flush(self):
            pass

        def query(self, expr, output_fields):
            return list(query_results)

        def upsert(self, rows):
            captured_upserts.extend(rows)

    monkeypatch.setattr("pymilvus.Collection", lambda name, **kw: FakeCollection())
    return {
        "inserts": captured_inserts,
        "upserts": captured_upserts,
        "query_results": query_results,
    }


def _make_indexer(monkeypatch):
    """创建一个不真正连 Milvus 的 MilvusIndexer 实例。"""
    from fish_worker.storage.milvus import MilvusIndexer

    monkeypatch.setattr("pymilvus.connections.connect", lambda **kw: None)
    monkeypatch.setattr("pymilvus.utility.has_collection", lambda name: True)

    class FakeSettings:
        milvus_uri = "http://localhost:19530"
        milvus_token = ""

    return MilvusIndexer(FakeSettings())


def test_bulk_index_marks_chunks_not_ready(monkeypatch):
    from fish_worker.chunker.text_chunker import TextChunk

    ctx = _make_fake_collection(monkeypatch)
    indexer = _make_indexer(monkeypatch)
    chunks = [TextChunk(text="hi", chunk_index=0, page=1, token_count=2)]

    indexer.bulk_index_document_chunks(
        collection_name="fish_user_knowledge",
        task_id="t1",
        scope_private=True,
        user_id="u1",
        file_name="f.pdf",
        file_type="pdf",
        chunks=chunks,
        vectors=[[0.1]],
        batch_size=10,
    )

    assert ctx["inserts"], "insert 应至少被调用一次"
    # 入库阶段必须 ready=False，避免半批写入时被检索到
    assert all(row.get("ready") is False for row in ctx["inserts"])


def test_mark_doc_ready_upserts_ready_true_for_doc_id(monkeypatch):
    indexer = _make_indexer(monkeypatch)

    ctx = _make_fake_collection(monkeypatch)
    # 模拟 query 返回两条该 doc_id 的切片
    ctx["query_results"].extend([
        {"id": "t1_0", "ready": False},
        {"id": "t1_1", "ready": False},
    ])

    indexer.mark_doc_ready("fish_user_knowledge", "t1")

    assert ctx["upserts"], "mark_doc_ready 应调用 upsert"
    assert len(ctx["upserts"]) == 2
    for row in ctx["upserts"]:
        assert row["ready"] is True


def test_mark_doc_ready_retries_then_raises_when_upsert_keeps_failing(monkeypatch):
    # Milvus 持续失败时按 max_attempts 重试，耗尽后抛出（让 processor 把任务留在可恢复态）
    import types

    indexer = _make_indexer(monkeypatch)
    indexer._s = types.SimpleNamespace(
        milvus_mark_ready_max_attempts=2,
        milvus_mark_ready_backoff_base=0,
    )

    attempts = {"n": 0}

    class FailingCollection:
        def load(self, **kw):
            pass

        def query(self, expr, output_fields):
            attempts["n"] += 1
            raise RuntimeError("Milvus 不可用")

    monkeypatch.setattr("pymilvus.Collection", lambda name, **kw: FailingCollection())

    with pytest.raises(RuntimeError):
        indexer.mark_doc_ready("fish_user_knowledge", "t1")
    assert attempts["n"] == 2, f"应重试 max_attempts 次，实际 {attempts['n']}"
