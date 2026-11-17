package com.sayohno.marketing.coupon.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sayohno.marketing.coupon.dao.entity.OfferPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface OfferPlanMapper extends BaseMapper<OfferPlan> {
    @Update("""
            UPDATE offer_plan
            SET inventory_qty = inventory_qty - #{count}, update_time = #{now}
            WHERE id = #{id} AND inventory_qty >= #{count} AND status = 'open' AND del_flag = 0
            """)
    int decrementStock(@Param("id") long id, @Param("count") int count, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE offer_plan
            SET inventory_qty = inventory_qty + #{count}, issued_total = issued_total + #{count}, update_time = #{now}
            WHERE id = #{id} AND merchant_id = #{merchantId} AND del_flag = 0
            """)
    int increaseStock(@Param("id") long id,
                      @Param("merchantId") long merchantId,
                      @Param("count") int count,
                      @Param("now") LocalDateTime now);

    @Update("""
            UPDATE offer_plan
            SET inventory_qty = inventory_qty + #{count}, update_time = #{now}
            WHERE id = #{id} AND del_flag = 0
            """)
    int returnUnallocated(@Param("id") long id, @Param("count") int count, @Param("now") LocalDateTime now);
}
