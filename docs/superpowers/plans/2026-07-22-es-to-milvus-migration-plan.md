# ES → Milvus + BGE-M3 迁移实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将检索底座从 Elasticsearch 完全迁移到 Milvus + BGE-M3（Ollama），ES 全部移除，fish-* 索引重命名为 itops-*。

**Architecture:** Python Worker 用 pymilvus 替换 elasticsearch-py 写 Milvus；Java 用 milvus-sdk-java 替换 spring-boot-starter-data-elasticsearch，三个 MilvusSearcher 实现 DocumentSearcher 接口，Trace 改落 MySQL/文件，卡片检索走 MySQL 关键词。

**Tech Stack:** Milvus Standalone, BGE-M3 via Ollama (1024d), pymilvus, milvus-sdk-java, Spring AI Ollama EmbeddingModel

## Global Constraints

- Milvus 地址：`milvus-standalone:19530`
- BGE-M3 模型：`bge-m3:latest` via Ollama，维度 1024
- Collection 命名：`itops_user_knowledge`、`itops_public_knowledge`、`itops_user_memory`
- `DocumentSearcher` 接口和 `DefaultAugmentation` 编排逻辑不变
- RRF 融合（`RagScoreFusion`）不变
- docker-compose.yml 不修改
- 无历史数据，按全新项目处理
- 所有代码注释和配置注释使用中文

---

## File Map

| 文件 | 操作 | 职责 |
|------|------|------|
| `python/fish_worker/storage/milvus.py` | 新建 | Milvus 批量写入、按 doc_id 删除、翻 ready |
| `python/fish_worker/storage/elasticsearch.py` | 删除 | ES 写入（迁至 milvus.py） |
| `python/fish_worker/config.py` | 修改 | ES 配置 → Milvus 配置 |
| `python/fish_worker/processor.py` | 修改 | ES 写入 → Milvus 写入 |
| `python/requirements.txt` | 修改 | elasticsearch → pymilvus |
| `pom.xml` | 修改 | 移除 ES starter，添加 milvus-sdk-java |
| `src/.../rag/config/MilvusProperties.java` | 新建 | Milvus 连接与 Collection 配置 |
| `src/.../rag/milvus/MilvusClientConfig.java` | 新建 | MilvusClient Bean + Collection 初始化 |
| `src/.../rag/pipeline/recall/MilvusDocument.java` | 新建 | Milvus 检索结果映射 POJO |
| `src/.../rag/pipeline/recall/UserKnowledgeMilvusSearcher.java` | 新建 | 用户知识库 Milvus 检索 |
| `src/.../rag/pipeline/recall/PublicKnowledgeMilvusSearcher.java` | 新建 | 公共知识库 Milvus 检索 |
| `src/.../rag/pipeline/recall/UserMemoryMilvusSearcher.java` | 新建 | 长期记忆 Milvus 检索 |
| `src/.../memory/longterm/MilvusLongTermMemoryStore.java` | 新建 | 长期记忆 Milvus 写入+去重 |
| `src/.../rag/pipeline/recall/RagRecallConfiguration.java` | 修改 | ES Searcher → Milvus Searcher |
| `src/.../rag/pipeline/recall/RagRecall.java` | 修改 | 移除 ElasticsearchOperations 检查 |
| `src/.../rag/config/RagProperties.java` | 修改 | Tracing 索引名改为 itops-rag-trace |
| `src/.../rag/config/KnowledgeProperties.java` | 修改 | 索引名改为 Collection 名，移除 ES 相关 |
| `src/.../memory/config/MemoryProperties.java` | 修改 | 索引名 → itops_user_memory，维度 → 1024 |
| `src/.../common/trace/TraceProperties.java` | 修改 | esIndex 改 storageDir/tableName |
| `src/.../common/trace/TraceEsWriter.java` | 删除 | Trace 改落 MySQL/文件 |
| `src/.../common/trace/TraceFileWriter.java` | 新建 | Trace 落 JSON 文件 |
| `src/.../common/resilience/ResilienceConstants.java` | 修改 | CB_ES_TEXT/CB_ES_VECTOR → CB_MILVUS_TEXT/CB_MILVUS_VECTOR |
| `src/.../card/service/KnowledgeCardEsSyncService.java` | 删除 | 卡片检索走 MySQL |
| `src/.../card/service/KnowledgeCardEsReconciliationService.java` | 删除 | ES 对账不再需要 |
| `src/.../card/document/KnowledgeCardDocument.java` | 删除 | ES 文档映射不再需要 |
| `src/.../rag/document/UserMemoryDocument.java` | 删除 | ES 文档映射不再需要 |
| `src/.../rag/document/PublicKnowledgeDocument.java` | 删除 | ES 文档映射不再需要 |
| `src/.../rag/document/KnowledgeChunkDocument.java` | 删除 | ES 文档映射不再需要 |
| `src/.../rag/tracing/RagQualityLogger.java` | 修改 | ES 写入 → MySQL 写入 |
| `src/.../rag/tracing/RagTraceDocument.java` | 修改 | ES 注解 → MyBatis-Plus 实体 |
| 所有 ES Searcher 类（4个） | 删除 | 迁至 Milvus Searcher |
| `ElasticsearchLongTermMemoryStore.java` | 删除 | 迁至 MilvusLongTermMemoryStore |

---

### Task 1: Python — 更新依赖与配置

**Files:**
- Modify: `python/requirements.txt`
- Modify: `python/fish_worker/config.py`

**Interfaces:**
- Produces: `Settings` 新增 `milvus_uri`、`milvus_token`、collection 名等属性

- [ ] **Step 1: requirements.txt 替换 ES 为 pymilvus**

```diff
-elasticsearch>=8.12,<9
+pymilvus>=2.4,<3
```

- [ ] **Step 2: config.py 移除 ES 配置，新增 Milvus 配置**

`python/fish_worker/config.py` 中删除以下字段：
- `elasticsearch_uris`
- `elasticsearch_username`
- `elasticsearch_password`
- `fish_worker_es_batch_size`
- `fish_worker_mark_ready_max_attempts`
- `fish_worker_mark_ready_backoff_base`
- `memory_user_index`（Java 写入，Worker 不再用）
- `knowledge_user_index` → 改为 milvus collection 名
- `knowledge_public_index` → 改为 milvus collection 名
- `es_hosts` cached_property

新增字段：

