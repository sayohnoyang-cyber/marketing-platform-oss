package com.sayohno.marketing.coupon.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sayohno.marketing.coupon.dao.entity.VoucherRedemption;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface VoucherRedemptionMapper extends BaseMapper<VoucherRedemption> {
    @Select("""
            <script>
            SELECT r.id AS id,
                   r.voucher_id AS voucherId,
                   r.order_no AS orderNo,
                   r.order_amount AS orderAmount,
                   r.discount_amount AS discountAmount,
                   r.refund_amount AS refundAmount,
                   r.refunded_at AS refundedAt,
                   r.status AS status,
                   r.create_time AS createTime,
                   r.update_time AS updateTime,
                   p.title AS couponTitle,
                   p.benefit_policy AS benefitPolicy,
                   v.status AS voucherStatus
            FROM voucher_redemption r
            JOIN issued_voucher v ON v.id = r.voucher_id
            JOIN offer_plan p ON p.id = v.plan_id
            WHERE p.merchant_id = #{merchantId}
              AND v.del_flag = 0
              AND p.del_flag = 0
            <if test="status != null and status != ''">
              AND r.status = #{status}
            </if>
            <if test="orderLike != null and orderLike != ''">
              AND (r.order_no LIKE #{orderLike}
              <if test="voucherId != null">
                OR r.voucher_id = #{voucherId}
              </if>
              )
            </if>
            ORDER BY r.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Map<String, Object>> selectMerchantRedemptions(@Param("merchantId") long merchantId,
                                                        @Param("orderLike") String orderLike,
                                                        @Param("voucherId") Long voucherId,
                                                        @Param("status") String status,
                                                        @Param("offset") long offset,
                                                        @Param("limit") long limit);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM voucher_redemption r
            JOIN issued_voucher v ON v.id = r.voucher_id
            JOIN offer_plan p ON p.id = v.plan_id
            WHERE p.merchant_id = #{merchantId}
              AND v.del_flag = 0
              AND p.del_flag = 0
            <if test="status != null and status != ''">
              AND r.status = #{status}
            </if>
            <if test="orderLike != null and orderLike != ''">
              AND (r.order_no LIKE #{orderLike}
              <if test="voucherId != null">
                OR r.voucher_id = #{voucherId}
              </if>
              )
            </if>
            </script>
            """)
    long countMerchantRedemptions(@Param("merchantId") long merchantId,
                                  @Param("orderLike") String orderLike,
                                  @Param("voucherId") Long voucherId,
                                  @Param("status") String status);
}
