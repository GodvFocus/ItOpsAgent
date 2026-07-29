"""把旧 Milvus collection 复制到原生 BM25 collection。

脚本只创建并写入新的目标 collection，不删除、不修改旧 collection。
必须先把 Milvus Server 升级到 2.5+，因为 BM25 Function 由 Server 负责生成 sparse 向量。
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from typing import Any

from pymilvus import (
    Collection,
    CollectionSchema,
    connections,
    DataType,
    FieldSchema,
    Function,
    FunctionType,
    MilvusClient,
    utility,
)


@dataclass(frozen=True)
class MigrationPair:
    source: str
    target: str


BASE_FIELDS = [
    FieldSchema(name="id", dtype=DataType.VARCHAR, is_primary=True, max_length=128),
    FieldSchema(name="content", dtype=DataType.VARCHAR, max_length=65535, enable_analyzer=True),
    FieldSchema(name="embedding", dtype=DataType.FLOAT_VECTOR, dim=1024),
    FieldSchema(name="sparse_embedding", dtype=DataType.SPARSE_FLOAT_VECTOR),
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
    FieldSchema(name="workspace_id", dtype=DataType.VARCHAR, max_length=64),
    FieldSchema(name="visibility", dtype=DataType.VARCHAR, max_length=32),
    FieldSchema(name="source_type", dtype=DataType.VARCHAR, max_length=32),
    FieldSchema(name="superseded", dtype=DataType.BOOL),
]

COPY_FIELDS = [field.name for field in BASE_FIELDS if field.name != "sparse_embedding"]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="迁移 Milvus collection 到原生 BM25 collection")
    parser.add_argument("--uri", default="http://localhost:19530")
    parser.add_argument("--token", default="")
    parser.add_argument("--batch-size", type=int, default=500)
    parser.add_argument("--dry-run", action="store_true", help="只检查服务版本和迁移计划，不创建或写入数据")
    parser.add_argument("--embedding-dimension", type=int, default=1024)
    return parser.parse_args()


def ensure_bm25_collection(name: str, dimension: int) -> Collection:
    schema = CollectionSchema(
        fields=[
            field if field.name != "embedding"
            else FieldSchema(name="embedding", dtype=DataType.FLOAT_VECTOR, dim=dimension)
            for field in BASE_FIELDS
        ],
        functions=[Function(
            name="text_bm25",
            function_type=FunctionType.BM25,
            input_field_names=["content"],
            output_field_names=["sparse_embedding"],
            description="Fish-Agent 原生 BM25 Function",
        )],
        description=f"Fish-Agent native BM25: {name}",
    )
    collection = Collection(name=name, schema=schema)
    collection.create_index(
        field_name="embedding",
        index_params={"index_type": "IVF_FLAT", "metric_type": "COSINE", "params": {"nlist": 128}},
    )
    collection.create_index(
        field_name="sparse_embedding",
        index_params={
            "index_type": "SPARSE_INVERTED_INDEX",
            "metric_type": "BM25",
            "params": {
                "inverted_index_algo": "DAAT_MAXSCORE",
                "bm25_k1": 1.2,
                "bm25_b": 0.75,
            },
        },
    )
    collection.load()
    return collection


def migrate_pair(pair: MigrationPair, batch_size: int, dimension: int) -> int:
    if not utility.has_collection(pair.source):
        raise RuntimeError(f"源 collection 不存在: {pair.source}")
    if utility.has_collection(pair.target):
        raise RuntimeError(f"目标 collection 已存在，为避免重复写入而停止: {pair.target}")

    source = Collection(pair.source)
    source.load()
    target = ensure_bm25_collection(pair.target, dimension)
    iterator = source.query_iterator(
        expr="",
        output_fields=COPY_FIELDS,
        batch_size=batch_size,
    )
    total = 0
    try:
        while True:
            rows: list[dict[str, Any]] = iterator.next()
            if not rows:
                break
            target.insert(rows)
            total += len(rows)
    finally:
        iterator.close()
    target.flush()
    return total


def main() -> int:
    args = parse_args()
    client = MilvusClient(uri=args.uri, token=args.token or None)
    version = client.get_server_version()
    pairs = [
        MigrationPair("itops_user_knowledge", "itops_user_knowledge_bm25"),
        MigrationPair("itops_public_knowledge", "itops_public_knowledge_bm25"),
        MigrationPair("itops_user_memory", "itops_user_memory_bm25"),
    ]
    print(json.dumps({"server_version": version, "pairs": [pair.__dict__ for pair in pairs]}, ensure_ascii=False))
    if args.dry_run:
        return 0 if supports_bm25(version) else 2
    if not supports_bm25(version):
        print("Milvus Server 低于 2.5，不能执行原生 BM25 迁移", file=sys.stderr)
        return 2

    connections.connect(alias="default", uri=args.uri, token=args.token or None)
    summary = {}
    for pair in pairs:
        summary[pair.target] = migrate_pair(pair, args.batch_size, args.embedding_dimension)
    print(json.dumps({"migrated_rows": summary}, ensure_ascii=False))
    return 0


def supports_bm25(version: str | None) -> bool:
    if not version:
        return False
    parts = version.lstrip("v").split(".")
    try:
        major, minor = int(parts[0]), int(parts[1])
    except (ValueError, IndexError):
        return False
    return major > 2 or (major == 2 and minor >= 5)


if __name__ == "__main__":
    raise SystemExit(main())