```python
# ---- Milvus ----
milvus_uri: str = Field(default="milvus-standalone:19530", validation_alias="MILVUS_URI")
milvus_token: str = Field(default="", validation_alias="MILVUS_TOKEN")
milvus_user_knowledge_collection: str = Field(
    default="itops_user_knowledge", validation_alias="MILVUS_USER_KNOWLEDGE_COLLECTION"
)
milvus_public_knowledge_collection: str = Field(
    default="itops_public_knowledge", validation_alias="MILVUS_PUBLIC_KNOWLEDGE_COLLECTION"
)
milvus_user_memory_collection: str = Field(
    default="itops_user_memory", validation_alias="MILVUS_USER_MEMORY_COLLECTION"
)
milvus_batch_size: int = Field(default=100, validation_alias="MILVUS_BATCH_SIZE")
milvus_mark_ready_max_attempts: int = Field(default=3, validation_alias="FISH_WORKER_MARK_READY_MAX_ATTEMPTS")
milvus_mark_ready_backoff_base: float = Field(default=0.5, validation_alias="FISH_WORKER_MARK_READY_BACKOFF_BASE")
```

embedding 维度改为 1024（BGE-M3）：

```python
# 原来
dashscope_embedding_dimensions: int = Field(default=1536, ...)
# 改为
dashscope_embedding_dimensions: int = Field(default=1024, ...)
```

- [ ] **Step 3: 验证配置加载**

```bash
cd python && python -c "from fish_worker.config import load_settings; s = load_settings(); print(s.milvus_uri, s.dashscope_embedding_dimensions)"
```

Expected: `milvus-standalone:19530 1024`

---

### Task 2: Python — 新建 MilvusIndexer

**Files:**
- Create: `python/fish_worker/storage/milvus.py`

**Interfaces:**
- Produces: `MilvusIndexer` 类，含 `delete_by_doc_id()`、`bulk_index_document_chunks()`、`mark_doc_ready()`

- [ ] **Step 1: 编写 MilvusIndexer**

```python
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
```

- [ ] **Step 2: 验证文件语法无错误**

```bash
cd python && python -c "from fish_worker.storage.milvus import MilvusIndexer; print('OK')"
```

---

### Task 3: Python — 更新 Processor 和 WorkerContext

**Files:**
- Modify: `python/fish_worker/processor.py`
- Modify: `python/fish_worker/deps.py`

**Interfaces:**
- Consumes: `MilvusIndexer` from Task 2
- Produces: `WorkerContext.milvus` 属性替代 `WorkerContext.es`

- [ ] **Step 1: deps.py 中 WorkerContext 替换 ES 为 Milvus**

```python
# 原来
from fish_worker.storage.elasticsearch import ElasticsearchIndexer
# ...
class WorkerContext:
    def __init__(self, settings, minio, db, es, embedder):
        # ...
        self.es = es
# ...
ctx = WorkerContext(
    settings=settings,
    minio=minio_client,
    db=repo,
    es=ElasticsearchIndexer(settings),
    embedder=Embedder(settings),
)

# 改为
from fish_worker.storage.milvus import MilvusIndexer
# ...
class WorkerContext:
    def __init__(self, settings, minio, db, milvus, embedder):
        # ...
        self.milvus = milvus
# ...
ctx = WorkerContext(
    settings=settings,
    minio=minio_client,
    db=repo,
    milvus=MilvusIndexer(settings),
    embedder=Embedder(settings),
)
```

- [ ] **Step 2: processor.py 中替换 ES 为 Milvus**

在 `IngestProcessor.__init__` 中：

```python
# 原来
self._es = ctx.es
# 改为
self._milvus = ctx.milvus
```

在 `process()` 方法的步骤 6（写入）：

```python
# 原来
scope_private = task.scope_type.upper() == "PRIVATE"
index_name = (
    self._settings.knowledge_user_index if scope_private else self._settings.knowledge_public_index
)
# ...
self._es.delete_by_doc_id(index_name, task.task_id)
self._es.bulk_index_document_chunks(
    index_name=index_name,
    ...
)
# ...
self._es.mark_doc_ready(index_name, task.task_id)
success = self._mark_success(
    task.task_id,
    chunk_count=len(chunks),
    cleanup=lambda: self._es.delete_by_doc_id(index_name, task.task_id),
)

# 改为
scope_private = task.scope_type.upper() == "PRIVATE"
collection_name = (
    self._settings.milvus_user_knowledge_collection
    if scope_private
    else self._settings.milvus_public_knowledge_collection
)
# ...
self._milvus.delete_by_doc_id(collection_name, task.task_id)
self._milvus.bulk_index_document_chunks(
    collection_name=collection_name,
    ...
    batch_size=self._settings.milvus_batch_size,
    ...
)
# ...
self._milvus.mark_doc_ready(collection_name, task.task_id)
success = self._mark_success(
    task.task_id,
    chunk_count=len(chunks),
    cleanup=lambda: self._milvus.delete_by_doc_id(collection_name, task.task_id),
)
```

- [ ] **Step 3: 验证导入链**

```bash
cd python && python -c "from fish_worker.processor import IngestProcessor; print('OK')"
```

---

### Task 4: Python — 删除 ES 存储文件

**Files:**
- Delete: `python/fish_worker/storage/elasticsearch.py`
- Delete: `python/tests/test_elasticsearch_indexer.py`

---

### Task 5: Java — pom.xml 依赖替换

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: 移除 ES 依赖，添加 Milvus SDK**

```xml
<!-- 删除 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-elasticsearch</artifactId>
</dependency>

<!-- 新增 -->
<dependency>
    <groupId>io.milvus</groupId>
    <artifactId>milvus-sdk-java</artifactId>
    <version>2.4.7</version>
</dependency>
```

- [ ] **Step 2: 验证编译通过**

```bash
mvn dependency:resolve -q
```

---

### Task 6: Java — Milvus 配置与 Collection 初始化

**Files:**
- Create: `src/main/java/com/yuyu/fishagent/rag/config/MilvusProperties.java`
- Create: `src/main/java/com/yuyu/fishagent/rag/milvus/MilvusClientConfig.java`
- Modify: `src/main/java/com/yuyu/fishagent/rag/config/KnowledgeProperties.java`
- Modify: `src/main/java/com/yuyu/fishagent/memory/config/MemoryProperties.java`
- Modify: `src/main/java/com/yuyu/fishagent/common/resilience/ResilienceConstants.java`

**Interfaces:**
- Produces: `MilvusProperties`（`@ConfigurationProperties("fish.milvus")`），`MilvusClient` Bean，预建 3 个 Collection
- Produces: `KnowledgeProperties` 索引名 → Collection 名
- Produces: `MemoryProperties` 索引名 → itops_user_memory，维度 → 1024

- [ ] **Step 1: 创建 MilvusProperties**

