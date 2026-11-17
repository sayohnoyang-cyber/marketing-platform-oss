package com.sayohno.marketing.shortlink.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CreateLinkRequest {
    private String gid;
    @jakarta.validation.constraints.Positive
    private Long activityId;
    @NotBlank
    private String originUrl;
    private LocalDateTime validDate;
    private String idempotencyKey;
    @jakarta.validation.constraints.Size(max = 128)
    private String name;
    @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9]{2,12}")
    private String shortUri;
}
