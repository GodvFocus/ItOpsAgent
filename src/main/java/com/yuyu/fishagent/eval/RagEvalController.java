package com.yuyu.fishagent.eval;

import com.yuyu.fishagent.auth.context.UserContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * RAG 真实检索评测入口。
 */
@RestController
@RequiredArgsConstructor
public class RagEvalController {

    private final RagEvalService ragEvalService;

    /**
     * 使用当前登录用户和 workspace 上下文执行 RAG golden set 评测。
     *
     * <p>同时支持 GET 和 POST，方便本地手工触发；请求不会接受 user/workspace 覆盖参数，
     * 以免评测绕过检索器已有的权限过滤。</p>
     */
    @GetMapping("/api/eval/rag")
    public HybridEvalReport evaluateByGet(
            @RequestParam(name = "k", required = false) Integer k,
            @RequestParam(name = "perLegK", required = false) Integer perLegK) {
        return evaluate(k, perLegK);
    }

    @PostMapping("/api/eval/rag")
    public HybridEvalReport evaluateByPost(
            @RequestParam(name = "k", required = false) Integer k,
            @RequestParam(name = "perLegK", required = false) Integer perLegK) {
        return evaluate(k, perLegK);
    }

    private HybridEvalReport evaluate(Integer k, Integer perLegK) {
        if (UserContextHolder.currentUserIdOrNull() == null) {
            throw new IllegalStateException("未登录，无法执行 RAG 评测");
        }
        if (UserContextHolder.currentWorkspaceIdOrNull() == null
                || UserContextHolder.currentWorkspaceIdOrNull().isBlank()) {
            throw new IllegalStateException("当前用户未选择 workspace，无法执行 RAG 评测");
        }
        return ragEvalService.run(k, perLegK);
    }
}