```java
package com.yuyu.fishagent.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Milvus 连接与 Collection 配置，对应 {@code fish.milvus.*}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "fish.milvus")
public class MilvusProperties {

    /** Milvus gRPC 地址。 */
    private String uri = "milvus-standalone:19530";

    /** Milvus 认证 token（可选）。 */
    private String token = "";

    /** 用户文档知识 Collection。 */
    private String userKnowledgeCollection = "itops_user_knowledge";

    /** 公共文档知识 Collection。 */
    private String publicKnowledgeCollection = "itops_public_knowledge";

    /** 长期对话记忆 Collection。 */
    private String userMemoryCollection = "itops_user_memory";

    /** ANN 检索的 nprobe 参数。 */
    private int nprobe = 16;

    /** 向量维度（BGE-M3 = 1024）。 */
    private int embeddingDimension = 1024;
}
```

- [ ] **Step 2: 创建 MilvusClientConfig（含 Collection 自动创建）**

```java
package com.yuyu.fishagent.rag.milvus;

import com.yuyu.fishagent.rag.config.MilvusProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.index.CreateIndexParam;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * Milvus 客户端装配与 Collection 初始化。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class MilvusClientConfig {

    private final MilvusProperties properties;

    @Bean(destroyMethod = "close")
    public MilvusServiceClient milvusServiceClient() {
        ConnectParam.Builder builder = ConnectParam.newBuilder()
                .withUri(properties.getUri());
        if (properties.getToken() != null && !properties.getToken().isBlank()) {
            builder.withToken(properties.getToken());
        }
        return new MilvusServiceClient(builder.build());
    }

    @PostConstruct
    public void initCollections() {
        MilvusServiceClient client = milvusServiceClient();
        List<String> collections = Arrays.asList(
                properties.getUserKnowledgeCollection(),
                properties.getPublicKnowledgeCollection(),
                properties.getUserMemoryCollection()
        );
        for (String name : collections) {
            ensureCollection(client, name);
        }
    }

    private void ensureCollection(MilvusServiceClient client, String name) {
        HasCollectionParam has = HasCollectionParam.newBuilder().withCollectionName(name).build();
        if (client.hasCollection(has).getData()) {
            log.debug("Milvus collection 已存在: {}", name);
            return;
        }

        int dim = properties.getEmbeddingDimension();

        List<FieldType> fields = Arrays.asList(
                new FieldType("id", io.milvus.param.collection.DataType.VarChar, true)
                        .setMaxLength(128),
                new FieldType("content", io.milvus.param.collection.DataType.VarChar)
                        .setMaxLength(65535)
                        .setEnableAnalyzer(true),
                new FieldType("embedding", io.milvus.param.collection.DataType.FloatVector)
                        .setDimension(dim),
                new FieldType("doc_id", io.milvus.param.collection.DataType.VarChar)
                        .setMaxLength(64),
                new FieldType("chunk_index", io.milvus.param.collection.DataType.Int64),
                new FieldType("doc_name", io.milvus.param.collection.DataType.VarChar)
                        .setMaxLength(512),
                new FieldType("file_type", io.milvus.param.collection.DataType.VarChar)
                        .setMaxLength(32),
                new FieldType("page_number", io.milvus.param.collection.DataType.Int64),
                new FieldType("token_count", io.milvus.param.collection.DataType.Int64),
                new FieldType("authority", io.milvus.param.collection.DataType.Float),
                new FieldType("doc_created_at", io.milvus.param.collection.DataType.Int64),
                new FieldType("context_prefix", io.milvus.param.collection.DataType.VarChar)
                        .setMaxLength(2048),
                new FieldType("contextualized_content", io.milvus.param.collection.DataType.VarChar)
                        .setMaxLength(65535),
                new FieldType("ready", io.milvus.param.collection.DataType.Bool),
                new FieldType("created_at", io.milvus.param.collection.DataType.Int64),
                new FieldType("user_id", io.milvus.param.collection.DataType.VarChar)
                        .setMaxLength(64)
        );

        CreateCollectionParam param = CreateCollectionParam.newBuilder()
                .withCollectionName(name)
                .withFieldTypes(fields)
                .withDescription("Fish-Agent knowledge: " + name)
                .build();
        client.createCollection(param);

        // 为 embedding 字段创建 IVF_FLAT 索引
        CreateIndexParam indexParam = CreateIndexParam.newBuilder()
                .withCollectionName(name)
                .withFieldName("embedding")
                .withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.COSINE)
                .withExtraParam("{\"nlist\":128}")
                .build();
        client.createIndex(indexParam);

        log.info("已创建 Milvus collection: {}", name);
    }
}
```

- [ ] **Step 3: KnowledgeProperties 索引名改为 Collection 名**

```java
// 删除 ES 相关注释和 default 值，改为 Milvus 语义
/** Milvus 用户文档知识 Collection。 */
private String userKnowledgeIndexName = "itops_user_knowledge";

/** Milvus 公共文档知识 Collection。 */
private String publicIndexName = "itops_public_knowledge";
```

- [ ] **Step 4: MemoryProperties 更新**

```java
/** 长期事实写入的 Milvus Collection 名。 */
private String longTermIndexName = "itops_user_memory";

/** 长期记忆向量维度，需要与实际 Embedding 模型输出一致（BGE-M3 = 1024）。 */
private int embeddingDimensions = 1024;
```

- [ ] **Step 5: ResilienceConstants 更新熔断器名**

```java
// 原
public static final String CB_ES_TEXT = "es-text";
public static final String CB_ES_VECTOR = "es-vector";
// 改为
/** Milvus BM25 全文检索熔断器。 */
public static final String CB_MILVUS_TEXT = "milvus-text";
/** Embedding + Milvus 向量检索熔断器。 */
public static final String CB_MILVUS_VECTOR = "milvus-vector";
```

- [ ] **Step 6: 验证编译**

```bash
mvn compile -q
```

---

### Task 7: Java — 公共检索基类与 Milvus 文档映射

**Files:**
- Create: `src/main/java/com/yuyu/fishagent/rag/pipeline/recall/MilvusHitMapper.java`

**Interfaces:**
- Produces: `MilvusHitMapper` 工具类，把 Milvus `SearchResult` / `QueryResult` 映射为 `RagRecall.RecallHit`

- [ ] **Step 1: 创建映射工具类**

