package com.sayohno.marketing.campaign.web.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class LinkListEntry {
    private String entryKey;
    private String kind;
    private String name;
    private Long activityId;
    private String activityName;
    private Long channelId;
    private Long linkId;
    private String fullShortUrl;
    private String origin;
    private LocalDateTime validDate;
    private Boolean onFlag;
    private Boolean mainReady;
    private Boolean effectiveFlag;
    private Integer activityStatus;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String source;
    private Long visits;
    private String phase;
    private String page;
}
