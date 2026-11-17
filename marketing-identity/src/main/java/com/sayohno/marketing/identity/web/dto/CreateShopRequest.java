package com.sayohno.marketing.identity.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateShopRequest {
    @NotBlank
    @Size(max = 128)
    private String name;
}
