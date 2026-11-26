package com.sayohno.marketing.common.outbox;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ConsumerInboxMapper extends BaseMapper<ConsumerInbox> {
    @Select("""
            SELECT id FROM infra_consumer_inbox
            WHERE create_time < #{cutoff}
            ORDER BY id
            LIMIT #{limit}
            """)
    List<Long> findExpiredIds(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    @Delete("""
            DELETE FROM infra_consumer_inbox
            WHERE create_time < #{cutoff}
            """)
    int deleteExpired(@Param("cutoff") LocalDateTime cutoff);
}
