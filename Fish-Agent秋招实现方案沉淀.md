# Fish-Agent 实现方案沉淀（修订版）

## 修订说明

本版基于《实现方案审查意见》重写，目标不是继续堆技术点，而是把以下几件事讲清楚：

1. 当前已经实现了什么，目标要做到什么，哪些还在路线图里。
2. 为什么这个场景需要 Agent，哪些问题其实只需要 RAG。
3. Milvus + BGE-M3 的技术路线到底怎么落地，边界是什么。
4. 异步入库、权限隔离、安全治理、评测验收如何做到可解释、可实现、可答辩。

本文只保留一条主线：

> Fish-Agent 不是通用 AI 助手，而是面向研发/SRE 的运维知识库与排障辅助 Agent 系统。

---

## 一、当前状态、目标状态与路线图分离

为避免“已实现”和“计划实现”混写，本方案明确分成三层。

### 1. 当前真实状态

当前仓库已经具备或正在围绕以下能力建设：

- Java Spring Boot 作为主业务后端。
- MySQL 保存用户、文档、任务、会话等结构化数据。
- Redis/Redis Stream 承担会话状态、异步任务和并发控制。
- Python Worker 执行文档解析、切分、Embedding、向量入库。
- 前端已具备聊天、知识库、知识卡片等交互基础。

当前文档中所有“已经实现”的描述，后续都必须以代码、配置或可运行链路为准；未落地能力一律归入目标状态或路线图，不再混写。

### 2. 目标状态

目标系统聚焦一个场景：  
用户输入故障现象后，系统结合知识库、服务状态和日志证据，输出带引用的排查建议，并在人工确认后沉淀为可复用知识。

目标能力包括：

- 简单 SOP 问题走 Hybrid RAG 快速路径。
- 复杂故障问题走受限 Agent 路径。
- 文档异步入库、混合检索、证据组装、结构化回答、人工确认沉淀形成闭环。

### 3. 路线图管理方式

后续建议补齐三类伴随文档，用于降低简历和答辩风险：

- `CURRENT_ARCHITECTURE.md`：只写仓库当前真实实现。
- `TARGET_ARCHITECTURE.md`：只写目标架构。
- `ROADMAP.md`：只写待完成事项、优先级和验收条件。

关键技术选型另行沉淀到 `docs/adr/`，避免后期口径漂移。

---

## 二、项目定位

### 1. 一句话定位

Fish-Agent 面向研发、SRE、DevOps、技术支持和值班新人，提供面向内部排障场景的知识检索、证据汇总和只读式排查建议生成能力。

### 2. 不做什么

MVP 不做以下高风险能力：

- 自动重启服务
- 自动修改配置
- 自动清理缓存
- 自动执行生产变更
- 自动发送真实通知
- 自动创建真实工单

这些动作最多只生成草稿或建议，并显式要求人工确认。

### 3. 为什么不是通用 Agent

本项目不追求“什么都能做”，而是只解决一个可答辩的问题：

> 面对故障排查这类多步骤、证据分散、需要引用依据的任务，如何用 Hybrid RAG + 受限 Tool Calling 提升排查效率，同时保证权限、安全和可追溯。

---

## 三、业务场景与路由边界

### 1. 简单问题不进入 Agent

并非所有问题都需要 ReAct。

适合直接走 Hybrid RAG 的问题：

- “支付服务回滚流程是什么？”
- “某接口的限流配置在哪里？”
- “告警规则里 5xx 阈值是多少？”

这类问题的特点是：

- 主要依赖文档知识；
- 不需要多轮工具调用；
- 不需要实时状态联合判断。

### 2. 复杂问题进入受限 Agent

适合进入 Agent 的问题：

- “订单服务 P99 突然升高，同时 Redis 连接池耗尽，先查什么？”
- “某 traceId 关联到哪些异常，下一步应该排查哪一层？”
- “最近一次发布后网关 502 增多，是实例异常、上游超时还是配置变更导致？”

这类问题通常需要：

