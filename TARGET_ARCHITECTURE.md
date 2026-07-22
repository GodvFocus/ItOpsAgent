# TARGET_ARCHITECTURE

## 文档目的

本文只描述目标架构，不描述当前仓库已经做到的内容。  
目标是把 Fish-Agent 收敛为一个面向研发/SRE 的运维知识库与排障辅助 Agent 系统。

---

## 一、目标定位

### 1. 一句话目标

面向研发、SRE、DevOps、技术支持和值班新人，围绕故障排查场景提供：

- 只读式证据检索
- 多源证据汇总
- 结构化排查建议生成
- 人工确认后的知识沉淀

### 2. 目标边界

目标系统不直接执行生产变更，不自动：

- 重启服务
- 修改配置
- 清理缓存
- 创建真实工单
- 发送真实通知

它只输出建议、步骤和草稿，并绑定证据。

---

## 二、目标查询架构

### 1. Query Router

目标系统在入口处先进行问题路由：

```text
用户请求
  -> Session/Auth 注入 userId、workspaceId
  -> Query Router
      -> 简单文档/SOP/规则问答：Hybrid RAG 快速路径
      -> 复杂故障排查：Bounded ReAct Agent 路径
```

### 2. 两条执行路径

#### 简单问题

适用于：

- Runbook 查询
- SOP 问答
- 配置项定位
- 告警规则说明

执行路径：

```text
Hybrid Search -> Evidence Assembler -> 结构化回答
```

#### 复杂问题

适用于：

- 故障现象判断
- 服务状态与日志联合排查
- traceId/错误窗口驱动的定位
- 多步证据补全

执行路径：

```text
Bounded ReAct Agent
  -> knowledge_search_tool
  -> service_status_tool
  -> log_search_tool
  -> Evidence Assembler
  -> 结构化回答
```

---

## 三、目标检索架构

### 1. 检索主线

目标架构放弃 Elasticsearch 作为主检索引擎，切换到：

- Milvus 全文/BM25：负责精确 Token 召回
- BGE-M3 dense embedding：负责语义召回
- RRF：负责两路结果融合

### 2. 检索职责拆分

| 需求 | 目标实现 |
| --- | --- |
| 错误码、服务名、接口路径、配置键精确匹配 | Milvus 全文/BM25 |
| 相似故障语义召回 | BGE-M3 dense |
| 多来源排序融合 | RRF |

### 3. 数据源职责拆分

| 数据类型 | 目标查询方式 |
| --- | --- |
| Runbook、故障复盘、接口文档、部署文档、告警规则 | Milvus Hybrid RAG |
| traceId、实时错误日志 | `log_search_tool` |
| 实例数、版本、P99、错误率 | `service_status_tool` |
| 历史工单编号、状态、负责人 | 结构化工单查询 |
| 历史工单正文 | 知识库补充语料 |

目标态里，运行时日志和状态不再由知识库兜底，而是明确拆到专用工具。

---

## 四、目标权限模型

### 1. 轻量工作空间模型

目标权限模型从纯 `userId` 隔离，升级为：

```text
workspace_id
owner_user_id
visibility = PRIVATE | WORKSPACE
```

统一查询条件：

```text
workspace_id = 当前工作空间
AND (
  visibility = 'WORKSPACE'
  OR owner_user_id = 当前用户
)
```

### 2. 设计目标

- 允许个人私有知识存在
- 允许团队共享 Runbook、复盘和排障经验
- 不在 MVP 引入完整 RBAC
- 为后续 tenant/team/role 扩展留出空间

### 3. 注入原则

以下信息全部由服务端注入，模型和前端都不能自带：

- `userId`
- `workspaceId`
- 服务访问范围
- 日志查询范围

---

## 五、目标文档入库架构

### 1. 目标链路

```text
上传文档
  -> RustFS/MinIO 保存原文
  -> MySQL 创建文档版本与入库任务
  -> 补偿扫描器投递 Redis Stream
  -> Python Worker 消费
  -> 解析、清洗、切片
  -> BGE-M3 dense embedding
  -> Milvus 写入 text、dense、metadata
  -> MySQL CAS 更新任务状态
  -> ACK Redis Stream
```

### 2. 可靠性目标

