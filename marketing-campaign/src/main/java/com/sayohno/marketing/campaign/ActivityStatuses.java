package com.sayohno.marketing.campaign;

public final class ActivityStatuses {
    public static final int DRAFT = 0;
    public static final int PUBLISHED = 1;
    /** 下线：停止新的预约和领取，保留本场投放，结束前可以重新发布。 */
    public static final int OFFLINE = 4;
    public static final int PAUSED = 5;
    /** 终止：不可恢复，并回收未领取数量。历史数据里原先的“取消”也使用这个值。 */
    public static final int CANCELLED = 6;

    public static final int RESERVED = 1;
    public static final int RESERVATION_CANCELLED = 0;

    private ActivityStatuses() {
    }
}
