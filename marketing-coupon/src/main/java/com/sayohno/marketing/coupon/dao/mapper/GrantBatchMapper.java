package com.sayohno.marketing.coupon.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sayohno.marketing.coupon.dao.entity.GrantBatch;
import com.sayohno.marketing.coupon.enums.GrantBatchStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface GrantBatchMapper extends BaseMapper<GrantBatch> {
    @Update("UPDATE grant_batch SET input_file = #{file} WHERE id = #{id}")
    int saveInput(@Param("id") long id, @Param("file") byte[] file);

    record BatchInput(byte[] contents) {}

    @Select("SELECT input_file AS contents FROM grant_batch WHERE id = #{id} AND input_file IS NOT NULL")
    BatchInput loadInput(@Param("id") long id);

    @Update("""
            UPDATE grant_batch
            SET status = 'running', owner_token = #{token}, lease_until = #{leaseUntil}, update_time = #{now}
            WHERE id = #{id} AND del_flag = 0
              AND (run_at IS NULL OR run_at <= #{now})
              AND (status = 'queued' OR (status = 'running' AND (lease_until IS NULL OR lease_until < #{now})))
            """)
    int acquire(@Param("id") long id,
                @Param("token") String token,
                @Param("leaseUntil") LocalDateTime leaseUntil,
                @Param("now") LocalDateTime now);

    @Update("""
            UPDATE grant_batch
            SET lease_until = #{leaseUntil}, update_time = #{now}
            WHERE id = #{id} AND owner_token = #{token} AND status = 'running'
            """)
    int renew(@Param("id") long id,
              @Param("token") String token,
              @Param("leaseUntil") LocalDateTime leaseUntil,
              @Param("now") LocalDateTime now);

    @Update("""
            UPDATE grant_batch
            SET total_count = #{total}, success_count = #{success}, fail_count = #{fail},
                progress = #{progress}, update_time = #{now}
            WHERE id = #{id} AND owner_token = #{token} AND status = 'running'
            """)
    int saveProgress(@Param("id") long id,
                     @Param("token") String token,
                     @Param("total") int total,
                     @Param("success") int success,
                     @Param("fail") int fail,
                     @Param("progress") int progress,
                     @Param("now") LocalDateTime now);

    @Update("""
            UPDATE grant_batch
            SET status = #{status.code}, completion_time = #{now}, input_file = NULL, progress = 100, owner_token = NULL, update_time = #{now}
            WHERE id = #{id} AND owner_token = #{token} AND status = 'running'
            """)
    int finishOwned(@Param("id") long id,
                    @Param("token") String token,
                    @Param("status") GrantBatchStatus status,
                    @Param("now") LocalDateTime now);
}
