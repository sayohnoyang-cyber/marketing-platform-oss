package com.sayohno.marketing.campaign.web.dto;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class RefundSummary {
    private long refundCount;
    private long amountCount;
    private BigDecimal knownAmount;
}