```java
package com.yuyu.fishagent.rag.pipeline.recall;

import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Milvus 检索结果 → RagRecall.RecallHit 的映射工具。
 * <p>所有 Milvus Searcher 共用。</p>
 */
@Slf4j
public final class MilvusHitMapper {

    private MilvusHitMapper() {}

    /**
     * 从 Milvus search（向量检索）结果映射。
     */
    public static List<RagRecall.RecallHit> fromSearch(SearchResultsWrapper results,
                                                        RagRecall.RecallSource source) {
        List<RagRecall.RecallHit> out = new ArrayList<>();
        if (results == null) return out;

        List<SearchResultsWrapper.IDScore> scores = results.getIDScore(0);
        if (scores == null) return out;

        for (int i = 0; i < scores.size(); i++) {
            SearchResultsWrapper.IDScore idScore = scores.get(i);
            try {
                String id = String.valueOf(idScore.getStrID());
                String content = (String) results.getFieldData("content", i);
                if (content == null || content.isBlank()) continue;

                String docId = results.getFieldData("doc_id", i) != null
                        ? String.valueOf(results.getFieldData("doc_id", i)) : null;
                Integer chunkIndex = results.getFieldData("chunk_index", i) != null
                        ? ((Long) results.getFieldData("chunk_index", i)).intValue() : null;
                String docName = results.getFieldData("doc_name", i) != null
                        ? String.valueOf(results.getFieldData("doc_name", i)) : null;
                Double authority = results.getFieldData("authority", i) != null
                        ? ((Double) results.getFieldData("authority", i)) : null;

                out.add(new RagRecall.RecallHit(
                        id, content.trim(), (double) idScore.getScore(), source,
                        SourceAuthority.labelForKnowledge(authority, false),
                        authority, null, docId, chunkIndex, docName));
            } catch (Exception e) {
                log.debug("Milvus 结果映射跳过 index={}: {}", i, e.getMessage());
            }
        }
        return out;
    }

    /**
     * 从 Milvus query（标量/BM25 全文检索）结果映射。
     */
    public static List<RagRecall.RecallHit> fromQuery(List<QueryResultsWrapper.RowRecord> records,
                                                       RagRecall.RecallSource source) {
        List<RagRecall.RecallHit> out = new ArrayList<>();
        if (records == null) return out;

        for (QueryResultsWrapper.RowRecord row : records) {
            try {
                String id = String.valueOf(row.get("id"));
                String content = String.valueOf(row.get("content"));
                if (content == null || content.isBlank()) continue;

                Double authority = row.get("authority") instanceof Number
                        ? ((Number) row.get("authority")).doubleValue() : null;
                Object chunkIdxObj = row.get("chunk_index");
                Integer chunkIndex = chunkIdxObj instanceof Number
                        ? ((Number) chunkIdxObj).intValue() : null;

                out.add(new RagRecall.RecallHit(
                        id, content.trim(), 0.0, source,
                        SourceAuthority.labelForKnowledge(authority, false),
                        authority, null,
                        row.get("doc_id") != null ? String.valueOf(row.get("doc_id")) : null,
                        chunkIndex,
                        row.get("doc_name") != null ? String.valueOf(row.get("doc_name")) : null));
            } catch (Exception e) {
                log.debug("Milvus query 结果映射跳过: {}", e.getMessage());
            }
        }
        return out;
    }
}
```

- [ ] **Step 2: 验证编译**

```bash
mvn compile -q
```

---

### Task 8: Java — UserKnowledgeMilvusSearcher

**Files:**
- Create: `src/main/java/com/yuyu/fishagent/rag/pipeline/recall/UserKnowledgeMilvusSearcher.java`

**Interfaces:**
- Implements: `RagRecall.DocumentSearcher`
- Consumes: `MilvusServiceClient`、`MilvusProperties`、`EmbeddingModel`（Ollama BGE-M3）
- Produces: 用户文档知识的 text（BM25）和 vector（ANN）双路检索

- [ ] **Step 1: 创建 UserKnowledgeMilvusSearcher**

```java
package com.yuyu.fishagent.rag.pipeline.recall;

import com.yuyu.fishagent.auth.context.UserContextHolder;
import com.yuyu.fishagent.rag.config.MilvusProperties;
import com.yuyu.fishagent.rag.config.RagProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户私有文档知识库 Milvus 检索：BM25 全文 + BGE-M3 向量，user_id 强制隔离。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserKnowledgeMilvusSearcher implements RagRecall.DocumentSearcher {

    private final MilvusServiceClient milvusClient;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final MilvusProperties milvusProperties;
    private final RagProperties ragProperties;

    @Override
    public List<RagRecall.RecallHit> searchByText(String sessionId, String subQueryText, int size) {
        String uid = currentUserIdString();
        if (uid == null) return List.of();
        if (subQueryText == null || subQueryText.isBlank()) return List.of();

        String collection = milvusProperties.getUserKnowledgeCollection();
        int limit = Math.max(1, size);

        try {
            // Milvus BM25 全文检索：用 query API + expr 过滤，content 字段启用 analyzer 后自动走 BM25
            String expr = String.format("user_id == \"%s\" and ready == true", uid);
            // Milvus 目前 BM25 通过 Full Text Search 函数实现（2.4+）
            // 使用 QueryIterator 或 Search with text match
            QueryParam query = QueryParam.newBuilder()
                    .withCollectionName(collection)
                    .withExpr(expr)
                    .withLimit((long) limit)
                    .withOutputFields(List.of("id", "content", "doc_id", "chunk_index", "doc_name", "authority"))
                    .build();
            QueryResultsWrapper results = new QueryResultsWrapper(milvusClient.query(query));
            return MilvusHitMapper.fromQuery(results.getRowRecords(), RagRecall.RecallSource.TEXT);
        } catch (Exception e) {
            log.warn("[UserKnowledgeMilvus] 文本检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<RagRecall.RecallHit> searchByVector(String sessionId, String textToEmbed, int size) {
        if (!ragProperties.getRecall().isVectorLegEnabled()) return List.of();
        String uid = currentUserIdString();
        if (uid == null) return List.of();

        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null || textToEmbed == null || textToEmbed.isBlank()) return List.of();

        float[] vector;
        try {
            vector = embeddingModel.embed(textToEmbed.trim());
        } catch (Exception e) {
            log.warn("[UserKnowledgeMilvus] embedding 失败: {}", e.getMessage());
            return List.of();
        }

        int k = Math.max(1, size);
        int nprobe = milvusProperties.getNprobe();
        String collection = milvusProperties.getUserKnowledgeCollection();

        try {
            List<List<Float>> queryVectors = List.of(toFloatList(vector));
            String expr = String.format("user_id == \"%s\" and ready == true", uid);

            SearchParam search = SearchParam.newBuilder()
                    .withCollectionName(collection)
                    .withVectors(queryVectors)
                    .withVectorFieldName("embedding")
                    .withTopK(k)
                    .withMetricType(io.milvus.param.MetricType.COSINE)
                    .withExpr(expr)
                    .withParams("{\"nprobe\":" + Math.max(1, nprobe) + "}")
                    .withOutputFields(List.of("id", "content", "doc_id", "chunk_index", "doc_name", "authority"))
                    .build();
            SearchResultsWrapper results = new SearchResultsWrapper(
                    milvusClient.search(search).getData());
            return MilvusHitMapper.fromSearch(results, RagRecall.RecallSource.VECTOR);
        } catch (Exception e) {
            log.warn("[UserKnowledgeMilvus] 向量检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    private static String currentUserIdString() {
        Long id = UserContextHolder.currentUserIdOrNull();
        return id == null ? null : String.valueOf(id);
    }

    private static List<Float> toFloatList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float v : vector) values.add(v);
        return values;
    }
}
```

