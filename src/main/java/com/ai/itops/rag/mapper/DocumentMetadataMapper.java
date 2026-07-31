package com.ai.itops.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ai.itops.rag.entity.DocumentMetadata;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@link DocumentMetadata} 持久化访问。
 */
@Mapper
public interface DocumentMetadataMapper extends BaseMapper<DocumentMetadata> {
}
