package com.sayohno.marketing.shortlink.web.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UpdateLinkRequest {
    private String originUrl;
    private LocalDateTime validDate;
    private Integer enableStatus;
}
