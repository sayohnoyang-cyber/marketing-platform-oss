package com.sayohno.marketing.common.lock;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface DistributedLockMapper extends BaseMapper<DistributedLockEntity> {

    @Insert("""
            INSERT INTO infra_distributed_lock (lock_name, owner_id, lease_until, update_time)
            VALUES (#{lockName}, #{ownerId}, #{leaseUntil}, #{now})
            """)
    int insertLock(@Param("lockName") String lockName,
                   @Param("ownerId") String ownerId,
                   @Param("leaseUntil") LocalDateTime leaseUntil,
                   @Param("now") LocalDateTime now);

    @Update("""
            UPDATE infra_distributed_lock
            SET owner_id = #{ownerId}, lease_until = #{leaseUntil}, update_time = #{now}
            WHERE lock_name = #{lockName} AND lease_until < #{now}
            """)
    int tryAcquire(@Param("lockName") String lockName,
                   @Param("ownerId") String ownerId,
                   @Param("leaseUntil") LocalDateTime leaseUntil,
                   @Param("now") LocalDateTime now);

    @Update("""
            UPDATE infra_distributed_lock
            SET lease_until = #{now}, update_time = #{now}
            WHERE lock_name = #{lockName} AND owner_id = #{ownerId}
            """)
    int releaseOwned(@Param("lockName") String lockName,
                     @Param("ownerId") String ownerId,
                     @Param("now") LocalDateTime now);
}