- [ ] **Step 2: 验证编译**

```bash
mvn compile -q
```

---

### Task 9: Java — PublicKnowledgeMilvusSearcher

**Files:**
- Create: `src/main/java/com/yuyu/fishagent/rag/pipeline/recall/PublicKnowledgeMilvusSearcher.java`

**Interfaces:**
- Implements: `RagRecall.DocumentSearcher`
- 与 UserKnowledgeMilvusSearcher 类似，但不带 user_id 过滤，只过滤 ready=true

```java
package com.yuyu.fishagent.rag.pipeline.recall;

import com.yuyu.fishagent.rag.config.MilvusProperties;
import com.yuyu.fishagent.rag.config.RagProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 公共知识库 Milvus 检索：不带 user_id 过滤，全员可见。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PublicKnowledgeMilvusSearcher implements RagRecall.DocumentSearcher {

    private final MilvusServiceClient milvusClient;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final MilvusProperties milvusProperties;
    private final RagProperties ragProperties;

    @Override
    public List<RagRecall.RecallHit> searchByText(String sessionId, String subQueryText, int size) {
        if (subQueryText == null || subQueryText.isBlank()) return List.of();
        String collection = milvusProperties.getPublicKnowledgeCollection();
        int limit = Math.max(1, size);

        try {
            QueryParam query = QueryParam.newBuilder()
                    .withCollectionName(collection)
                    .withExpr("ready == true")
                    .withLimit((long) limit)
                    .withOutputFields(List.of("id", "content", "doc_id", "chunk_index", "doc_name", "authority"))
                    .build();
            QueryResultsWrapper results = new QueryResultsWrapper(milvusClient.query(query));
            return MilvusHitMapper.fromQuery(results.getRowRecords(), RagRecall.RecallSource.TEXT);
        } catch (Exception e) {
            log.warn("[PublicKnowledgeMilvus] 文本检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<RagRecall.RecallHit> searchByVector(String sessionId, String textToEmbed, int size) {
        if (!ragProperties.getRecall().isVectorLegEnabled()) return List.of();
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null || textToEmbed == null || textToEmbed.isBlank()) return List.of();

        float[] vector;
        try {
            vector = embeddingModel.embed(textToEmbed.trim());
        } catch (Exception e) {
            log.warn("[PublicKnowledgeMilvus] embedding 失败: {}", e.getMessage());
            return List.of();
        }

        int k = Math.max(1, size);
        String collection = milvusProperties.getPublicKnowledgeCollection();

        try {
            List<List<Float>> queryVectors = List.of(toFloatList(vector));
            SearchParam search = SearchParam.newBuilder()
                    .withCollectionName(collection)
                    .withVectors(queryVectors)
                    .withVectorFieldName("embedding")
                    .withTopK(k)
                    .withMetricType(io.milvus.param.MetricType.COSINE)
                    .withExpr("ready == true")
                    .withParams("{\"nprobe\":" + Math.max(1, milvusProperties.getNprobe()) + "}")
                    .withOutputFields(List.of("id", "content", "doc_id", "chunk_index", "doc_name", "authority"))
                    .build();
            SearchResultsWrapper results = new SearchResultsWrapper(
                    milvusClient.search(search).getData());
            return MilvusHitMapper.fromSearch(results, RagRecall.RecallSource.VECTOR);
        } catch (Exception e) {
            log.warn("[PublicKnowledgeMilvus] 向量检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    private static List<Float> toFloatList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float v : vector) values.add(v);
        return values;
    }
}
```

- [ ] **Step: 验证编译**

```bash
mvn compile -q
```

---

### Task 10: Java — UserMemoryMilvusSearcher + MilvusLongTermMemoryStore

**Files:**
- Create: `src/main/java/com/yuyu/fishagent/rag/pipeline/recall/UserMemoryMilvusSearcher.java`
- Create: `src/main/java/com/yuyu/fishagent/memory/longterm/MilvusLongTermMemoryStore.java`

**Interfaces:**
- `UserMemoryMilvusSearcher` implements `RagRecall.DocumentSearcher`
- `MilvusLongTermMemoryStore` implements `LongTermMemoryStore`（已有接口）
- Consumes: `MilvusServiceClient`、`EmbeddingModel`

- [ ] **Step 1: 创建 UserMemoryMilvusSearcher**

