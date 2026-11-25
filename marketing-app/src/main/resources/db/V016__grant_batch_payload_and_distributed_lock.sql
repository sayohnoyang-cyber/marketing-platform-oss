-- 批量发券任务增加共享数据库载荷存储（消除多实例本机 /tmp 依赖），并增加通用数据库分布式租约锁表。
ALTER TABLE grant_batch
    ADD COLUMN file_content MEDIUMBLOB NULL COMMENT '共享存储的发券原始Excel字节，支持多实例无状态抢占执行' AFTER file_path;

CREATE TABLE IF NOT EXISTS infra_distributed_lock (
    lock_name VARCHAR(64) NOT NULL,
    owner_id VARCHAR(64) NOT NULL,
    lease_until DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (lock_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定时任务与全局协调分布式租约锁表（Redis未启用时兜底）';
