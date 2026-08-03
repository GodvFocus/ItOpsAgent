package com.ai.itops.security.permission.mapper;

import com.ai.itops.security.permission.entity.WorkspaceMember;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** 成员关系数据访问，所有权限查询都带 workspace_id。 */
@Mapper
public interface WorkspaceMemberMapper extends BaseMapper<WorkspaceMember> {

    @Select("SELECT * FROM workspace_member WHERE workspace_id = #{workspaceId} "
            + "AND user_id = #{userId} LIMIT 1")
    WorkspaceMember selectByWorkspaceAndUser(@Param("workspaceId") String workspaceId,
                                              @Param("userId") Long userId);

    @Select("SELECT * FROM workspace_member WHERE workspace_id = #{workspaceId} "
            + "AND user_id = #{userId} FOR UPDATE")
    WorkspaceMember selectByWorkspaceAndUserForUpdate(@Param("workspaceId") String workspaceId,
                                                       @Param("userId") Long userId);

    @Select("SELECT * FROM workspace_member WHERE workspace_id = #{workspaceId} "
            + "ORDER BY joined_at ASC, id ASC")
    List<WorkspaceMember> selectByWorkspaceId(@Param("workspaceId") String workspaceId);

    @Update("UPDATE workspace_member SET role = #{role}, updated_at = #{updatedAt} "
            + "WHERE workspace_id = #{workspaceId} AND user_id = #{userId} "
            + "AND status = 'ACTIVE'")
    int updateRole(@Param("workspaceId") String workspaceId,
                   @Param("userId") Long userId,
                   @Param("role") String role,
                   @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE workspace_member SET status = 'DISABLED', updated_at = #{updatedAt} "
            + "WHERE workspace_id = #{workspaceId} AND user_id = #{userId} "
            + "AND status = 'ACTIVE'")
    int disable(@Param("workspaceId") String workspaceId,
                @Param("userId") Long userId,
                @Param("updatedAt") LocalDateTime updatedAt);
}