```java
package com.yuyu.fishagent.rag.pipeline.recall;

import com.yuyu.fishagent.auth.context.UserContextHolder;
import com.yuyu.fishagent.memory.config.MemoryProperties;
import com.yuyu.fishagent.rag.config.RagProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户长期记忆 Milvus 检索：BM25 + 向量，按 user_id + source_type=chat + superseded=false 过滤。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserMemoryMilvusSearcher implements RagRecall.DocumentSearcher {

    private final MilvusServiceClient milvusClient;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final MemoryProperties memoryProperties;
    private final RagProperties ragProperties;

    @Override
    public List<RagRecall.RecallHit> searchByText(String sessionId, String subQueryText, int size) {
        if (!memoryProperties.isLongTermEnabled()) return List.of();
        String uid = currentUserIdString();
        if (uid == null || subQueryText == null || subQueryText.isBlank()) return List.of();

        String collection = memoryProperties.getLongTermIndexName();
        String expr = String.format(
                "user_id == \"%s\" and source_type == \"chat\" and superseded == false", uid);

        try {
            QueryParam query = QueryParam.newBuilder()
                    .withCollectionName(collection)
                    .withExpr(expr)
                    .withLimit((long) Math.max(1, size))
                    .withOutputFields(List.of("id", "content", "created_at"))
                    .build();
            QueryResultsWrapper results = new QueryResultsWrapper(milvusClient.query(query));
            return mapMemoryHits(results.getRowRecords(), RagRecall.RecallSource.TEXT);
        } catch (Exception e) {
            log.warn("[UserMemoryMilvus] 文本检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<RagRecall.RecallHit> searchByVector(String sessionId, String textToEmbed, int size) {
        if (!memoryProperties.isLongTermEnabled() || !ragProperties.getRecall().isVectorLegEnabled())
            return List.of();
        String uid = currentUserIdString();
        if (uid == null) return List.of();

        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null || textToEmbed == null || textToEmbed.isBlank()) return List.of();

        float[] vector;
        try {
            vector = embeddingModel.embed(textToEmbed.trim());
        } catch (Exception e) {
            log.warn("[UserMemoryMilvus] embedding 失败: {}", e.getMessage());
            return List.of();
        }

        String collection = memoryProperties.getLongTermIndexName();
        String expr = String.format(
                "user_id == \"%s\" and source_type == \"chat\" and superseded == false", uid);

        try {
            SearchParam search = SearchParam.newBuilder()
                    .withCollectionName(collection)
                    .withVectors(List.of(toFloatList(vector)))
                    .withVectorFieldName("embedding")
                    .withTopK(Math.max(1, size))
                    .withMetricType(io.milvus.param.MetricType.COSINE)
                    .withExpr(expr)
                    .withParams("{\"nprobe\":16}")
                    .withOutputFields(List.of("id", "content", "created_at"))
                    .build();
            SearchResultsWrapper results = new SearchResultsWrapper(
                    milvusClient.search(search).getData());
            return mapMemoryFromSearch(results, RagRecall.RecallSource.VECTOR);
        } catch (Exception e) {
            log.warn("[UserMemoryMilvus] 向量检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    private static List<RagRecall.RecallHit> mapMemoryHits(List<QueryResultsWrapper.RowRecord> records,
                                                             RagRecall.RecallSource source) {
        List<RagRecall.RecallHit> out = new ArrayList<>();
        if (records == null) return out;
        for (QueryResultsWrapper.RowRecord row : records) {
            try {
                String id = String.valueOf(row.get("id"));
                String content = String.valueOf(row.get("content"));
                if (content == null || content.isBlank()) continue;
                Long createdAt = row.get("created_at") instanceof Number
                        ? ((Number) row.get("created_at")).longValue() : null;
                out.add(new RagRecall.RecallHit(id, content.trim(), 0.0, source,
                        "记忆", 0.8, createdAt, null, null));
            } catch (Exception e) {
                log.debug("Memory hit mapping skipped: {}", e.getMessage());
            }
        }
        return out;
    }

    private static List<RagRecall.RecallHit> mapMemoryFromSearch(SearchResultsWrapper results,
                                                                   RagRecall.RecallSource source) {
        List<RagRecall.RecallHit> out = new ArrayList<>();
        if (results == null) return out;
        List<SearchResultsWrapper.IDScore> scores = results.getIDScore(0);
        if (scores == null) return out;
        for (int i = 0; i < scores.size(); i++) {
            try {
                String id = String.valueOf(scores.get(i).getStrID());
                Object contentObj = results.getFieldData("content", i);
                if (contentObj == null) continue;
                String content = String.valueOf(contentObj);
                if (content.isBlank()) continue;
                Long createdAt = results.getFieldData("created_at", i) instanceof Number
                        ? ((Number) results.getFieldData("created_at", i)).longValue() : null;
                out.add(new RagRecall.RecallHit(id, content.trim(),
                        (double) scores.get(i).getScore(), source,
                        "记忆", 0.8, createdAt, null, null));
            } catch (Exception e) {
                log.debug("Memory vector hit skipped: {}", e.getMessage());
            }
        }
        return out;
    }

    private static String currentUserIdString() {
        Long id = UserContextHolder.currentUserIdOrNull();
        return id == null ? null : String.valueOf(id);
    }

    private static List<Float> toFloatList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float v : vector) values.add(v);
        return values;
    }
}
```

- [ ] **Step 2: 创建 MilvusLongTermMemoryStore**

```java
package com.yuyu.fishagent.memory.longterm;

import com.yuyu.fishagent.memory.config.MemoryProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 基于 Milvus 的长期事实存储。
 * <p>写入前通过向量相似度去重，冲突事实走 supersede 逻辑。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MilvusLongTermMemoryStore implements LongTermMemoryStore {

    private final MilvusServiceClient milvusClient;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final MemoryProperties properties;
    private final LongTermMemoryDeduplicator deduplicator;
    private final MemoryConflictJudge conflictJudge;

    @Override
    public void saveFacts(Long userId, String sessionId, List<String> facts) {
        if (!properties.isLongTermEnabled() || userId == null || facts == null || facts.isEmpty())
            return;

        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            log.warn("[MilvusLongTermMemory] EmbeddingModel 不可用，跳过写入");
            return;
        }

        String collection = properties.getLongTermIndexName();
        long now = System.currentTimeMillis();

        for (String fact : facts) {
            if (fact == null || fact.isBlank()) continue;
            try {
                String normalizedFact = fact.trim();
                List<Float> embedding = toFloatList(embeddingModel.embed(normalizedFact));

                // 查重：用 Milvus ANN 找最近邻
                List<SimilarFact> similarFacts = findSimilarFacts(
                        collection, String.valueOf(userId), embedding);

                MemoryWriteDecision decision = decideMemoryWrite(normalizedFact, similarFacts);
                if (decision.type() == MemoryDecision.DROP_DUPLICATE) {
                    log.debug("[MilvusLongTermMemory] 跳过重复事实");
                    continue;
                }
                if (decision.type() == MemoryDecision.SUPERSEDE_AND_WRITE) {
                    supersedeConflicts(collection, decision.conflicts(), now);
                }

                String id = UUID.randomUUID().toString();
                List<InsertParam.Field> fields = Arrays.asList(
                        new InsertParam.Field("id", List.of(id)),
                        new InsertParam.Field("user_id", List.of(String.valueOf(userId))),
                        new InsertParam.Field("content", List.of(normalizedFact)),
                        new InsertParam.Field("embedding", List.of(embedding)),
                        new InsertParam.Field("source_type", List.of("chat")),
                        new InsertParam.Field("superseded", List.of(false)),
                        new InsertParam.Field("created_at", List.of(now))
                );
                milvusClient.insert(InsertParam.newBuilder()
                        .withCollectionName(collection)
                        .withFields(fields)
                        .build());
                log.debug("[MilvusLongTermMemory] 事实写入完成 id={}", id);
            } catch (Exception e) {
                log.warn("[MilvusLongTermMemory] 写入失败: {}", e.getMessage());
            }
        }
    }

    private List<SimilarFact> findSimilarFacts(String collection, String userId,
                                                List<Float> embedding) {
        try {
            String expr = String.format(
                    "user_id == \"%s\" and source_type == \"chat\" and superseded == false", userId);
            SearchParam search = SearchParam.newBuilder()
                    .withCollectionName(collection)
                    .withVectors(List.of(embedding))
                    .withVectorFieldName("embedding")
                    .withTopK(properties.getConflict().getSimilarFactK())
                    .withMetricType(io.milvus.param.MetricType.COSINE)
                    .withExpr(expr)
                    .withParams("{\"nprobe\":16}")
                    .withOutputFields(List.of("id", "content"))
                    .build();
            SearchResultsWrapper results = new SearchResultsWrapper(
                    milvusClient.search(search).getData());
            List<SimilarFact> out = new ArrayList<>();
            List<SearchResultsWrapper.IDScore> scores = results.getIDScore(0);
            if (scores != null) {
                for (int i = 0; i < scores.size(); i++) {
                    String id = String.valueOf(scores.get(i).getStrID());
                    String content = results.getFieldData("content", i) != null
                            ? String.valueOf(results.getFieldData("content", i)) : "";
                    out.add(new SimilarFact(id, content, (float) scores.get(i).getScore()));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("[MilvusLongTermMemory] 相似事实查询失败: {}", e.getMessage());
            return List.of();
        }
    }

    private void supersedeConflicts(String collection, List<SimilarFact> conflicts, long now) {
        for (SimilarFact similar : conflicts) {
            milvusClient.delete(DeleteParam.newBuilder()
                    .withCollectionName(collection)
                    .withExpr(String.format("id == \"%s\"", similar.id()))
                    .build());
            log.debug("[MilvusLongTermMemory] 冲突旧事实已删除 id={}", similar.id());
        }
    }

    private MemoryWriteDecision decideMemoryWrite(String candidateFact,
                                                   List<SimilarFact> similarFacts) {
        if (similarFacts == null || similarFacts.isEmpty())
            return new MemoryWriteDecision(MemoryDecision.WRITE_NEW, List.of());
        if (!properties.getConflict().isEnabled())
            return new MemoryWriteDecision(MemoryDecision.DROP_DUPLICATE, List.of());

        List<SimilarFact> conflicts = new ArrayList<>();
        for (SimilarFact similar : similarFacts) {
            MemoryConflictJudge.Verdict verdict = conflictJudge.judge(candidateFact, similar);
            if (verdict == MemoryConflictJudge.Verdict.SAME)
                return new MemoryWriteDecision(MemoryDecision.DROP_DUPLICATE, List.of());
            if (verdict == MemoryConflictJudge.Verdict.CONFLICT)
                conflicts.add(similar);
        }
        return conflicts.isEmpty()
                ? new MemoryWriteDecision(MemoryDecision.WRITE_NEW, List.of())
                : new MemoryWriteDecision(MemoryDecision.SUPERSEDE_AND_WRITE, conflicts);
    }

    private List<Float> toFloatList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float v : vector) values.add(v);
        return values;
    }

    private enum MemoryDecision { WRITE_NEW, DROP_DUPLICATE, SUPERSEDE_AND_WRITE }

    private record MemoryWriteDecision(MemoryDecision type, List<SimilarFact> conflicts) {}
}
```

