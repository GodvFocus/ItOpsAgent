package com.yuyu.fishagent.eval;

import com.yuyu.fishagent.auth.context.UserContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 真实工程指标评测入口。
 */
@RestController
@RequiredArgsConstructor
public class EngineeringMetricsController {

    private final EngineeringMetricsService engineeringMetricsService;

    @GetMapping("/api/eval/engineering")
    public EngineeringMetricsReport evaluateByGet(
            @RequestParam(name = "k", required = false) Integer k,
            @RequestParam(name = "perLegK", required = false) Integer perLegK) {
        return evaluate(k, perLegK);
    }

    @PostMapping("/api/eval/engineering")
    public EngineeringMetricsReport evaluateByPost(
            @RequestParam(name = "k", required = false) Integer k,
            @RequestParam(name = "perLegK", required = false) Integer perLegK) {
        return evaluate(k, perLegK);
    }

    private EngineeringMetricsReport evaluate(Integer k, Integer perLegK) {
        if (UserContextHolder.currentUserIdOrNull() == null) {
            throw new IllegalStateException("未登录，无法执行真实工程指标评测");
        }
        if (UserContextHolder.currentWorkspaceIdOrNull() == null
                || UserContextHolder.currentWorkspaceIdOrNull().isBlank()) {
            throw new IllegalStateException("当前用户未选择 workspace，无法执行真实工程指标评测");
        }
        return engineeringMetricsService.run(k, perLegK);
    }
}
