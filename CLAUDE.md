# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 常用命令

```bash
# 构建（跳过测试）
mvn clean package -DskipTests

# 运行全部测试
mvn test

# 运行单个测试类
mvn test -Dtest=ChatServiceObservabilityTest

# 启动应用（dev profile）
mvn spring-boot:run -Dspring-boot.active=dev

# 前端开发
cd frontend && pnpm install && pnpm dev    # http://127.0.0.1:5173，反代到 localhost:8080

# Python Worker（文档入库消费者）
cd python && pip install -e . && python -m fish_worker
```

## 技术栈

- **后端**：Java 21, Spring Boot 3.5.13, Spring AI 1.1.4, Spring AI Alibaba Agent Framework 1.1.2.0, MyBatis-Plus 3.5.9
- **LLM**：DashScope（通义千问）、Ollama（本地）、DeepSeek（OpenAI 兼容接口），通过 `fish.llm.chat-provider` 切换
- **存储**：MySQL（关系数据）、Redis（会话/缓存/限流）、Milvus + BGE-M3（向量检索底座，替代原 ES）、MinIO（S3 对象存储）
- **可观测**：Micrometer → Prometheus (`/actuator/prometheus` 在 9092 端口), Resilience4j 熔断
- **前端**：Vue 3 + TypeScript + Vite + Element Plus + Pinia
- **Python Worker**：独立进程，Redis Stream 消费文档入库任务

## 架构概述

项目是通用型 AI Agent 应用（正收敛为研发/SRE 排障辅助 Agent），单 Maven 模块，包结构 `com.yuyu.fishagent`：

### 核心模块

| 包 | 职责 |
|---|---|
| `agent` | ReAct Agent 编排。`BaseAgent`/`ChatAgent` 封装 Spring AI Alibaba ReAct 循环，`ToolRegistry` 管理 11 个内置/外部工具，含工具结果预算、摘要和 scratch 检索式回注 |
| `chat` | 对话核心。`ChatService` 是主链路编排器：登录校验 → 限流 → 上下文组装 → Agent 执行 → SSE 推送。包含上下文窗口 Token 预算管理（`ContextBudgetAllocator`） |
| `rag` | 多阶段 RAG 管线：查询改写 → 扩展（LLM 分解/HyDE）→ 四索引双路召回（文本 match + 向量 knn）→ RRF 融合 → DashScope Rerank → 邻居扩展/权威加权 |
| `memory` | 三层短期记忆（Redis L1 / RustFS L2 / 完整历史 L3）+ 长期事实的 LLM 抽取、去重、冲突判断、Milvus 存储 |
| `card` | 从对话中抽取知识卡片，支持创建/确认/拒绝/合并/关系发现/复习队列/ES 同步与定时对账 |
| `auth` | 基于 Redis 的会话管理，`X-Auth-Token` 头校验，BCrypt 密码哈希 |
| `common` | Redis Cache 抽象、令牌桶限流、Resilience4j 熔断、Trace 系统（ES 写入）、Prometheus 指标 |

## Docker Desktop 中间件（实际运行，非 docker-compose.yml）

应用启动需激活 dev profile，以下服务运行于 Docker Desktop：

| 服务 | 镜像 | 宿主机地址 | 用途 |
|------|------|-----------|------|
| MySQL | `mysql:8`（docker-compose） | `localhost:3306` root/123456 | 关系数据 |
| Redis | `redis:8.0`（docker-compose） | `localhost:6379` db=1 | 会话/缓存/限流 |
| Milvus | `milvusdb/milvus:v2.4.15` | `localhost:19530`（gRPC API） | 向量检索底座（替代 ES） |
| Milvus MinIO | `minio/minio:RELEASE.2023-03-20` | `localhost:9000-9001` | Milvus 内部存储（应用不应直接使用） |

**注意**：

- Elasticsearch 已不再运行，检索底座已迁移到 Milvus
- RustFS 服务不再使用，S3 存储改用 `minioai` 容器（配置在 `fish.rustfs.endpoint`，需要改端口则更新 `application-dev.yml`）
- 管理端口（Prometheus）dev 环境改为 `9092`（避免被 Docker Desktop 占用 9090）

### 对话主链路

```
登录校验 → Redis 令牌桶 + SSE 并发槽位 → TraceFilter 注入 traceId
→ ChatService 组装系统上下文（记忆/RAG/卡片） → ChatAgent ReAct 循环
→ SSE 流式推送 token/工具调用/done/error 事件 → 持久化会话与 Trace
```

### Python Worker

独立进程消费 Redis Stream `fish:doc:ingest`：MinIO 下载原文 → 文档解析/切分 → Embedding → ES 写入。入口：`python/fish_worker/main.py`。

## 关键配置注意点

- **Spring Boot 版本必须停留 3.5.x**：Spring AI Alibaba 1.1.2.x 不支持 Spring Boot 4.x（包路径迁移导致 ClassNotFound）
- **`spring.threads.virtual: true`**：启用 Java 21 虚拟线程
- **LLM 切换**：通过 `FISH_LLM_CHAT_PROVIDER` 环境变量（DASHSCOPE / OLLAMA / DEEPSEEK），`FishLlmEnvironmentPostProcessor` 自动补全 `spring.ai.model.chat`
- **敏感凭据**在 `application-dev.yml`（已 gitignore），模板参考该文件头注释
- **ES 自动配置排除**：`application.yml` 排除了 OpenAI Audio/Image/Embedding/Moderation 自动配置，DeepSeek 仅需 Chat

## 开发规范

### Git 配置

- **绝不随意变更 git config**。仓库已有的配置（`user.name`、`user.email`、换行符处理等）直接沿用，不要修改。
- 如果确实需要新增配置项（如新增 hook 路径、调整别名），先向老板说明原因，老板同意后再改。
- 不要运行 `git config --local` 命令来"修复"或"优化"现有配置。

### Git 提交

- **按功能块提交，拒绝碎碎念**。完成一个小功能点/修复点后统一提交，而不是改一个文件就 commit 一次。
- 一个 commit 应该是一个可独立理解、可独立回滚的逻辑单元。
