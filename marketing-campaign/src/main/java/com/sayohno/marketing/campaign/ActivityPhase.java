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

    public static ActivityPhase of(int status, LocalDateTime warmupStart, LocalDateTime start, LocalDateTime end, LocalDateTime now) {
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
