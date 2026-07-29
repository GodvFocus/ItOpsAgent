# ROADMAP

1. [已完成代码与评测链路] 修复真实 Hybrid RAG，并建立端到端评测
   lexical 查询真正使用用户 query；使用真实 Milvus 召回结果计算 Recall@K、MRR、nDCG，并支持 `dense-only / lexical-only / hybrid / hybrid+rerank` 消融实验和延迟、成本记录。原生 BM25 已接入，Docker Milvus 已升级到 v2.6.20 并完成 `_bm25` collection 迁移。
2. 实现路由级工具权限与统一 Evidence Registry
   让每次 RAG 命中、日志命中、状态快照、工单记录都先注册为不可变 Evidence，再让结构化结论引用这些 ID。评测 citation precision、citation coverage 和 unsupported claim rate。
3. 完成可靠事件投递
   使用 Transactional Outbox：业务任务和 outbox 同事务写入，独立 dispatcher XADD，记录 `published_at/attempt_count/next_retry_at`，增加指数退避、DLQ、人工重放和重复投递测试。这个方向对 Java 后端岗的价值非常高。
4. 修复分布式锁所有权
   锁值改为 request token，使用 Lua compare-and-delete；增加续租或 watchdog，防止旧请求删除新锁。补 Redis 故障、TTL 过期、客户端断连等并发测试。
5. 建立真实工程指标
   至少测量 Router accuracy/F1、工具选择和参数准确率、Recall@K、引用准确率、p95 TTFT、完整响应 p95、失败恢复时间、平均模型调用次数与 token 成本。没有数字时，“提升命中率”“降低成本”都只是目标，不是成果。
