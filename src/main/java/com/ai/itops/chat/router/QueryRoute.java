package com.ai.itops.chat.router;

/**
 * 查询路由结果。
 */
public enum QueryRoute {

    /**
     * 简单 SOP / Runbook / 文档问答，直接走 Hybrid RAG 快路径。
     */
    FAST_RAG,

    /**
     * 复杂故障排查，走受限 Agent + 排障工具链。
     */
    TROUBLESHOOTING_AGENT
}
