package com.ai.itops.security.permission.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Workspace 权限边界。项目现有 workspaceId 为字符串，因此主键沿用 VARCHAR。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("workspace")
public class Workspace {

    @TableId
    private String id;

    private String name;

    @TableField("owner_id")
    private Long ownerId;

    private String status;

    @TableField("created_by")
    private Long createdBy;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