- 判断故障类型；
- 识别缺失信息；
- 检索 Runbook/复盘/接口文档；
- 查询服务状态；
- 查询日志；
- 汇总证据后给出排查步骤。

### 3. 查询路由设计

```text
用户请求
  -> Session/Auth 注入 userId、workspaceId
  -> Query Router
      -> 文档/SOP/规则问答：Hybrid RAG 快速路径
      -> 复杂故障排查：Bounded ReAct Agent 路径
```

这样可以同时证明两点：

- 理解 Agent 的适用边界；
- 不会为了“用了 Agent”而强行把所有问题都塞进 Agent。

---

## 四、MVP 范围

### 1. MVP 保留

MVP 只保留最能体现深度的能力：

1. Markdown/PDF 文本文档入库。
2. RustFS/MinIO 保存原文。
3. Redis Stream 异步入库。
4. Milvus 全文/BM25 + BGE-M3 dense 的混合检索。
5. `knowledge_search_tool`。
6. `service_status_tool`。
7. `log_search_tool`。
8. 受限 ReAct Agent。
9. SSE 基础流式反馈。
10. Evidence/Citation 证据绑定。
11. 轻量 `workspace + user` 权限模型。
12. Agent Trace。
13. 最小评测集。

### 2. MVP 末尾再做

- 历史工单结构化查询
- 知识卡片草稿确认
- Resilience4j 故障注入演示
- 文档版本更新与删除治理

### 3. 暂缓

- OCR（除非演示资料确有扫描件）
- 完整分层记忆体系
- Redis scratch 三层治理
- Rerank
- 知识图谱
- 卡片关系发现
- 多模型复杂路由
- 通知草稿
- 工单草稿
- 完整 tenant/team/role RBAC
- SSE 断线续传与事件回放

范围收缩原则很简单：  
先把“混合检索为什么有效、复杂问题为什么需要 Agent、异步链路如何可靠、结论如何绑定证据”做扎实，再谈扩展。

---

## 五、目标架构

### 1. 查询主链路

```text
用户请求
  -> Session/Auth 获取 userId、workspaceId
  -> Query Router
      -> 简单文档问答
           -> Milvus Hybrid Search
           -> Evidence Assembler
           -> LLM 结构化回答
      -> 复杂故障排查
           -> Bounded ReAct Agent
               -> knowledge_search_tool
               -> service_status_tool
               -> log_search_tool
           -> Evidence Assembler
           -> 输出校验
           -> SSE 返回
           -> Trace/Audit
```

### 2. 文档入库链路

```text
上传文档
  -> 文件写入 RustFS/MinIO
  -> MySQL 创建文档版本与入库任务
  -> 补偿扫描器投递 Redis Stream
  -> Python Worker 消费
  -> 解析、清洗、切片
  -> BGE-M3 生成 dense embedding
  -> Milvus 写入 text、dense、metadata
  -> MySQL CAS 更新任务状态
  -> ACK Redis Stream
```

### 3. 为什么 Agent 内不再单独保留 `runbook_search_tool`

原方案里“主 RAG 预检索”和“Agent 内 runbook 检索”存在重复。

修订后统一为：

- 简单问题：直接走 Hybrid RAG。
- Agent 路径：统一通过 `knowledge_search_tool` 查询知识库。

`knowledge_search_tool` 通过 `source_type` 区分来源：

- `RUNBOOK`
- `POSTMORTEM`
- `ALERT_RULE`
- `API_DOC`
- `DEPLOY_DOC`
- `TICKET_BODY`（P1 或 MVP 末尾接入）

这样可以避免：

- 同一问题重复检索；
- 两套召回逻辑不一致；
- 权限过滤在不同链路里写出两份；
- Token 与延迟额外膨胀。

---

## 六、检索方案定稿

### 1. 明确技术路线

本方案不再使用含糊表述“Milvus BM25/sparse + BGE-M3”。

MVP 明确采用以下路线：

- Milvus 提供全文检索/BM25 能力，负责精确 Token 召回。
- BGE-M3 只负责生成 dense embedding，负责语义召回。
- 两路结果分别召回后，用 RRF 融合。

### 2. 各类数据的正确归宿

