# ES → Milvus + BGE-M3 迁移设计

## 元信息

- 日期：2026-07-22
- 范围：ROADMAP P0 第 1 项
- 结论：ES 全部移除，知识检索迁 Milvus，卡片检索迁 MySQL，Trace 迁 MySQL/文件，索引命名 `fish-*` → `itops-*`

---

## 一、数据存储分工

| 数据类型 | 当前（ES） | 目标 |
|---------|-----------|------|
| 用户文档切片 | `fish-user-knowledge` | Milvus `itops_user_knowledge` |
| 公共知识切片 | `fish-public-knowledge` | Milvus `itops_public_knowledge` |
| 对话长期记忆 | `fish-user-memory` | Milvus `itops_user_memory` |
| 知识卡片检索 | `fish-knowledge-card` | MySQL 关键词查询，删除 ES 同步层 |
| RAG Trace | `fish-rag-trace` | MySQL + 文件 |
| Turn Trace | `fish-trace` | MySQL + 文件 |

**三个 Milvus Collection 字段不同、过滤条件不同、写入路径不同，独立管理。**

---

## 二、Java 检索层改动

### 不改的部分

- `RagRecall.DocumentSearcher` 接口（`searchByText` / `searchByVector`）
- `RagRecall.DefaultAugmentation` 编排（查询改写 → 子查询扩展 → 多路并发检索 → RRF 融合 → rerank → 渲染）
- `RagScoreFusion` RRF 融合逻辑

### Searcher 替换

| 当前 | 目标 |
|-----|------|
| `UserMemoryElasticsearchSearcher` | `UserMemoryMilvusSearcher` |
| `UserKnowledgeElasticsearchSearcher` | `UserKnowledgeMilvusSearcher` |
| `PublicKnowledgeElasticsearchSearcher` | `PublicKnowledgeMilvusSearcher` |
| `UserKnowledgeCardSearcher` | 删除，卡片走 MySQL 关键词查询 |

每个 Milvus Searcher：
- `searchByText()` → Milvus BM25 全文检索（content 字段启用 analyzer）
- `searchByVector()` → Ollama BGE-M3 embedding（1024 维）→ Milvus ANN 向量检索

### 配置装配

`RagRecallConfiguration` 注入改为 Milvus Searcher，去掉 `ElasticsearchOperations` 依赖。

`DefaultAugmentation` 中 ES 可用性检查改为 Milvus client 检查。

---

## 三、Python Worker 改动

### Embedding 层

当前 `Embedder` 已支持 Ollama，只需改配置：

```
FISH_LLM_EMBEDDING_PROVIDER=OLLAMA
OLLAMA_EMBEDDING_MODEL=bge-m3:latest
OLLAMA_BASE_URL=<现有 Ollama 地址>
DASHSCOPE_EMBEDDING_DIMENSIONS=1024
```

代码不动。

### 入库层

新建 `storage/milvus.py`（`MilvusIndexer`），替换 `storage/elasticsearch.py`（删除）。

核心方法：

| ES | Milvus |
|----|--------|
| `delete_by_doc_id` → `delete_by_query` | `milvus.delete(expr='doc_id=="xxx"')` |
| `bulk_index` → 分批 `bulk` | `milvus.insert()` 批量写入 |
| `mark_doc_ready` → `refresh` + `update_by_query` | `milvus.upsert()` 翻转 ready 字段（Milvus insert 立即可见，无需 refresh） |

### 配置层

`config.py` 改动：

- 删除：`elasticsearch_uris`、`es_hosts`、ES 索引名、`fish_worker_es_batch_size`、`fish_worker_mark_ready_*`
- 新增：`milvus_uri`（默认 `milvus-standalone:19530`）、Collection 名配置

### Processor

`processor.py` 步骤 6 "写入 ES" 改为 "写入 Milvus"，其余步骤不变。

---

## 四、删除清单

### Java

- 4 个 `*ElasticsearchSearcher`
- `ElasticsearchLongTermMemoryStore`
- `KnowledgeCardEsSyncService` / `KnowledgeCardEsReconciliationService`
- `TraceEsWriter`
- ES 相关 Document 类（`UserMemoryDocument`、`PublicKnowledgeDocument`、`KnowledgeCardDocument`）
- ES 相关 properties 配置
- `pom.xml` 中 `spring-boot-starter-data-elasticsearch` 依赖

### Python

- `storage/elasticsearch.py`
- `config.py` 中 ES 配置项

### 新增

- `pom.xml`：`io.milvus:milvus-sdk-java`
- Java：3 个 `*MilvusSearcher` + `MilvusLongTermMemoryStore`
- Python：`storage/milvus.py`
- MySQL：trace 存储表
- MySQL：卡片关键词检索（利用已有 `knowledge_card` 表的 title/content/keywords 字段）

---

## 五、检索 Pipeline 目标态

```text
用户输入
  → QueryRewrite（可选）
  → SubQueryExpand
  → 并发召回（虚拟线程）：

    【文本腿】
    UserMemoryMilvusSearcher.searchByText()     → Milvus BM25 on itops_user_memory
    UserKnowledgeMilvusSearcher.searchByText()  → Milvus BM25 on itops_user_knowledge
    PublicKnowledgeMilvusSearcher.searchByText()→ Milvus BM25 on itops_public_knowledge

    【向量腿】
    UserMemoryMilvusSearcher.searchByVector()     → BGE-M3 embed → Milvus ANN
    UserKnowledgeMilvusSearcher.searchByVector()  → BGE-M3 embed → Milvus ANN
    PublicKnowledgeMilvusSearcher.searchByVector()→ BGE-M3 embed → Milvus ANN

  → RRF 融合（RagScoreFusion，不改）
  → DashScope Rerank（保留）
  → Provenance Boost + Context Expand
  → 渲染注入
```

---

## 六、不变的部分

- MySQL 文档元数据表 `document_metadata`
- Redis Stream 任务投递（`fish:doc:ingest`）
- MinIO/RustFS 文档原文存储
- RAG pipeline 编排逻辑
- Reranker
- SSE 聊天流式输出
- 前端（检索 API 契约不变）
- 鉴权 / Session / 限流
