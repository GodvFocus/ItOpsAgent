package com.ai.itops.security.permission.mapper;

import com.ai.itops.security.permission.entity.Workspace;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** Workspace 数据访问，更新所有者时使用数据库行锁。 */
@Mapper
public interface WorkspaceMapper extends BaseMapper<Workspace> {

    @Select("SELECT w.* FROM workspace w JOIN workspace_member wm ON wm.workspace_id = w.id "
            + "WHERE wm.user_id = #{userId} AND wm.status = 'ACTIVE' AND w.status = 'ACTIVE' "
            + "ORDER BY w.updated_at DESC, w.id ASC")
    List<Workspace> selectActiveByUserId(@Param("userId") Long userId);

    @Select("SELECT * FROM workspace WHERE id = #{workspaceId} FOR UPDATE")
    Workspace selectByIdForUpdate(@Param("workspaceId") String workspaceId);

    @Update("UPDATE workspace SET owner_id = #{ownerId}, updated_at = #{updatedAt} "
            + "WHERE id = #{workspaceId} AND owner_id = #{expectedOwnerId}")
    int updateOwner(@Param("workspaceId") String workspaceId,
                    @Param("ownerId") Long ownerId,
                    @Param("expectedOwnerId") Long expectedOwnerId,
                    @Param("updatedAt") java.time.LocalDateTime updatedAt);
}
