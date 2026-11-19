package com.sayohno.marketing.common.outbox;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OutboxEventMapper extends BaseMapper<OutboxEvent> {

    @Select("""
            SELECT * FROM infra_outbox_event
            WHERE status IN (0, 1)
              AND next_retry_at <= #{now}
              AND (status = 0 OR update_time <= #{staleSending})
            ORDER BY id
            LIMIT #{limit}
            """)
    List<OutboxEvent> findDue(@Param("now") LocalDateTime now,
                              @Param("staleSending") LocalDateTime staleSending,
                              @Param("limit") int limit);

    @Update("""
            UPDATE infra_outbox_event
            SET status = 1, update_time = #{now}
            WHERE id = #{id}
              AND ((status = 0 AND next_retry_at <= #{now}) OR (status = 1 AND update_time <= #{staleSending}))
            """)
    int claim(@Param("id") long id,
              @Param("now") LocalDateTime now,
              @Param("staleSending") LocalDateTime staleSending);

    @Update("""
            UPDATE infra_outbox_event
            SET status = 2, update_time = #{now}, last_error = NULL
            WHERE id = #{id} AND status = 1
            """)
    int markSent(@Param("id") long id, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE infra_outbox_event
            SET status = CASE WHEN retry_count + 1 >= #{maxRetries} THEN 3 ELSE 0 END,
                retry_count = retry_count + 1,
                next_retry_at = #{nextRetryAt},
                last_error = #{lastError},
                update_time = #{now}
            WHERE id = #{id} AND status = 1
            """)
    int markRetry(@Param("id") long id,
                  @Param("now") LocalDateTime now,
                  @Param("nextRetryAt") LocalDateTime nextRetryAt,
                  @Param("lastError") String lastError,
                  @Param("maxRetries") int maxRetries);

    @Update("""
            UPDATE infra_outbox_event
            SET status = 0, retry_count = 0, next_retry_at = #{now}, last_error = NULL, update_time = #{now}
            WHERE event_id = #{eventId} AND status IN (2, 3)
            """)
    int reopen(@Param("eventId") String eventId, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE infra_outbox_event
            SET status = 3, last_error = #{error}, update_time = #{now}
            WHERE event_id = #{eventId} AND status <> 3
            """)
    int markDead(@Param("eventId") String eventId, @Param("error") String error, @Param("now") LocalDateTime now);

    @Delete("""
            DELETE FROM infra_outbox_event
            WHERE status = 2 AND update_time < #{cutoff}
            """)
    int deleteExpiredSent(@Param("cutoff") LocalDateTime cutoff);
}