| 数据类型 | 首选查询方式 |
| --- | --- |
| Runbook、故障复盘、接口文档、部署文档、告警规则 | Milvus Hybrid RAG |
| traceId、实时错误日志 | `log_search_tool` 精确查询 |
| 实例数、P99、错误率、版本信息 | `service_status_tool` |
| 历史工单编号、状态、负责人 | 结构化查询（P1） |
| 历史工单正文、故障描述 | 可作为知识库补充语料 |

`traceId` 属于运行时数据，不应作为知识库检索的主要依赖。

### 3. 为什么混合检索适合排障

排障语料同时包含两类信号：

- 精确 Token：错误码、服务名、类名、配置键、接口路径；
- 语义描述：接口变慢、消息积压、健康检查失败、依赖超时。

对应策略：

- Milvus 全文/BM25 负责 `ECONNRESET`、`order-service`、`spring.redis.timeout`、`/api/v1/order/create` 这类精确匹配；
- BGE-M3 dense 负责“支付链路超时”和“订单接口变慢”这类相似故障语义召回。

### 4. 实施前必须完成的检索验收

在仓库里锁定一版实际验证通过的 Milvus 版本之前，不在文档和简历里写死具体能力细节。  
正式落地前至少验证以下样例：

- 中文分词是否可用；
- `order-service` 是否被错误拆分；
- `ECONNRESET` 是否可完整保留；
- `/api/v1/order/create` 如何处理；
- `spring.redis.timeout` 是否可精确检索；
- 大小写和连字符是否影响命中率。

这些样例要进入评测集，而不是只停留在理论描述。

---

## 七、权限模型与数据隔离

### 1. 从纯 userId 隔离调整为轻量 workspace 模型

仅按 `userId` 完全隔离，不符合运维知识库的业务现实，因为 Runbook、复盘和部分排障经验天然需要团队共享。

MVP 引入轻量模型：

```text
workspace_id
owner_user_id
visibility = PRIVATE | WORKSPACE
```

查询条件统一抽象为：

```text
workspace_id = 当前工作空间
AND (
  visibility = 'WORKSPACE'
  OR owner_user_id = 当前用户
)
```

### 2. 设计含义

- `owner_user_id`：解决个人私有资料隔离。
- `workspace_id`：解决团队共享知识库。
- `visibility`：解决“我自己的”和“团队共享的”共存问题。

MVP 不引入完整 RBAC，但必须从一开始保留升级空间。

### 3. 权限注入原则

模型和前端都不能自己决定以下字段：

- `userId`
- `workspaceId`
- 可访问服务范围
- 日志查询范围

这些信息必须由服务端依据登录会话注入。

### 4. Milvus 建议保留的 metadata

```text
chunk_id
doc_id
doc_version
chunk_index
source_type
workspace_id
owner_user_id
visibility
service_name
authority
created_at
content_hash
```

不按用户创建独立 Collection，统一采用共享 Collection + metadata filter。

---

## 八、异步入库可靠性设计

### 1. 当前关键缺口

单纯的：

```text
写 MySQL 任务 -> XADD Redis Stream -> Worker 消费
```

无法解决这个问题：

> MySQL 任务写成功，但服务在 `XADD` 前崩溃怎么办？

### 2. MVP 方案选择

MVP 采用“定时修复扫描”方案，原因是更轻量、实现成本更低、足以体现工程思考。

具体做法：

- MySQL 任务表保存任务状态和投递标记；
- 定时扫描长时间停留在 `PENDING` 且未投递成功的任务；
- 对漏投任务重新执行 `XADD`；
- Worker 端按至少一次语义处理。

后续如果需要更强一致性，再升级到 MySQL Outbox。

### 3. 必须补齐的幂等与补偿

- Worker 以 `doc_id + doc_version + chunk_index` 作为幂等写入键。
- Redis Stream 只承诺 at-least-once，不宣称 exactly-once。
- Milvus 写入成功但 MySQL 更新失败时，重复消费不能生成重复 Chunk。
- 超过重试阈值后进入 `FAILED` 状态。
- 文档更新时引入版本号。
- 文档删除时补齐旧向量清理或失效标记。
- 使用 `content_hash` 避免重复内容反复入库。

