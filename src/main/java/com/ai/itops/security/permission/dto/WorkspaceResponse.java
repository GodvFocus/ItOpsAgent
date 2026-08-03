package com.ai.itops.security.permission.dto;

import com.ai.itops.security.permission.entity.Workspace;

import java.time.LocalDateTime;

/** Workspace 对外视图，不直接暴露持久化实体。 */
public record WorkspaceResponse(String id, String name, Long ownerId, String status,
                                LocalDateTime createdAt, LocalDateTime updatedAt) {

    public static WorkspaceResponse from(Workspace workspace) {
        return new WorkspaceResponse(workspace.getId(), workspace.getName(), workspace.getOwnerId(),
                workspace.getStatus(), workspace.getCreatedAt(), workspace.getUpdatedAt());
    }
}
