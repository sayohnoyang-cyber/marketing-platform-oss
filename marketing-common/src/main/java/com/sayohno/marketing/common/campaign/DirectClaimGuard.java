package com.sayohno.marketing.common.campaign;

import java.time.LocalDateTime;

public interface DirectClaimGuard {
    void assertDirectClaimAllowed(long templateId);

    /**
     * 校验已关联活动的优惠券模板是否允许修改有效期与每人限领，防止改坏预热或进行中的活动。
     */
    default void assertTemplateRevisionAllowed(long templateId, LocalDateTime validStart, LocalDateTime validEnd, int maxClaimsPerUser) {
    }

    /**
     * 校验已关联活动的优惠券模板是否允许提前终止。
     */
    default void assertTemplateTerminationAllowed(long templateId) {
    }
}