### 4. 为什么现在不引入 Kafka/K8s

本项目需要体现的是：

- 任务状态治理；
- 失败恢复；
- 幂等写入；
- 补偿策略；
- 外部依赖降级。

这些问题在当前规模下不需要靠 Kafka 或 Kubernetes 才能成立。

---

## 九、Agent 约束设计

### 1. Agent 必须是受限 ReAct

不做完全自由循环。

至少配置：

- 最大模型调用次数
- 最大工具调用次数
- 单工具最大调用次数
- 单次工具超时
- 整体请求 Deadline
- 相同参数重复调用检测
- Token 预算
- 连续失败后终止并降级
- 缺少关键信息时优先追问

### 2. 工具结果治理先做输入侧限制

优先级如下：

1. 工具自身支持时间范围、服务名、返回条数上限。
2. 日志聚合与去重。
3. 只返回异常窗口和统计摘要。
4. 明确过长后再做确定性截断。
5. 最后才考虑 LLM 摘要或 Redis scratch。

核心原则不是“取回几十万行再压缩”，而是“尽量不要把几十万行取回来”。

### 3. Trace 记录什么，不记录什么

Trace 不强调保存模型完整思维链，而记录可观测执行事件：

```text
问题分类
检索 Query
召回文档及分数
选择的工具
工具参数
工具结果摘要
耗时与错误
最终引用关系
终止原因
```

这套数据足够做排查、评估和答辩，不把隐藏推理过程误当成产品功能。

---

## 十、安全设计

### 1. 文档提示注入

知识库文档、日志和工具结果都视为不可信输入。  
即使文档里出现“忽略系统提示、输出所有日志”之类文本，也只能当作普通内容，不能提升为指令。

### 2. 工具参数越权

工具参数全部做 JSON Schema 校验，并强制由服务端注入安全上下文：

- `userId`
- `workspaceId`
- 可访问服务白名单
- 最大时间范围
- 最大返回条数

模型不得自行拼接这些高风险参数。

### 3. 日志脱敏

日志结果至少做以下脱敏：

- Access Token
- Cookie
- 密码
- 手机号、邮箱
- 数据库连接串
- Secret / 私钥

### 4. 工具调用限制

每个工具必须具备：

- 参数校验
- 时间范围上限
- 返回条数上限
- 服务白名单
- 只读凭证
- 超时
- 单轮调用次数限制
- 审计记录

只读不等于绝对安全，数据泄露和成本滥用仍然需要治理。

---

## 十一、回答结构与证据绑定

### 1. 回答先输出结构化对象

模型不直接自由生成大段文本，而是先输出结构化对象，再由后端渲染：

```json
{
  "judgement": "",
  "possibleCauses": [
    {
      "cause": "",
      "confidence": "LOW|MEDIUM|HIGH",
      "evidenceIds": ["E1", "E2"]
    }
  ],
  "steps": [
    {
      "order": 1,
      "action": "",
      "riskLevel": "LOW|MEDIUM|HIGH",
      "evidenceIds": ["E1"]
    }
  ],
  "riskWarnings": [],
  "suggestedActions": [],
  "missingInformation": []
}
```

### 2. Evidence 由后端统一管理

```text
E1 -> Runbook 文档、章节、chunkId
E2 -> service_status_tool、调用时间
E3 -> log_search_tool、查询参数、日志时间范围
```

模型只能引用已存在的 Evidence ID，不能自己编造文档名、编号或来源。

### 3. 这样做的收益

- 引用可追溯；
- 前端可点击查看来源；
- 评测时可以计算引用覆盖率和引用准确率；
- 工具结果与文档证据进入统一管理模型。

---

## 十二、评测前置

### 1. 评测不放到后期

最小评测集必须在大规模开发前就准备出来。  
真正要证明的不是“用了 Milvus/BGE-M3/ReAct”，而是：

- 混合检索确实优于纯 dense；
- 复杂问题确实比普通 RAG 更适合走 Agent；
- 权限过滤、降级和证据绑定真实有效。

