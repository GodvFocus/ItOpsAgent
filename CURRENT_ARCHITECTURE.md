# CURRENT_ARCHITECTURE

## 文档目的

本文只描述仓库当前已经落地、能在代码中找到对应实现的架构现状，不写目标态，不写规划态，不把“准备做”写成“已经有”。

最后核对时间：2026-07-21。

---

## 一、当前系统定位

当前仓库实现的是一个通用型 AI Agent 应用，已经包含：

- 登录鉴权与会话管理
- SSE 流式对话
- ReAct Agent 与通用工具调用
- RAG 检索增强
- 短期/长期记忆
- 知识库文档入库与管理
- 知识卡片提取、确认、关系发现、复习
- Trace、限流、熔断、评测等工程化能力

它还没有收敛为“面向研发/SRE 的排障辅助 Agent”这一单一场景。当前工具集、检索模型、权限模型和回答结构，仍然是更通用的 Agent 形态。

---

## 二、当前技术栈

### 1. 后端

- Java 21
- Spring Boot 3.5.13
- Spring AI 1.1.4
- Spring AI Alibaba Agent Framework 1.1.2.0
- MyBatis-Plus
- Redis
- Elasticsearch
- Resilience4j
- MinIO / RustFS S3 兼容对象存储

### 2. Python Worker

- Python Worker 独立进程
- Redis Stream 消费
- MinIO 下载原文
- 文档解析、切分、Embedding、Elasticsearch 写入

### 3. 前端

- Vue 3 + TypeScript + Vite
- Element Plus
- Pinia
- SSE 流式渲染
- vis-network 知识关系图

---

## 三、当前模块划分

### 1. `auth`

当前已实现：

- `AuthController`：注册、登录、登出、`/me`
- `AuthService`：用户注册与 BCrypt 密码校验
- `RedisSessionManager`：基于 Redis 的会话存储
- `GlobalAuthInterceptor`：从 `X-Auth-Token` 或请求参数解析登录态
- `PermissionInterceptor`：管理员接口保护
- `RateLimitInterceptor`：聊天接口限流入口

当前会话模型是：

- UUID Token
- Redis Session
- `UserContextHolder` 线程上下文注入

当前没有：

- JWT
- workspace 级权限模型
- tenant/team/role 级 RBAC

### 2. `chat`

当前已实现：

- `POST /api/chat/stream`：SSE 流式对话
- 会话列表、历史读取、删除、重命名
- `ChatService` 负责主链路编排
- `ChatMetadataService` 管理会话元数据
- 文件历史存储 `UserScopedFileChatMemoryStore`
- 上下文预算分配与裁剪

当前对话主链路是：

```text
登录态校验
-> Redis 令牌桶 + SSE 并发槽位 + 会话互斥锁
-> TraceFilter 注入 traceId
-> ChatService 组装系统上下文
-> ChatAgent 执行 ReAct
-> SSE 推送 token 与事件
-> 持久化会话与 Trace
```

### 3. `agent`

当前已实现：

- `BaseAgent` / `ChatAgent`
- Spring AI Alibaba ReAct 编排
- `ToolRegistry` SPI 工具注册
- `AgentStatus` 运行状态机
- `ModelCallLimitHook` + 递归上限防死循环
- 工具结果预算、摘要、scratch 检索式回注

当前代码中的工具提供器共 11 个：

- 内置工具 6 个：`calculator`、`datetime`、`file_read`、`file_write`、`web_fetch`、`search_large_result`
- 外部工具 5 个：`tavily_search`、`bocha_search`、`amap_geo`、`amap_weather`、`mail`

当前没有：

- 面向运维排障的专用工具，如 `knowledge_search_tool`、`service_status_tool`、`log_search_tool`
- 工具级工作空间权限模型
- 证据 ID 驱动的回答约束

### 4. `rag`

当前 RAG 主链路仍然基于 Elasticsearch，而不是 Milvus。

当前已实现：

