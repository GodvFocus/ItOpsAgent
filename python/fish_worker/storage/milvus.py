"""批量写入文档切片到 Milvus Collection（itops_user_knowledge 或 itops_public_knowledge）。

幂等策略：Milvus 主键 = {task_id}_{chunk_index}，insert 时主键冲突会报错，
因此在写入前先 delete_by_doc_id 清理旧切片。
"""

from __future__ import annotations

import logging
import time
from typing import Any

from pymilvus import Collection, connections, utility, DataType, FieldSchema, CollectionSchema

from fish_worker.chunker.text_chunker import TextChunk
from fish_worker.config import Settings

log = logging.getLogger(__name__)

# BGE-M3 向量维度
_BGE_M3_DIM = 1024


def _ensure_collection(name: str) -> Collection:
    """确保 Collection 存在，不存在则创建（含 BM25 analyzer 的 content 字段 + dense vector 字段）。"""
    if utility.has_collection(name):
        return Collection(name)

    fields = [
        FieldSchema(name="id", dtype=DataType.VARCHAR, is_primary=True, max_length=128),
        FieldSchema(name="content", dtype=DataType.VARCHAR, max_length=65535, enable_analyzer=True),
        FieldSchema(name="embedding", dtype=DataType.FLOAT_VECTOR, dim=_BGE_M3_DIM),
        FieldSchema(name="doc_id", dtype=DataType.VARCHAR, max_length=64),
        FieldSchema(name="chunk_index", dtype=DataType.INT64),
        FieldSchema(name="doc_name", dtype=DataType.VARCHAR, max_length=512),
        FieldSchema(name="file_type", dtype=DataType.VARCHAR, max_length=32),
        FieldSchema(name="page_number", dtype=DataType.INT64),
        FieldSchema(name="token_count", dtype=DataType.INT64),
        FieldSchema(name="authority", dtype=DataType.FLOAT),
        FieldSchema(name="doc_created_at", dtype=DataType.INT64),
        FieldSchema(name="context_prefix", dtype=DataType.VARCHAR, max_length=2048),
        FieldSchema(name="contextualized_content", dtype=DataType.VARCHAR, max_length=65535),
        FieldSchema(name="ready", dtype=DataType.BOOL),
        FieldSchema(name="created_at", dtype=DataType.INT64),
        FieldSchema(name="user_id", dtype=DataType.VARCHAR, max_length=64),
    ]
    schema = CollectionSchema(fields=fields, description=f"Fish-Agent knowledge: {name}")
    col = Collection(name=name, schema=schema)

    # 为 embedding 字段创建 IVF_FLAT 索引（适合中小规模）
    index_params = {
        "index_type": "IVF_FLAT",
        "metric_type": "COSINE",
        "params": {"nlist": 128},
    }
    col.create_index(field_name="embedding", index_params=index_params)

    log.info("Created Milvus collection: %s", name)
    return col


class MilvusIndexer:

    def __init__(self, settings: Settings) -> None:
        self._s = settings
        # 连接 Milvus（幂等：已连接则跳过）
        kw: dict[str, Any] = {"uri": settings.milvus_uri}
        if settings.milvus_token:
            kw["token"] = settings.milvus_token
        connections.connect(alias="default", **kw)

    def delete_by_doc_id(self, collection_name: str, doc_id: str) -> None:
        """写入前清理该 doc_id 下的旧切片，保证幂等重处理不产生残留。"""
        try:
            col = _ensure_collection(collection_name)
            col.load()
            col.delete(expr=f'doc_id == "{doc_id}"')
            log.debug("Milvus delete_by_doc_id doc_id=%s collection=%s", doc_id, collection_name)
        except Exception as e:
            log.warning("Milvus delete_by_doc_id failed doc_id=%s collection=%s: %s", doc_id, collection_name, e)

    def bulk_index_document_chunks(
        self,
        *,
        collection_name: str,
        task_id: str,
        scope_private: bool,
        user_id: str | None,
        file_name: str,
        file_type: str,
        chunks: list[TextChunk],
        vectors: list[list[float]],
        batch_size: int,
        default_authority: float = 1.0,
        doc_created_at_ms: int | None = None,
    ) -> None:
        if len(chunks) != len(vectors):
            raise ValueError("chunks and vectors length mismatch")

        col = _ensure_collection(collection_name)
        now_ms = int(time.time() * 1000)

        rows: list[dict[str, Any]] = []
        for ch, vec in zip(chunks, vectors):
            row = {
                "id": f"{task_id}_{ch.chunk_index}",
                "content": ch.text,
                "embedding": vec,
                "doc_id": task_id,
                "chunk_index": ch.chunk_index,
                "doc_name": file_name,
                "file_type": file_type,
                "page_number": ch.page,
                "token_count": ch.token_count,
                "authority": float(default_authority),
                "doc_created_at": doc_created_at_ms if doc_created_at_ms is not None else now_ms,
                "context_prefix": ch.context_prefix or "",
                "contextualized_content": ch.contextualized_text or ch.text,
                "ready": False,
                "created_at": now_ms,
                "user_id": user_id or "",
            }
            rows.append(row)

        # 分批 insert
        col.load()
        for i in range(0, len(rows), batch_size):
            batch = rows[i : i + batch_size]
            try:
                col.insert(batch)
                log.debug("Milvus inserted %s rows (batch slice)", len(batch))
            except Exception as e:
                log.error("Milvus insert failed at offset %s: %s", i, e)
                raise RuntimeError(f"Milvus insert failed at offset {i}: {e!r}")

    def mark_doc_ready(self, collection_name: str, doc_id: str) -> None:
        """全部 chunk 写入完成后，翻 ready=True 让检索可见。

        Milvus insert 立即可见，这里只需 upsert ready 字段。
        重试逻辑与旧 ES 实现一致，耗尽则抛出让任务留在可恢复态。
        """
        max_attempts = max(1, int(getattr(self._s, "milvus_mark_ready_max_attempts", 3)))
        backoff_base = float(getattr(self._s, "milvus_mark_ready_backoff_base", 0.5))

        col = _ensure_collection(collection_name)
        col.load()

        # 查询该 doc 所有 chunk 的 id
        results = col.query(
            expr=f'doc_id == "{doc_id}"',
            output_fields=["id", "ready"],
        )

        last_exc: Exception | None = None
        for attempt in range(1, max_attempts + 1):
            try:
                for r in results:
                    r["ready"] = True
                if results:
                    col.upsert(results)
                log.debug("Milvus mark_doc_ready doc_id=%s collection=%s attempt=%s", doc_id, collection_name, attempt)
                return
            except Exception as e:
                last_exc = e
                log.warning(
                    "Milvus mark_doc_ready attempt %s/%s failed doc_id=%s collection=%s: %s",
                    attempt, max_attempts, doc_id, collection_name, e,
                )
                if attempt < max_attempts:
                    time.sleep(backoff_base * (2 ** (attempt - 1)))

        log.error("Milvus mark_doc_ready exhausted retries doc_id=%s collection=%s", doc_id, collection_name)
        raise RuntimeError(
            f"mark_doc_ready failed for doc_id={doc_id} collection={collection_name} after {max_attempts} attempts"
        ) from last_exc
