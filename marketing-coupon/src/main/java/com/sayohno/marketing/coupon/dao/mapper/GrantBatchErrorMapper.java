package com.sayohno.marketing.coupon.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sayohno.marketing.coupon.dao.entity.GrantBatchError;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface GrantBatchErrorMapper extends BaseMapper<GrantBatchError> {
    @Insert("""
            INSERT INTO grant_batch_error (id, batch_id, row_no, user_id, reason, create_time)
            SELECT #{id}, #{taskId}, #{rowNo}, #{userId}, #{reason}, #{now}
            FROM grant_batch
            WHERE id = #{taskId} AND owner_token = #{token} AND status = 'running'
            """)
    int insertOwned(@Param("id") long id,
                    @Param("taskId") long taskId,
                    @Param("rowNo") int rowNo,
                    @Param("userId") Long userId,
                    @Param("reason") String reason,
                    @Param("token") String token,
                    @Param("now") LocalDateTime now);

    @Delete("""
            DELETE FROM grant_batch_error
            WHERE batch_id = #{taskId} AND row_no = #{rowNo}
              AND EXISTS (
                SELECT 1 FROM grant_batch
                WHERE id = #{taskId} AND owner_token = #{token} AND status = 'running'
              )
            """)
    int deleteOwned(@Param("taskId") long taskId, @Param("rowNo") int rowNo, @Param("token") String token);
}
