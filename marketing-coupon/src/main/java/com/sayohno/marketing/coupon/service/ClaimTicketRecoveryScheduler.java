package com.sayohno.marketing.coupon.service;

import com.sayohno.marketing.common.config.MarketingProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ClaimTicketRecoveryScheduler {
    private final VoucherClaimService claimService;
    private final MarketingProperties properties;

    public ClaimTicketRecoveryScheduler(VoucherClaimService claimService, MarketingProperties properties) {
        this.claimService = claimService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${CLAIM_RECOVERY_DELAY:5000}")
    public void recover() {
        if (properties.getOffer().isGrantBatchScanEnabled() || properties.getRedis().isEnabled()) {
            claimService.recoverStuck(properties.getOffer().getClaimRecoverAfter());
        }
    }
}
