package com.yuyu.fishagent.chat.router;

/**
 * 对话入口查询路由器。
 */
public interface QueryRouter {

    /**
     * 基于当前用户输入决定走快路径还是 Agent 路径。
     */
    RouteDecision route(String userInput);
}
