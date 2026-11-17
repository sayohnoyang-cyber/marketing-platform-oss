package com.sayohno.marketing.campaign.web.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ActivitySpotlight {
    private Long id;
    private String name;
    private Integer status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer quantity;
    private Integer claimed;
    private String phase;
}