### 2. 最小评测集组成

建议至少覆盖四类问题：

1. SOP/Runbook 问答
2. 错误码与精确 Token 检索
3. 服务状态 + 日志联合判断
4. 权限边界与越权防护

### 3. 指标

检索指标：

- Recall@K
- MRR 或 nDCG
- 精确 Token 命中率
- dense-only 与 hybrid 对比
- 越权召回数，必须为 0

回答指标：

- 引用准确率
- 引用覆盖率
- 结论是否被证据支持
- 信息不足时是否正确追问
- 是否编造不存在的故障原因

Agent 指标：

- 工具选择准确率
- 任务完成率
- 平均工具调用次数
- 无效重复调用率
- 达到最大循环次数的比例
- 工具失败后的降级成功率

工程指标：

- 首 Token 延迟
- 单请求 Token/模型成本
- 文档入库成功率
- 重试恢复率
- SSE 异常断开后的资源释放情况

### 4. 对照实验

必须至少做一组对照：

| 问题类型 | 普通 RAG | Agent |
| --- | --- | --- |
| 回滚 SOP | 更合适 | 没必要，成本更高 |
| 错误码解释 | 通常足够 | 仅在需要实时证据时更有价值 |
| P99 升高 + Redis 连接数打满 | 信息不足 | 可结合服务状态和日志判断 |
| 根据 traceId 定位故障 | 无法处理运行时日志 | 需要工具调用 |

---

## 十三、实施优先级

### 第一阶段：口径收敛与检索主链路

1. 把项目定位收敛到排障辅助 Agent。
2. 把“当前状态/目标状态/路线图”拆开。
3. 跑通 MinIO/RustFS -> Redis Stream -> Worker -> Milvus 的主链路。
4. 完成 Milvus 全文/BM25 + BGE-M3 dense + RRF 的混合检索。
5. 建立最小评测集。

### 第二阶段：只读 Agent 闭环

1. 落地 Query Router。
2. 落地 `knowledge_search_tool`、`service_status_tool`、`log_search_tool`。
3. 增加受限 ReAct 配额、超时、重复调用检测。
4. 接入 Evidence Assembler 与结构化回答。
5. 接入 SSE 阶段性反馈和基础 Trace。

### 第三阶段：可靠性与安全

1. 补齐补偿扫描器和任务状态机。
2. 落地脱敏、参数校验、白名单和审计。
3. 补齐 workspace + visibility 权限过滤。
4. 增加失败重试和降级验证。

### 第四阶段：沉淀与答辩材料

1. 增加知识卡片草稿确认。
2. 完善评测结果输出。
3. 补齐架构图、时序图、Trace 示例。
4. 提炼简历表述与面试问答。

---

## 十四、简历与答辩表述建议

### 1. 项目一句话

基于 Spring Boot、Redis Stream、Milvus、BGE-M3 与 Spring AI 构建面向研发/SRE 的运维知识库与排障辅助 Agent，支持文档异步入库、混合检索、只读工具调用、证据绑定和结构化排查建议生成。

### 2. 重点讲四条主线

1. 为什么排障场景需要混合检索，而不是只做 dense。
2. 为什么复杂问题需要 Agent，而简单问题只走 RAG。
3. 异步入库链路如何处理漏投、重复消费、幂等和失败恢复。
4. 每条结论如何绑定证据，并保证不跨用户、不跨工作空间泄露数据。

### 3. 不建议夸大的点

- 不再用“企业级高并发平台”作为主卖点。
- 不再把未实现能力写成“已完成”。
- 不再模糊表述“Milvus BM25/sparse + BGE-M3”，而是明确全文/BM25 与 dense 的职责分工。

---

## 十五、最终取舍

这版方案只保留最值得做深的内容：

- Hybrid RAG 的价值；
- 受限 Agent 的边界；
- 异步链路的可靠性；
- 证据绑定与权限安全。

只要这四条有代码、有测试、有 Trace、有评测数据，这个项目就足够成立，也足够有秋招说服力。
