package com.ai.itops.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 知识库上传任务元数据，映射 {@code document_metadata}；原文件在 RustFS itops-docs，解析由 Python Worker 异步完成。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("document_metadata")
public class DocumentMetadata {

    public static final String VISIBILITY_PRIVATE = "PRIVATE";
    public static final String VISIBILITY_WORKSPACE = "WORKSPACE";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("task_id")
    private String taskId;

    @TableField("user_id")
    private Long userId;

    /** 资源创建者，服务端写入，供 EDITOR 的资源所有权判断使用。 */
    @TableField("created_by")
    private Long createdBy;

    @TableField("workspace_id")
    private String workspaceId;

    @TableField("file_name")
    private String fileName;

    @TableField("file_size")
    private Long fileSize;

    @TableField("minio_path")
    private String minioPath;

    @TableField("visibility")
    private String visibility;

    private String status;

    @TableField("error_msg")
    private String errorMsg;

    /** Python Worker 写入 ES 的切片数量；可为 null（未完成或非文档任务）。 */
    @TableField("chunk_count")
    private Integer chunkCount;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    public String getScopeType() {
        return visibility;
    }

    public void setScopeType(String scopeType) {
        this.visibility = normalizeLegacyScope(scopeType);
    }

    public static String normalizeLegacyScope(String scopeType) {
        if (scopeType == null || scopeType.isBlank()) {
            return VISIBILITY_PRIVATE;
        }
        String normalized = scopeType.trim().toUpperCase();
        if ("PUBLIC".equals(normalized)) {
            return VISIBILITY_WORKSPACE;
        }
        return normalized;
    }
}
