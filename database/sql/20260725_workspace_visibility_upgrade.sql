-- 兼容旧版 MySQL 的权限模型升级脚本。
-- 不依赖 ADD COLUMN IF NOT EXISTS / CREATE INDEX IF NOT EXISTS，
-- 通过 information_schema 做幂等判断，可重复执行。

-- 请先确认当前库就是 Fish-Agent 使用的数据库。
-- 如有需要，可先执行：USE itops_agent;

SET @workspace_col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'document_metadata'
      AND COLUMN_NAME = 'workspace_id'
);

SET @sql := IF(
    @workspace_col_exists = 0,
    'ALTER TABLE document_metadata ADD COLUMN workspace_id VARCHAR(64) NOT NULL DEFAULT ''default'' AFTER user_id',
    'SELECT ''skip add workspace_id'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @visibility_col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'document_metadata'
      AND COLUMN_NAME = 'visibility'
);

SET @sql := IF(
    @visibility_col_exists = 0,
    'ALTER TABLE document_metadata ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT ''PRIVATE'' AFTER minio_path',
    'SELECT ''skip add visibility'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE document_metadata
SET workspace_id = CASE
        WHEN workspace_id IS NULL OR workspace_id = '' THEN 'default'
        ELSE workspace_id
    END,
    visibility = CASE
        WHEN scope_type = 'PUBLIC' THEN 'WORKSPACE'
        WHEN visibility IS NULL OR visibility = '' THEN 'PRIVATE'
        ELSE visibility
    END
WHERE workspace_id IS NULL
   OR workspace_id = ''
   OR visibility IS NULL
   OR visibility = ''
   OR scope_type = 'PUBLIC';

SET @idx_exists := (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'document_metadata'
      AND INDEX_NAME = 'idx_workspace_visibility'
);

SET @sql := IF(
    @idx_exists = 0,
    'CREATE INDEX idx_workspace_visibility ON document_metadata (workspace_id, visibility)',
    'SELECT ''skip create idx_workspace_visibility'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
