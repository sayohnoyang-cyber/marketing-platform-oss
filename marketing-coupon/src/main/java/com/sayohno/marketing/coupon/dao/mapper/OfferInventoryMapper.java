package com.sayohno.marketing.coupon.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sayohno.marketing.coupon.dao.entity.OfferInventory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface OfferInventoryMapper extends BaseMapper<OfferInventory> {
    @Update("""
            UPDATE offer_inventory
            SET quantity = quantity + #{delta}, update_time = #{now}
            WHERE id = #{id} AND status = 'open' AND quantity = #{expected}
            """)
    int addQuantity(@Param("id") long id, @Param("delta") int delta, @Param("expected") int expected, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE offer_inventory
            SET quantity = quantity - #{delta}, update_time = #{now}
            WHERE id = #{id} AND status = 'open' AND quantity = #{expected} AND quantity - claimed_count >= #{delta}
            """)
    int reduceQuantity(@Param("id") long id, @Param("delta") int delta, @Param("expected") int expected, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE offer_inventory
            SET claimed_count = claimed_count + 1, update_time = #{now}
            WHERE id = #{id} AND status = 'open' AND claimed_count < quantity
            """)
    int claimOne(@Param("id") long id, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE offer_inventory
            SET quantity = claimed_count, status = 'frozen', update_time = #{now}
            WHERE id = #{id} AND status = 'open'
            """)
    int freeze(@Param("id") long id, @Param("now") LocalDateTime now);
}
