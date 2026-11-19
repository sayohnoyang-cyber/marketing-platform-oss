package com.sayohno.marketing.campaign;

import java.time.LocalDateTime;

public enum ActivityPhase {
    DRAFT,
    WARMUP,
    LIVE,
    ENDED,
    PAUSED,
    OFFLINE,
    CANCELLED;

    /**
     * 活动运行阶段由发布状态、开场时间、结束时间与服务端当前时间推导。
     * 已发布且未到开场时间一律处于 WARMUP（开放预约）；warmupStart 仅保留数据库与旧调用签名兼容（P2-3）。
     */
    public static ActivityPhase of(int status, LocalDateTime warmupStart, LocalDateTime start, LocalDateTime end, LocalDateTime now) {
        return of(status, start, end, now);
    }

    public static ActivityPhase of(int status, LocalDateTime start, LocalDateTime end, LocalDateTime now) {
        if (status == ActivityStatuses.CANCELLED) {
            return CANCELLED;
        }
        if (status == ActivityStatuses.OFFLINE) {
            return OFFLINE;
        }
        if (status == ActivityStatuses.PAUSED) {
            return PAUSED;
        }
        if (status == ActivityStatuses.DRAFT) {
            return DRAFT;
        }
        if (now.isBefore(start)) {
            return WARMUP;
        }
        if (!now.isBefore(end)) {
            return ENDED;
        }
        return LIVE;
    }

    public boolean claimOpen() {
        return this == LIVE;
    }

    public boolean reservationOpen() {
        return this == WARMUP;
    }
}
