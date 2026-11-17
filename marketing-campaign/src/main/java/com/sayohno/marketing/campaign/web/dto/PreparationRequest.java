package com.sayohno.marketing.campaign.web.dto;
import jakarta.validation.constraints.*;
public record PreparationRequest(@NotBlank @Size(max = 64) String requestKey) { }
