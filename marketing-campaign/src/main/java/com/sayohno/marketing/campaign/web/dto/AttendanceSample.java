package com.sayohno.marketing.campaign.web.dto;

import lombok.Data;

@Data
public class AttendanceSample {
    private Long channelId;
    private String name;
    private long reservations;
    private long arrivals;
}
