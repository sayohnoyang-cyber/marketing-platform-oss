package com.sayohno.marketing.campaign.web.dto;
import jakarta.validation.constraints.*;
public record ApplyLimitRequest(@NotBlank @Size(max = 64) String requestKey, @Min(1) long forecastId) { }
