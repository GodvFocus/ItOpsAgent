package com.ai.itops.security.permission.entity;

import com.ai.itops.security.permission.MemberStatus;
import com.ai.itops.security.permission.WorkspaceRole;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Workspace 与用户的成员关系。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("workspace_member")
public class WorkspaceMember {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("workspace_id")
    private String workspaceId;

    @TableField("user_id")
    private Long userId;

    private WorkspaceRole role;

    private MemberStatus status;

    @TableField("joined_at")
    private LocalDateTime joinedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
