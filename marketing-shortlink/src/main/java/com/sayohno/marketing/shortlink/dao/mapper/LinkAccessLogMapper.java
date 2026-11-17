package com.sayohno.marketing.shortlink.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sayohno.marketing.shortlink.dao.entity.LinkAccessLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LinkAccessLogMapper extends BaseMapper<LinkAccessLog> {
    @Select("""
            SELECT l.id AS id, COUNT(a.id) AS pv, COUNT(DISTINCT a.visitor_id) AS visits
            FROM link l LEFT JOIN link_access_log a ON a.link_id = l.id AND a.visitor_id != 'anonymous'
            WHERE l.merchant_id = #{merchantId} AND l.del_time = 0
            GROUP BY l.id
            """)
    java.util.List<java.util.Map<String, Object>> byMerchant(@Param("merchantId") long merchantId);
}
