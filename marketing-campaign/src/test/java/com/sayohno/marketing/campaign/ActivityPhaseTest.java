package com.sayohno.marketing.campaign;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ActivityPhaseTest {
    @Test
    void serverTimeDecidesPhaseWithoutAStatusFlip() {
        LocalDateTime warmup = LocalDateTime.of(2026, 11, 10, 8, 0);
        LocalDateTime start = LocalDateTime.of(2026, 11, 10, 10, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 10, 12, 0);
        assertEquals(ActivityPhase.WARMUP, ActivityPhase.of(ActivityStatuses.PUBLISHED, warmup, start, end, start.minusMinutes(1)));
        assertEquals(ActivityPhase.WARMUP, ActivityPhase.of(ActivityStatuses.PUBLISHED, warmup, start, end, warmup.minusHours(1)));
        assertFalse(ActivityPhase.of(ActivityStatuses.PUBLISHED, warmup, start, end, start.minusMinutes(1)).claimOpen());
        assertEquals(ActivityPhase.OFFLINE, ActivityPhase.of(ActivityStatuses.OFFLINE, warmup, start, end, start.plusMinutes(1)));
        assertFalse(ActivityPhase.of(ActivityStatuses.OFFLINE, warmup, start, end, start.plusMinutes(1)).claimOpen());
        assertFalse(ActivityPhase.of(ActivityStatuses.OFFLINE, warmup, start, end, start.minusMinutes(1)).reservationOpen());
        assertEquals(ActivityPhase.LIVE, ActivityPhase.of(ActivityStatuses.PUBLISHED, warmup, start, end, start.plusMinutes(1)));
        assertEquals(ActivityPhase.PAUSED, ActivityPhase.of(ActivityStatuses.PAUSED, warmup, start, end, start.plusMinutes(1)));
        assertFalse(ActivityPhase.of(ActivityStatuses.PAUSED, warmup, start, end, start.plusMinutes(1)).claimOpen());
    }
}
