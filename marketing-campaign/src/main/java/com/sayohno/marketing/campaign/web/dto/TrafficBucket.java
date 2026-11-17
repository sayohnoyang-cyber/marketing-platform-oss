package com.sayohno.marketing.campaign.web.dto;

import lombok.Data;
import java.time.LocalDate;

@Data
public class TrafficBucket {
    private LocalDate statDate;
    private Integer bucketHour;
    private Long visitors;
}
