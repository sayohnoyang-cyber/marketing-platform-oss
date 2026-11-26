package com.sayohno.marketing.shortlink.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sayohno.marketing.shortlink.dao.entity.LinkStatsDaily;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Mapper
public interface LinkStatsDailyMapper extends BaseMapper<LinkStatsDaily> {
    @Update("""
            UPDATE link_stats_daily
            SET pv = pv + 1, uv = uv + #{uvDelta}, update_time = #{now}
            WHERE id = #{id}
            """)
    int add(@Param("id") long id, @Param("uvDelta") int uvDelta, @Param("now") LocalDateTime now);

    /**
     * 直接按业务唯一键 (link_id, stat_date) 原子累加，省去热路径上的前置 SELECT，缩短行锁持有时间。
     */
    @Update("""
            UPDATE link_stats_daily
            SET pv = pv + 1, uv = uv + #{uvDelta}, update_time = #{now}
            WHERE link_id = #{linkId} AND stat_date = #{statDate}
            """)
    int addByLinkAndDate(@Param("linkId") long linkId,
                         @Param("statDate") LocalDate statDate,
                         @Param("uvDelta") int uvDelta,
                         @Param("now") LocalDateTime now);
}
