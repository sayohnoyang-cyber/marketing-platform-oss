package com.sayohno.marketing.coupon.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sayohno.marketing.coupon.dao.entity.IssuedVoucher;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface IssuedVoucherMapper extends BaseMapper<IssuedVoucher> {
    @Select("""
            SELECT claim_seq FROM issued_voucher
            WHERE user_id = #{userId} AND plan_id = #{planId}
            ORDER BY claim_seq DESC LIMIT 1 FOR UPDATE
            """)
    Integer lockLatestClaimSeq(@Param("userId") long userId, @Param("planId") long planId);
}
