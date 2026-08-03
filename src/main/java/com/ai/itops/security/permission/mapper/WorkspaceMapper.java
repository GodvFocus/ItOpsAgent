package com.ai.itops.security.permission.mapper;

import com.ai.itops.security.permission.entity.Workspace;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Workspace 数据访问，更新所有者时使用数据库行锁。 */
@Mapper
public interface WorkspaceMapper extends BaseMapper<Workspace> {

    @Select("SELECT * FROM workspace WHERE id = #{workspaceId} FOR UPDATE")
    Workspace selectByIdForUpdate(@Param("workspaceId") String workspaceId);

    @Update("UPDATE workspace SET owner_id = #{ownerId}, updated_at = #{updatedAt} "
            + "WHERE id = #{workspaceId} AND owner_id = #{expectedOwnerId}")
    int updateOwner(@Param("workspaceId") String workspaceId,
                    @Param("ownerId") Long ownerId,
                    @Param("expectedOwnerId") Long expectedOwnerId,
                    @Param("updatedAt") java.time.LocalDateTime updatedAt);
}