- 查询改写 `RagQueryRewrite`
- 查询扩展 `RagQueryExpand`
- 可选 HyDE
- 四索引双路召回
- RRF 融合 `RagScoreFusion`
- DashScope Rerank
- 邻居扩展、上下文扩展、来源权威加权
- RAG 追踪 `RagQualityLogger` / `fish-rag-trace`

当前四个 ES 索引分别是：

- `fish-user-memory`：长期对话事实
- `fish-user-knowledge`：用户私有文档切片
- `fish-public-knowledge`：公共知识切片
- `fish-knowledge-card`：知识卡片检索索引

当前召回特征：

- 文本召回：`match`
- 向量召回：`dense_vector` + `knn`
- 用户隔离：私有索引统一按 `user_id` 过滤
- 公共知识：`fish-public-knowledge` 不带 `user_id` 过滤

当前没有：

- Milvus
- BM25/full-text 与 dense 分离的双引擎设计
- workspace 过滤
- Query Router 区分“简单 RAG”和“复杂 Agent”

### 5. `memory`

当前已实现：

- 三层短期记忆
  - L1：Redis
  - L2：RustFS 或文件快照
  - L3：完整会话历史
- 长期事实抽取与入库
- 长期事实去重、冲突判断
- 记忆压缩
- Agent 状态记录

当前长期记忆仍然写入 Elasticsearch `fish-user-memory`。

### 6. `card`

当前已实现：

- 会话抽取知识卡片
- 卡片创建、更新、删除
- 待确认、批量确认、批量拒绝
- 卡片合并
- 关系创建、删除、自动发现
- 分组迁移、关键词迁移
- 复习队列与统计
- ES 同步与定时对账

当前知识卡片是仓库中已经落地的一条完整业务线，不是规划项。

### 7. `common`

当前已实现的公共基础设施包括：

- Redis Cache
- Redis Lua 限流
- TraceFilter / MDC 传播
- `TraceCollector` / `TraceEsWriter`
- Resilience4j Circuit Breaker
- 全局异常处理
- WebMvc 配置与调度配置

---

## 四、当前数据与基础设施

### 1. MySQL

当前 MySQL 已落地的核心表包括：

- `sys_user`
- `chat_metadata`
- `document_metadata`
- `knowledge_card`
- `card_relation`
- `card_group`
- `card_keyword`
- `keyword`
- `keyword_relation`
- `card_review_record`

当前文档元数据表 `document_metadata` 采用：

- `user_id`
- `scope_type = PRIVATE | PUBLIC`

当前没有：

- `workspace_id`
- `owner_user_id + visibility`
- 文档版本号

### 2. Redis

当前 Redis 用于：

- Session
- 聊天限流
- SSE 并发计数
- 会话互斥锁
- 短期记忆
- Agent 状态
- 文档入库 Stream
- 部分缓存

### 3. Elasticsearch

当前 Elasticsearch 承担：

- 长期记忆
- 用户知识库
- 公共知识库
- 知识卡片检索
- RAG Trace
- Turn Trace 持久化

这意味着当前检索与可观测主链路都仍然依赖 ES。

### 4. RustFS / MinIO

当前对象存储用于：

- 会话 JSON
- 短期记忆快照
- 知识库原始文档
- 分片上传合并后的对象

---

## 五、当前知识库入库链路

当前链路已经落地，且包含普通上传与分片上传两条入口：

```text
前端上传文档
-> KnowledgeController
-> KnowledgeIngestionService
-> RustFS/MinIO 保存原文
-> MySQL 写 document_metadata
-> Redis Stream XADD fish:doc:ingest
-> Python Worker XREADGROUP 消费
-> 解析、清洗、切分、Embedding
-> Elasticsearch 写入 fish-user-knowledge 或 fish-public-knowledge
-> MySQL 更新任务状态
```

当前 Worker 已支持：

- PDF
- TXT / MD
- DOCX
- HTML
- XLSX
- PPTX

