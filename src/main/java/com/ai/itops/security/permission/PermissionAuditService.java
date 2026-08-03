package com.ai.itops.security.permission;

import com.ai.itops.common.trace.TraceConstants;
import com.ai.itops.security.permission.entity.PermissionAuditLog;
import com.ai.itops.security.permission.mapper.PermissionAuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** 尽力而为的审计记录，审计表故障不能回滚主要业务操作。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionAuditService {

    private final PermissionAuditLogMapper mapper;

    public void record(Long operatorId, String workspaceId, String resourceType, String resourceId,
                       String action, String result, String failureReason) {
        try {
            PermissionAuditLog logRow = new PermissionAuditLog();
            logRow.setOperatorId(operatorId);
            logRow.setWorkspaceId(workspaceId);
            logRow.setResourceType(safe(resourceType, 32));
            logRow.setResourceId(safe(resourceId, 128));
            logRow.setAction(safe(action, 64));
            logRow.setResult(safe(result, 16));
            logRow.setFailureReason(safe(failureReason, 500));
            logRow.setTraceId(safe(MDC.get(TraceConstants.TRACE_ID), 128));
            logRow.setCreatedAt(LocalDateTime.now());
            mapper.insert(logRow);
        } catch (Exception e) {
            log.warn("权限审计记录失败 workspaceId={}, action={}: {}", workspaceId, action, e.getMessage());
        }
    }

    private static String safe(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String valueTrimmed = value.trim();
        return valueTrimmed.length() <= maxLength ? valueTrimmed : valueTrimmed.substring(0, maxLength);
    }
}
