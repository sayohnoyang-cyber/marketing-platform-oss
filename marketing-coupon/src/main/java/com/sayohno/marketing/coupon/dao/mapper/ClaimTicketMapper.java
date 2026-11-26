package com.sayohno.marketing.coupon.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sayohno.marketing.coupon.dao.entity.ClaimTicket;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface ClaimTicketMapper extends BaseMapper<ClaimTicket> {
    @Select("SELECT * FROM claim_ticket WHERE id = #{id} FOR UPDATE")
    ClaimTicket lockById(@Param("id") long id);

    @Update("""
            UPDATE claim_ticket
            SET status = 'succeeded', voucher_id = #{voucherId}, claim_seq = #{seq}, redis_applied = 1,
                reservation_token = NULL, lease_until = NULL, update_time = #{now}
            WHERE id = #{id} AND reservation_token = #{token} AND status = 'reserved'
            """)
    int succeed(@Param("id") long id, @Param("token") String token, @Param("voucherId") long voucherId,
                @Param("seq") int seq, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE claim_ticket SET status = 'pending', redis_applied = 0, reservation_token = NULL,
                lease_until = NULL, claim_seq = NULL, alloc_receive_count = NULL, update_time = #{now}
            WHERE id = #{id} AND status = 'failed'
            """)
    int reopenFailed(@Param("id") long id, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE claim_ticket
            SET reservation_token = #{token}, lease_until = #{leaseUntil}, update_time = #{now}
            WHERE id = #{id}
              AND status IN ('pending', 'reserved')
              AND (reservation_token IS NULL OR reservation_token = #{token} OR lease_until IS NULL OR lease_until < #{now})
            """)
    int acquireLease(@Param("id") long id,
                     @Param("token") String token,
                     @Param("leaseUntil") LocalDateTime leaseUntil,
                     @Param("now") LocalDateTime now);

    @Update("""
            UPDATE claim_ticket
            SET status = 'reserved', claim_seq = #{count}, alloc_receive_count = #{allocCount}, redis_applied = 1, update_time = #{now}
            WHERE id = #{id} AND reservation_token = #{token} AND status IN ('pending', 'reserved')
            """)
    int markReserved(@Param("id") long id,
                     @Param("token") String token,
                     @Param("count") int count,
                     @Param("allocCount") Integer allocCount,
                     @Param("now") LocalDateTime now);

    @Update("""
            UPDATE claim_ticket
            SET status = 'failed', redis_applied = 0, reservation_token = NULL, lease_until = NULL, update_time = #{now}
            WHERE id = #{id} AND reservation_token = #{token} AND status IN ('pending', 'reserved')
            """)
    int markFailed(@Param("id") long id, @Param("token") String token, @Param("now") LocalDateTime now);
}
