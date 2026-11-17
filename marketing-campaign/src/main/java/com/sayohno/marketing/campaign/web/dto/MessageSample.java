package com.sayohno.marketing.campaign.web.dto;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class MessageSample {
    private long total;
    private long pending;
    private long sending;
    private long dead;
    private long retrying;
    private long awaitingReceipt;
    private long completed;
    private long stalled;
    private LocalDateTime oldestPending;
    private LocalDateTime lastConsumedAt;
}
