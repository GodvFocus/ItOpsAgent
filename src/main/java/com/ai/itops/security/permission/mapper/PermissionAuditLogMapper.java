package com.ai.itops.security.permission.mapper;

import com.ai.itops.security.permission.entity.PermissionAuditLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 权限审计日志持久化。 */
@Mapper
public interface PermissionAuditLogMapper extends BaseMapper<PermissionAuditLog> {
}