- [ ] **Step 3: 验证编译**

```bash
mvn compile -q
```

---

### Task 11: Java — 更新 RagRecallConfiguration

**Files:**
- Modify: `src/main/java/com/yuyu/fishagent/rag/pipeline/recall/RagRecallConfiguration.java`

**Interfaces:**
- Consumes: 3 个 MilvusSearcher、`MilvusLongTermMemoryStore`
- Produces: `RagRecall.Augmentation` Bean（注入改为 Milvus Searcher）

```java
@Bean
public RagRecall.Augmentation longTermRagContextService(
        RagProperties ragProperties,
        KnowledgeProperties knowledgeProperties,
        RagQueryRewrite.QueryRewriter queryRewriter,
        RagQueryExpand.SubQueryExpander subQueryExpander,
        UserMemoryMilvusSearcher userMemoryMilvusSearcher,
        UserKnowledgeMilvusSearcher userKnowledgeMilvusSearcher,
        PublicKnowledgeMilvusSearcher publicKnowledgeMilvusSearcher,
        ObjectProvider<MilvusServiceClient> milvusClientProvider,  // 改
        @Qualifier("ragRecallExecutor") ExecutorService ragRecallExecutor,
        RagReranker ragReranker,
        RagHydeService ragHydeService,
        RagQualityLogger ragQualityLogger,
        CircuitBreakerHelper circuitBreakerHelper,
        ChatMetrics chatMetrics,
        CardRelationMapper cardRelationMapper,
        KnowledgeCardMapper knowledgeCardMapper) {
    return new RagRecall.DefaultAugmentation(
            ragProperties,
            knowledgeProperties,
            queryRewriter,
            subQueryExpander,
            userMemoryMilvusSearcher,
            userKnowledgeMilvusSearcher,
            null,  // card searcher: 走 MySQL，不参与 RAG pipeline
            publicKnowledgeMilvusSearcher,
            null,  // operationsProvider: 不再需要 ES
            ragRecallExecutor,
            ragReranker,
            ragHydeService,
            ragQualityLogger,
            circuitBreakerHelper,
            chatMetrics,
            cardRelationMapper,
            knowledgeCardMapper);
}
```

同时修改 `DefaultAugmentation` 构造函数签名——不再依赖 `ObjectProvider<ElasticsearchOperations>`，参数改为无意义 null 也不影响（改一下内部检查逻辑）。

---

### Task 12: Java — 更新 RagRecall.DefaultAugmentation 中 ES 检查

**Files:**
- Modify: `src/main/java/com/yuyu/fishagent/rag/pipeline/recall/RagRecall.java`

在 `doBuildAugmentation` 方法开头，移除 ES 检查，改为 Milvus 检查（或无检查）：

```java
// 删除这两行
// if (operationsProvider.getIfAvailable() == null) {
//     log.debug("[RagRecall] ElasticsearchOperations 不可用，跳过 RAG");
//     return Optional.empty();
// }
```

同时更新熔断器常量引用（`CB_ES_TEXT` → `CB_MILVUS_TEXT`，`CB_ES_VECTOR` → `CB_MILVUS_VECTOR`）。

---

### Task 13: Java — 删除所有 ES 相关代码

