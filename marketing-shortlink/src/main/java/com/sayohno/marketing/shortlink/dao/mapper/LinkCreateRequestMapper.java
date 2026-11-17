package com.sayohno.marketing.shortlink.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sayohno.marketing.shortlink.dao.entity.LinkCreateRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface LinkCreateRequestMapper extends BaseMapper<LinkCreateRequest> {
    @Select("""
            SELECT * FROM link_create_request
            WHERE merchant_id = #{merchantId} AND idempotency_key = #{idempotencyKey}
            FOR UPDATE
            """)
    LinkCreateRequest lockByKey(@Param("merchantId") long merchantId, @Param("idempotencyKey") String idempotencyKey);
}
