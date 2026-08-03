package com.ai.itops.security.permission;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 资源层权限校验门面，避免业务服务自行拼接跨 Workspace 查询。 */
@Service
@RequiredArgsConstructor
public class ResourceOwnershipService {

    private final PermissionEvaluator permissionEvaluator;

    public void checkDocument(Long userId, String workspaceId, String taskId,
                              WorkspacePermission permission) {
        if (permissionEvaluator instanceof DefaultPermissionEvaluator evaluator) {
            evaluator.checkDocumentPermission(userId, workspaceId, taskId, permission);
        } else {
            permissionEvaluator.checkResourcePermission(userId, workspaceId, ResourceType.DOCUMENT,
                    taskId, permission);
        }
    }
}
