package com.ai.itops.rag.tracing;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** RAG 质量追踪的 MyBatis-Plus 数据访问接口。 */
@Mapper
public interface RagTraceMapper extends BaseMapper<RagTraceDocument> {
}