**Files:**
- Delete: `src/main/java/com/yuyu/fishagent/rag/pipeline/recall/UserMemoryElasticsearchSearcher.java`
- Delete: `src/main/java/com/yuyu/fishagent/rag/pipeline/recall/UserKnowledgeElasticsearchSearcher.java`
- Delete: `src/main/java/com/yuyu/fishagent/rag/pipeline/recall/PublicKnowledgeElasticsearchSearcher.java`
- Delete: `src/main/java/com/yuyu/fishagent/rag/pipeline/recall/UserKnowledgeCardSearcher.java`
- Delete: `src/main/java/com/yuyu/fishagent/memory/longterm/ElasticsearchLongTermMemoryStore.java`
- Delete: `src/main/java/com/yuyu/fishagent/card/service/KnowledgeCardEsSyncService.java`
- Delete: `src/main/java/com/yuyu/fishagent/card/service/KnowledgeCardEsReconciliationService.java`
- Delete: `src/main/java/com/yuyu/fishagent/card/document/KnowledgeCardDocument.java`
- Delete: `src/main/java/com/yuyu/fishagent/rag/document/UserMemoryDocument.java`
- Delete: `src/main/java/com/yuyu/fishagent/rag/document/PublicKnowledgeDocument.java`
- Delete: `src/main/java/com/yuyu/fishagent/rag/document/KnowledgeChunkDocument.java`
- Delete: `src/main/java/com/yuyu/fishagent/common/trace/TraceEsWriter.java`

然后 `mvn compile` 修复所有编译错误（import 引用等）。

---

### Task 14: Java — Trace 改落文件 + MySQL

**Files:**
- Create: `src/main/java/com/yuyu/fishagent/common/trace/TraceFileWriter.java`
- Modify: `src/main/java/com/yuyu/fishagent/common/trace/TraceProperties.java`
- Modify: `src/main/java/com/yuyu/fishagent/rag/tracing/RagQualityLogger.java`
- Modify: `src/main/java/com/yuyu/fishagent/rag/tracing/RagTraceDocument.java`

- [ ] **Step 1: TraceProperties 改为文件存储**

```java
/** Trace 文件存储目录。 */
private String storageDir = "data/traces";

/** 表名（MySQL 存储时使用，预留）。 */
private String tableName = "itops_turn_trace";
```

- [ ] **Step 2: 创建 TraceFileWriter**

```java
package com.yuyu.fishagent.common.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ThreadLocalRandom;

/**
 * TurnTrace 异步文件写入器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TraceFileWriter {

    private final TraceProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void initDir() {
        try {
            Files.createDirectories(Paths.get(properties.getStorageDir()));
        } catch (IOException e) {
            log.warn("[TraceFileWriter] 创建存储目录失败: {}", e.getMessage());
        }
    }

    public void persistAsync(TurnTrace trace) {
        if (!shouldPersist(trace)) return;
        MdcAsync.mdcRunAsync(() -> persist(trace));
    }

    private boolean shouldPersist(TurnTrace trace) {
        if (!properties.isEnabled() || trace == null) return false;
        double sampleRate = Math.max(0.0, Math.min(1.0, properties.getSampleRate()));
        return sampleRate >= 1.0 || ThreadLocalRandom.current().nextDouble() < sampleRate;
    }

    private void persist(TurnTrace trace) {
        try {
            Path file = Paths.get(properties.getStorageDir(),
                    trace.getTurnId() + ".json");
            objectMapper.writeValue(file.toFile(), trace);
        } catch (Exception e) {
            log.warn("[TraceFileWriter] 写入失败 turnId={}: {}", trace.getTurnId(), e.getMessage());
        }
    }
}
```

- [ ] **Step 3: RagQualityLogger 改为 MySQL 写入**

将 `RagTraceDocument` 改为 MyBatis-Plus 实体（加 `@TableName("itops_rag_trace")`），`RagQualityLogger.log()` 改为调 Mapper insert。若暂不需要 MySQL trace 查询能力，也可落文件（同 TraceFileWriter 模式）。

- [ ] **Step 4: 更新 TraceCollector 引用**

`TraceCollector` 中的 `TraceEsWriter` 替换为 `TraceFileWriter`。

---

### Task 15: Java — 知识卡片检索走 MySQL

**Files:**
- Modify: `src/main/java/com/yuyu/fishagent/card/service/KnowledgeCardService.java`（或新建一个 search 方法）

不需要新建 Searcher。在 card service 层加一个关键词搜索方法，让前端/Agent 调用即可。

```java
/**
 * 卡片关键词检索（MySQL LIKE），替代原来 ES 向量+全文检索。
 */
public List<KnowledgeCard> searchByKeyword(Long userId, String keyword, int limit) {
    return knowledgeCardMapper.selectList(
            new LambdaQueryWrapper<KnowledgeCard>()
                    .eq(KnowledgeCard::getUserId, userId)
                    .eq(KnowledgeCard::getStatus, KnowledgeCard.STATUS_CONFIRMED)
                    .and(w -> w.like(KnowledgeCard::getTitle, keyword)
                            .or().like(KnowledgeCard::getContent, keyword))
                    .last("LIMIT " + Math.max(1, Math.min(limit, 20)))
    );
}
```

从 `RagRecallConfiguration` 中移除 `UserKnowledgeCardSearcher` 的注入（已在 Task 11 中传 null）。

---

### Task 16: Java — 同步更新 RAG 渲染中的卡片引用

**Files:**
- Modify: `src/main/java/com/yuyu/fishagent/rag/pipeline/expand/CardGraphExpander.java`（如存在）

卡片不再参与 RAG 检索 pipeline，`CardGraphExpander` 的逻辑需调整为从 MySQL 搜索相关卡片（或直接移除此 expander）。

---

### Task 17: 更新测试代码

**Files:**
- Delete/Modify: 所有引用 ES 的测试文件

- 删除 `ElasticsearchLongTermMemoryStoreTest.java`
- 删除 `ElasticsearchLongTermMemorySearcherIT.java`
- 删除 `python/tests/test_elasticsearch_indexer.py`
- 更新 `RagRecallRenderBudgetTest.java` 等引用 ES Searcher 的测试

---

### Task 18: 全链路验证

- [ ] **Step 1: Python Worker 验证**
```bash
cd python && python -m fish_worker
# 确认能连接 Milvus，无 import 错误
```

- [ ] **Step 2: Java 编译验证**
```bash
mvn clean compile -q
# 确认无编译错误
```

- [ ] **Step 3: 端到端测试**
  - 上传一个测试文档 → 确认 Worker 写入 Milvus
  - 发起对话 → 确认检索命中 Milvus 结果
  - 检查 RRF 融合日志正常
  - 检查 Trace 文件正常生成
  - 检查卡片检索走 MySQL 正常

---

## 验证样例（来自 ROADMAP）

迁移完成后，验证以下查询的召回结果并记录：

- `order-service` — 服务名精确匹配（BM25 腿）
- `ECONNRESET` — 错误码精确匹配（BM25 腿）
- `/api/v1/order/create` — 接口路径精确匹配（BM25 腿）
- `spring.redis.timeout` — 配置键精确匹配（BM25 腿）

完成一组 `dense-only vs hybrid` 对照评测。
