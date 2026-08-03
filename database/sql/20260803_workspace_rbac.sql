-- Workspace RBAC 增量迁移。
-- 该脚本不依赖 Flyway，按项目现有的手工 SQL 迁移方式执行；重复执行安全。
USE itops_agent;

CREATE TABLE IF NOT EXISTS workspace (
    id         VARCHAR(64)  NOT NULL COMMENT 'Workspace 主键，沿用现有字符串 workspaceId',
    name       VARCHAR(128) NOT NULL,
    owner_id   BIGINT       NOT NULL COMMENT '必须与 OWNER 成员记录一致',
    status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_by BIGINT       NOT NULL,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_workspace_owner (owner_id),
    KEY idx_workspace_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Workspace 权限边界';

CREATE TABLE IF NOT EXISTS workspace_member (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    workspace_id VARCHAR(64) NOT NULL,
    user_id      BIGINT      NOT NULL,
    role         VARCHAR(16) NOT NULL,
    status       VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    joined_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_workspace_member (workspace_id, user_id),
    KEY idx_member_user_workspace (user_id, workspace_id),
    KEY idx_member_workspace_role (workspace_id, role),
    CONSTRAINT fk_member_workspace FOREIGN KEY (workspace_id) REFERENCES workspace (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Workspace 成员关系';

CREATE TABLE IF NOT EXISTS permission_audit_log (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    operator_id    BIGINT                DEFAULT NULL,
    workspace_id   VARCHAR(64)            DEFAULT NULL,
    resource_type  VARCHAR(32)  NOT NULL,
    resource_id    VARCHAR(128)           DEFAULT NULL,
    action         VARCHAR(64)  NOT NULL,
    result         VARCHAR(16)  NOT NULL,
    failure_reason VARCHAR(500)           DEFAULT NULL,
    trace_id       VARCHAR(128)           DEFAULT NULL,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_audit_workspace_time (workspace_id, created_at),
    KEY idx_audit_operator_time (operator_id, created_at),
    KEY idx_audit_action_time (action, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '权限与敏感操作审计日志';

-- 资源创建者字段用于 EDITOR 的细粒度修改/删除判断。
SET @created_by_col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'document_metadata'
      AND COLUMN_NAME = 'created_by'
);
SET @sql := IF(@created_by_col_exists = 0,
    'ALTER TABLE document_metadata ADD COLUMN created_by BIGINT NULL AFTER user_id',
    'SELECT ''skip add document_metadata.created_by''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE document_metadata SET created_by = user_id WHERE created_by IS NULL;

SET @created_by_idx_exists := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'document_metadata'
      AND INDEX_NAME = 'idx_workspace_created_by'
);
SET @sql := IF(@created_by_idx_exists = 0,
    'CREATE INDEX idx_workspace_created_by ON document_metadata (workspace_id, created_by)',
    'SELECT ''skip create idx_workspace_created_by''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 历史数据仅在存在用户时建立 default Workspace；OWNER 取最早注册用户，随后其他历史用户作为 VIEWER。
INSERT INTO workspace (id, name, owner_id, status, created_by)
SELECT 'default', '默认工作区', u.id, 'ACTIVE', u.id
FROM sys_user u
WHERE u.id = (SELECT MIN(id) FROM sys_user)
  AND NOT EXISTS (SELECT 1 FROM workspace WHERE id = 'default');

INSERT IGNORE INTO workspace_member (workspace_id, user_id, role, status)
SELECT 'default', owner_id, 'OWNER', 'ACTIVE' FROM workspace WHERE id = 'default';

INSERT IGNORE INTO workspace_member (workspace_id, user_id, role, status)
SELECT 'default', u.id,
       CASE WHEN UPPER(u.role) = 'ADMIN' THEN 'ADMIN' ELSE 'VIEWER' END,
       'ACTIVE'
FROM sys_user u
JOIN workspace w ON w.id = 'default'
WHERE u.id <> w.owner_id;

-- Outbox 已有 workspace_id/user_id；消息中的 operator_id、document_id、created_by
-- 由 Java 服务从已校验的文档记录生成，不接受前端原始字段。
