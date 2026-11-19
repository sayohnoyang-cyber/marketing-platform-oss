package com.sayohno.marketing.common.outbox;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface ConsumerInboxMapper extends BaseMapper<ConsumerInbox> {
    @Delete("""
            DELETE FROM infra_consumer_inbox
            WHERE create_time < #{cutoff}
            """)
    int deleteExpired(@Param("cutoff") LocalDateTime cutoff);
}
