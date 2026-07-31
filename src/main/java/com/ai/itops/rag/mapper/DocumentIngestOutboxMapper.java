package com.ai.itops.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ai.itops.rag.entity.DocumentIngestOutbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@link DocumentIngestOutbox} 持久化访问。
 */
@Mapper
public interface DocumentIngestOutboxMapper extends BaseMapper<DocumentIngestOutbox> {

    @Select("""
            <script>
            SELECT *
            FROM document_ingest_outbox
            WHERE published_at IS NULL
              AND (
                ((status = 'PENDING' OR status = 'RETRY') AND next_retry_at &lt;= #{now})
                OR (status = 'DISPATCHING' AND updated_at &lt;= #{dispatchingExpireAt})
              )
            ORDER BY next_retry_at ASC, id ASC
            LIMIT #{limit}
            </script>
            """)
    List<DocumentIngestOutbox> selectDueForDispatch(@Param("now") LocalDateTime now,
                                                    @Param("dispatchingExpireAt") LocalDateTime dispatchingExpireAt,
                                                    @Param("limit") int limit);

    @Update("""
            UPDATE document_ingest_outbox
            SET status = 'DISPATCHING',
                attempt_count = attempt_count + 1,
                updated_at = #{now}
            WHERE id = #{id}
              AND published_at IS NULL
              AND attempt_count = #{attemptCount}
              AND (
                ((status = 'PENDING' OR status = 'RETRY') AND next_retry_at <= #{now})
                OR (status = 'DISPATCHING' AND updated_at <= #{dispatchingExpireAt})
              )
            """)
    int claimForDispatch(@Param("id") Long id,
                         @Param("attemptCount") int attemptCount,
                         @Param("now") LocalDateTime now,
                         @Param("dispatchingExpireAt") LocalDateTime dispatchingExpireAt);

    @Update("""
            UPDATE document_ingest_outbox
            SET status = 'PUBLISHED',
                published_at = #{publishedAt},
                dead_lettered_at = NULL,
                next_retry_at = NULL,
                stream_record_id = #{streamRecordId},
                last_error = NULL,
                updated_at = #{publishedAt}
            WHERE id = #{id}
            """)
    int markPublished(@Param("id") Long id,
                      @Param("streamRecordId") String streamRecordId,
                      @Param("publishedAt") LocalDateTime publishedAt);

    @Update("""
            UPDATE document_ingest_outbox
            SET status = 'RETRY',
                next_retry_at = #{nextRetryAt},
                last_error = #{lastError},
                updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    int markRetry(@Param("id") Long id,
                  @Param("nextRetryAt") LocalDateTime nextRetryAt,
                  @Param("lastError") String lastError,
                  @Param("updatedAt") LocalDateTime updatedAt);

    @Update("""
            UPDATE document_ingest_outbox
            SET status = 'DEAD',
                dead_lettered_at = #{deadLetteredAt},
                next_retry_at = NULL,
                last_error = #{lastError},
                updated_at = #{deadLetteredAt}
            WHERE id = #{id}
            """)
    int markDead(@Param("id") Long id,
                 @Param("lastError") String lastError,
                 @Param("deadLetteredAt") LocalDateTime deadLetteredAt);

    @Select("""
            SELECT *
            FROM document_ingest_outbox
            WHERE task_id = #{taskId}
            LIMIT 1
            """)
    DocumentIngestOutbox selectByTaskId(@Param("taskId") String taskId);

    @Select("""
            SELECT *
            FROM document_ingest_outbox
            WHERE status = 'DEAD'
            ORDER BY updated_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<DocumentIngestOutbox> selectDeadLetters(@Param("limit") int limit);

    @Update("""
            UPDATE document_ingest_outbox
            SET status = 'RETRY',
                next_retry_at = #{now},
                published_at = NULL,
                dead_lettered_at = NULL,
                stream_record_id = NULL,
                last_error = NULL,
                updated_at = #{now}
            WHERE task_id = #{taskId}
            """)
    int resetForReplay(@Param("taskId") String taskId, @Param("now") LocalDateTime now);
}
