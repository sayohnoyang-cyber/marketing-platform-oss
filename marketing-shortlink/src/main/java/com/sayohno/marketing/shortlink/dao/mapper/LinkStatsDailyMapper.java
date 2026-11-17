package com.sayohno.marketing.shortlink.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sayohno.marketing.shortlink.dao.entity.LinkStatsDaily;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface LinkStatsDailyMapper extends BaseMapper<LinkStatsDaily> {
    @Update("""
            UPDATE link_stats_daily
            SET pv = pv + 1, uv = uv + #{uvDelta}, update_time = #{now}
            WHERE id = #{id}
            """)
    int add(@Param("id") long id, @Param("uvDelta") int uvDelta, @Param("now") LocalDateTime now);
}