当前可靠性机制包括：

- 消费者组
- `XREADGROUP`
- `XAUTOCLAIM`
- `XACK`
- Worker 侧幂等删除 `delete_by_doc_id`
- Java 侧 `OrphanTaskCompensationService` 清理长时间卡住的任务

当前没有：

- MySQL Outbox
- “MySQL 已写成功但还未 XADD 就崩溃”的显式投递补偿标记
- 基于 `doc_version` 的版本化入库

---

## 六、当前聊天与检索链路

当前对话主链路大致如下：

```text
用户请求
-> 鉴权
-> RateLimitInterceptor
-> ChatController
-> ChatService
-> 短期记忆加载
-> 长期记忆 / 文档 / 卡片 / 公共知识四索引 ES 检索
-> RRF 融合 + rerank + 注入
-> ChatAgent ReAct
-> 工具调用
-> SSE 输出
-> Trace 持久化
```

当前系统并没有在入口处先判断：

- 这个问题是否只是 SOP 问答
- 是否应该绕过 Agent 直接返回 RAG 结果

也就是说，当前主聊天能力仍然是“统一 Agent + RAG 增强”的架构，而不是“简单问题走 RAG，复杂问题走排障 Agent”的路由式架构。

---

## 七、当前前端实现

当前前端已实现的页面包括：

- 登录页 `LoginView.vue`
- 对话页 `ChatView.vue`
- 知识库管理页 `KnowledgeView.vue`
- 知识卡片页 `KnowledgeCardView.vue`

当前前端已接入的后端接口包括：

- `/api/auth/**`
- `/api/chat/**`
- `/api/knowledge/**`
- `/api/card/**`
- `/api/card/review/**`

当前前端能力包括：

- SSE 聊天流式展示
- 会话列表与历史
- 文档上传与任务轮询
- 文档切片查看
- 关联知识卡片查看
- 知识卡片提取、编辑、确认、复习
- 卡片关系图可视化

---

## 八、当前可观测性与测试

### 1. 可观测性

当前已实现：

- `traceId` 全链路传播
- `fish-trace` 对话 Trace ES 持久化
- `fish-rag-trace` RAG 质量追踪
- Actuator `health/info/prometheus`
- Resilience4j 指标接入

### 2. 测试与评测

当前仓库已经有真实测试资产，不应写成“未来再做”：

- Java 单元测试与集成测试
- Python Worker 的解析、切分、ES 写入、CAS 状态测试
- `src/test/resources/eval/golden-rag.json`
- `src/test/resources/eval/golden-summary.json`
- `EvalRunner` / `SummaryEvalRunner`

但这些评测仍然围绕现有通用 RAG/记忆/摘要能力，不是面向排障场景设计的最小评测集。

---

## 九、当前明确未实现的内容

以下能力在仓库当前代码中没有落地，不应写入现状文档：

- Milvus 检索主链路
- BGE-M3 dense + Milvus 全文/BM25 混合检索
- `workspace_id + visibility` 权限模型
- 面向排障的 `knowledge_search_tool`
- `service_status_tool`
- `log_search_tool`
- `ticket_query_tool`
- Query Router
- Evidence ID 结构化回答
- 面向排障场景的固定输出 schema
- 自动或半自动工单草稿、通知草稿闭环

---

## 十、现状结论

当前仓库的真实实现可以概括为：

> 一个已经具备登录、流式对话、ReAct、ES 检索、记忆、知识库入库、知识卡片、Trace 与评测支撑的通用型 Agent 系统。

它的优势是工程面已经比较完整；它的现状问题是：

- 技术栈仍然是 ES 主导，不是目标中的 Milvus 路线；
- 权限模型仍然是 `user_id + PUBLIC/PRIVATE`；
- 工具集仍然偏通用，而不是排障专用；
- 对话入口还没有收敛为“排障问题路由”。

这正是 `TARGET_ARCHITECTURE.md` 和 `ROADMAP.md` 需要解决的差距。