MVP 目标不是 exactly-once，而是：

- at-least-once 投递
- 幂等写入
- 明确补偿
- 明确失败状态

### 3. 关键约束

- 以 `doc_id + doc_version + chunk_index` 做幂等键
- 使用补偿扫描器处理漏投任务
- 文档更新必须带版本号
- 删除必须触发旧向量清理或失效标记
- `content_hash` 用于重复内容识别

---

## 六、目标 Agent 架构

### 1. Bounded ReAct

目标 Agent 不是自由循环，而是受限 ReAct。

必须具备：

- 最大模型调用次数
- 最大工具调用次数
- 单工具调用上限
- 单次工具超时
- 请求总 Deadline
- 重复参数调用检测
- Token 预算
- 连续失败后的降级终止

### 2. 目标工具集

MVP 工具集只保留排障刚需工具：

- `knowledge_search_tool`
- `service_status_tool`
- `log_search_tool`

P1 再考虑：

- `ticket_query_tool`
- `ticket_draft_tool`
- `notify_draft_tool`

### 3. 工具治理目标

每个工具必须具备：

- JSON Schema 参数校验
- 时间范围上限
- 返回条数上限
- 服务白名单
- 只读凭证
- 超时
- 单轮调用次数限制
- 审计记录

---

## 七、目标回答架构

### 1. 先 Evidence，后回答

目标系统先组装证据，再生成回答。  
模型不能自由编造来源。

### 2. 结构化输出

目标回答对象：

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

### 3. Evidence 管理目标

Evidence 由后端分配 ID 并维护映射，例如：

```text
E1 -> Runbook 文档、章节、chunkId
E2 -> service_status_tool、调用时间
E3 -> log_search_tool、查询参数、日志时间范围
```

模型只能引用已有 `Evidence ID`。

---

## 八、目标安全架构

### 1. 输入不可信

以下内容全部按不可信输入处理：

- 知识库文档
- 日志结果
- 工具返回

这类内容不能提升为系统指令。

### 2. 安全约束

目标架构要求：

- 文档提示注入防护
- 工具参数越权防护
- 日志敏感信息脱敏
- 工具白名单与只读凭证
- 调用审计

### 3. 日志脱敏范围

至少包括：

- Access Token
- Cookie
- 密码
- 手机号、邮箱
- 数据库连接串
- Secret / 私钥

---

## 九、目标可观测性与评测

### 1. Trace 目标

目标 Trace 记录的是执行事件，不是模型完整思维链：

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

### 2. 评测目标

目标系统必须内建最小排障评测集，覆盖：

- SOP/Runbook 问答
- 错误码精确召回
- 服务状态 + 日志联合排查
- 权限越权防护

### 3. 核心指标

- Recall@K
- 精确 Token 命中率
- 引用准确率
- 引用覆盖率
- 工具选择准确率
- 平均工具调用次数
- 越权召回数必须为 0

---

## 十、目标非功能要求

### 1. 性能

- SSE 首屏可感知反馈
- 工具调用结果不能无限膨胀上下文
- 长结果优先通过工具侧约束，而不是把超长原文全部注入模型

### 2. 可靠性

- 外部依赖故障时可降级
- 任务失败可恢复
- 文档重复消费不产生重复切片

### 3. 可答辩性

目标架构的重点不是堆中间件，而是把以下四点讲透：

1. 为什么排障场景需要 Hybrid RAG。
2. 为什么复杂问题需要 Agent，简单问题不需要。
3. 异步链路如何处理漏投、重试、幂等和失败恢复。
4. 回答如何绑定证据并防止跨用户、跨工作空间泄露。

---

## 十一、目标结论

目标架构可以概括为：

> 一个面向研发/SRE 排障场景、以 Milvus Hybrid RAG 为知识底座、以受限 ReAct 为推理执行器、以证据绑定和工作空间隔离为安全边界的只读式排障辅助 Agent。

它和当前架构最大的差异有四个：

- 检索底座从 Elasticsearch 迁移到 Milvus + BGE-M3
- 权限模型从 `user_id + PUBLIC/PRIVATE` 升级到 `workspace + visibility`
- 工具集从通用工具切换为排障专用工具
- 对话入口从统一 Agent 演进为 Query Router + 双路径执行
