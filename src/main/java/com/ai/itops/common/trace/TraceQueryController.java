package com.ai.itops.common.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * dev profile 下的 trace 查询端点。
 *
 * <p>原从 ES 查询，ES → Milvus 迁移后改为读取本地 JSON 文件。</p>
 */
@Slf4j
@Profile("dev")
@RestController
@RequestMapping("/admin/trace")
@RequiredArgsConstructor
public class TraceQueryController {

    private final TraceProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping("/{turnId}")
    public TurnTrace get(@PathVariable String turnId) {
        if (turnId == null || turnId.isBlank()) {
            return null;
        }
        try {
            Path file = Paths.get(properties.getStorageDir(), turnId + ".json");
            if (!Files.exists(file)) {
                return null;
            }
            return objectMapper.readValue(file.toFile(), TurnTrace.class);
        } catch (Exception e) {
            log.warn("[TraceQueryController] 读取 trace 文件失败 turnId={}: {}", turnId, e.getMessage());
            return null;
        }
    }
}
