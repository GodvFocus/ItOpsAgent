package com.ai.itops.security.permission.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 权限和敏感成员操作审计记录，不保存 Token、密码或完整请求体。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("permission_audit_log")
public class PermissionAuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("operator_id")
    private Long operatorId;

    @TableField("workspace_id")
    private String workspaceId;

    @TableField("resource_type")
    private String resourceType;

    @TableField("resource_id")
    private String resourceId;

    private String action;

    private String result;

    @TableField("failure_reason")
    private String failureReason;

    @TableField("trace_id")
    private String traceId;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
